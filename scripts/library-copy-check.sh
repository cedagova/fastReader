#!/usr/bin/env bash
# Proves the Reader libraries work as a source copy outside this build (#207,
# A197-F010; the consumption mode is recorded in docs/library-consumption.md).
#
# It reads the copy set from docs/library-consumption.md — the one list a host
# copies — and then:
#
#   1. checks that each copied module declares a version and that its
#      CHANGELOG.md has an entry for that version;
#   2. assembles a throwaway standalone Gradle project in a fresh temp dir:
#      the copy set's tracked files, a catalog holding ONLY the catalog entries
#      the copy set lists, and the host template in scripts/library-copy-check/;
#   3. runs that host's minified release build (R8), the copied tests the
#      copy set names and, when the copy set lists test fixtures, the host's
#      own unit test against them (#199), with the serialization runtime's embedded keep rules
#      ignored and the consumer rules of every module that does not keep its
#      own serialized types emptied in the copy;
#   4. reads R8's mapping and fails unless every @Serializable type of each
#      self-keeping module still has the members kotlinx.serialization looks up
#      at run time (`Companion` + its `serializer()`, or `INSTANCE` +
#      `serializer()`), under their own names.
#
# A missing file or catalog entry fails step 3's build or tests; a missing
# keep rule fails step 4. Nothing outside the temp dir is written, and the script deletes
# the temp dir on exit.
#
# Usage: scripts/library-copy-check.sh [--keep] [extra Gradle arguments...]
#   --keep   leave the temp project in place and print its path (debugging)
#
# Needs JDK 21 (JAVA_HOME) and an Android SDK: ANDROID_HOME, or this checkout's
# local.properties, whose sdk.dir is copied into the temp project.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
doc="$repo/docs/library-consumption.md"
template="$repo/scripts/library-copy-check"

die() {
  printf 'library-copy-check: %s\n' "$*" >&2
  exit 1
}
say() { printf 'library-copy-check: %s\n' "$*"; }

keep=false
if [[ "${1:-}" == "--keep" ]]; then
  keep=true
  shift
fi

# --- 1. The copy set --------------------------------------------------------
[[ -f "$doc" ]] || die "missing $doc"
copy_set="$(awk '/<!-- copy-set:begin -->/ { on = 1; next } /<!-- copy-set:end -->/ { on = 0 } on' "$doc" |
  grep -v '^```' | sed 's/#.*//' | awk 'NF')" || true
[[ -n "$copy_set" ]] || die "no copy set between the copy-set markers in $doc"

modules=()
self_kept=()
paths=()
versions=()
libraries=()
plugins=()
test_tasks=()
fixture_modules=()
while read -r kind rest; do
  fields=()
  [[ -n "$rest" ]] && read -r -a fields <<<"$rest"
  [[ ${#fields[@]} -gt 0 ]] || die "copy-set entry '$kind' has no value"
  case "$kind" in
    module)
      [[ ${#fields[@]} -le 2 ]] || die "copy-set entry 'module ${rest}': expected 'module <dir> [<package>]'"
      modules+=("${fields[0]}")
      paths+=("${fields[0]}")
      if [[ ${#fields[@]} -eq 2 ]]; then self_kept+=("${fields[0]}=${fields[1]}"); fi
      ;;
    path) paths+=("${fields[@]}") ;;
    versions) versions+=("${fields[@]}") ;;
    libraries) libraries+=("${fields[@]}") ;;
    plugins) plugins+=("${fields[@]}") ;;
    test)
      [[ ${#fields[@]} -eq 2 ]] || die "copy-set entry 'test ${rest}': expected 'test <module> <test class pattern>'"
      test_tasks+=(":${fields[0]}:testDebugUnitTest" --tests "${fields[1]}")
      ;;
    fixtures) fixture_modules+=("${fields[@]}") ;;
    *) die "unknown copy-set entry '$kind' in $doc" ;;
  esac
done <<<"$copy_set"
[[ ${#modules[@]} -gt 0 ]] || die "the copy set lists no module"
[[ ${#self_kept[@]} -gt 0 ]] || die "the copy set names no module that keeps its own serialized types"
for fixture in ${fixture_modules[@]+"${fixture_modules[@]}"}; do
  listed_module=false
  for module in "${modules[@]}"; do [[ "$module" == "$fixture" ]] && listed_module=true; done
  $listed_module || die "copy-set entry 'fixtures $fixture': not a listed module"
  [[ -d "$repo/$fixture/src/testFixtures" ]] || die "copy-set entry 'fixtures $fixture': $fixture has no src/testFixtures"
done
# The host's unit test against the fixtures (scripts/library-copy-check/consumer/src/test).
if [[ ${#fixture_modules[@]} -gt 0 ]]; then test_tasks+=(":consumer:testDebugUnitTest"); fi

for module in "${modules[@]}"; do
  build_file="$repo/$module/build.gradle.kts"
  [[ -f "$build_file" ]] || die "module '$module' has no build.gradle.kts"
  version="$(sed -nE 's/^version = "([^"]+)"$/\1/p' "$build_file")"
  [[ -n "$version" ]] || die "$module/build.gradle.kts declares no top-level version = \"x.y.z\""
  [[ -f "$repo/$module/CHANGELOG.md" ]] || die "$module has no CHANGELOG.md"
  awk -v v="$version" '$1 == "##" && $2 == v { found = 1 } END { exit !found }' "$repo/$module/CHANGELOG.md" ||
    die "$module/CHANGELOG.md has no '## $version' entry for the version its build file declares"
  say "$module $version"
done

# --- 2. The throwaway project -----------------------------------------------
work="$(mktemp -d "${TMPDIR:-/tmp}/library-copy-check.XXXXXX")"
[[ -n "$work" && -d "$work" ]] || die "could not create a temp dir"
cleanup() {
  if $keep; then
    say "kept the throwaway project at $work"
  else
    rm -rf -- "$work"
  fi
}
trap cleanup EXIT

# Tracked (and new, not ignored) files only: what a copy of a tagged checkout
# holds, never build output or machine-local files.
for path in "${paths[@]}"; do
  [[ -e "$repo/$path" ]] || die "copy-set path '$path' does not exist"
  listed="$(git -C "$repo" ls-files --cached --others --exclude-standard -- "$path")"
  [[ -n "$listed" ]] || die "copy-set path '$path' holds no tracked file"
  while IFS= read -r file; do
    [[ -e "$repo/$file" ]] || continue # deleted in this worktree, not yet committed
    mkdir -p "$work/$(dirname "$file")"
    cp -p "$repo/$file" "$work/$file"
  done <<<"$listed"
done

# The catalog: only the listed entries, each line exactly as the root catalog
# declares it. A listed key the root catalog lacks fails here; an entry the
# copied builds need but the list lacks fails the Gradle build below.
mkdir -p "$work/gradle"
awk -v want_versions="${versions[*]-}" -v want_libraries="${libraries[*]-}" -v want_plugins="${plugins[*]-}" '
  BEGIN {
    split("versions libraries plugins", order, " ")
    n = split(want_versions, a, " "); for (i = 1; i <= n; i++) want["versions", a[i]] = 1
    n = split(want_libraries, a, " "); for (i = 1; i <= n; i++) want["libraries", a[i]] = 1
    n = split(want_plugins, a, " "); for (i = 1; i <= n; i++) want["plugins", a[i]] = 1
  }
  /^\[[a-z]+\]/ { section = substr($1, 2, length($1) - 2); next }
  /^[A-Za-z0-9_.-]+[ \t]*=/ {
    key = $0; sub(/[ \t]*=.*/, "", key)
    if ((section, key) in want) { out[section] = out[section] $0 "\n"; found[section, key] = 1 }
  }
  END {
    for (k in want) if (!(k in found)) {
      split(k, parts, SUBSEP)
      printf "library-copy-check: gradle/libs.versions.toml has no [%s] entry \"%s\"\n", parts[1], parts[2] > "/dev/stderr"
      missing = 1
    }
    if (missing) exit 1
    print "# The catalog entries the copy set lists (docs/library-consumption.md), nothing else."
    for (i = 1; i <= 3; i++) printf "\n[%s]\n%s", order[i], out[order[i]]
  }
' "$repo/gradle/libs.versions.toml" >"$work/gradle/libs.versions.toml" || die "the catalog entries the copy set lists are incomplete"

cp -R "$template/." "$work/"
printf '%s\n' "${modules[@]}" >"$work/copied-modules.txt"
printf '%s\n' ${fixture_modules[@]+"${fixture_modules[@]}"} >"$work/fixture-modules.txt"

# The host's rules: keep every @Serializable class of each self-keeping module,
# as a host that reaches all of them does, and nothing else about them. R8
# lists the classes this rule matched in seeds.txt, which step 4 reads.
{
  echo "# Generated by scripts/library-copy-check.sh."
  for entry in "${self_kept[@]}"; do
    echo "-keep,allowobfuscation @kotlinx.serialization.Serializable class ${entry#*=}.**"
  done
} >"$work/consumer/host-rules.pro"

# A module listed without a package keeps serialized types it does not own
# (:reader-auth's rules match every package), so in this copy only its
# consumer-rules.pro is emptied: then a self-keeping module's types can be kept
# by nothing but its own rules. The host build ignores the serialization jar's
# embedded rules for the same reason (scripts/library-copy-check/consumer/).
# AGP's `ignoreFrom` accepts only remote libraries, not a project module.
for module in "${modules[@]}"; do
  is_self_kept=false
  for entry in "${self_kept[@]}"; do [[ "${entry%%=*}" == "$module" ]] && is_self_kept=true; done
  if ! $is_self_kept && [[ -f "$work/$module/consumer-rules.pro" ]]; then
    echo "# Emptied in the copy check's throwaway copy only (scripts/library-copy-check.sh)." >"$work/$module/consumer-rules.pro"
  fi
done

if [[ -f "$repo/local.properties" ]]; then
  grep '^sdk\.dir=' "$repo/local.properties" >"$work/local.properties" || true
fi
[[ -s "$work/local.properties" || -n "${ANDROID_HOME:-}" || -n "${ANDROID_SDK_ROOT:-}" ]] ||
  die "no Android SDK: set ANDROID_HOME or sdk.dir in $repo/local.properties"

# --- 3. The host's minified build --------------------------------------------
say "building the throwaway host in $work"
"$repo/gradlew" -p "$work" --console=plain :consumer:minifyReleaseWithR8 ${test_tasks[@]+"${test_tasks[@]}"} "$@" ||
  die "the throwaway host did not build or a copied test failed (Gradle output above); a file or catalog entry missing from the copy set is the usual cause"

# --- 4. The serialized types survived ---------------------------------------
outputs="$work/consumer/build/outputs/mapping/release"
[[ -f "$outputs/mapping.txt" && -f "$outputs/seeds.txt" && -f "$outputs/configuration.txt" ]] ||
  die "R8 wrote no mapping.txt/seeds.txt/configuration.txt in $outputs"
# The isolation itself: no rule R8 received may keep @Serializable classes of
# every package, or a self-keeping module's missing rule would go unnoticed.
if grep -nE '@kotlinx\.serialization\.Serializable class \*\*([^.]|$)' "$outputs/configuration.txt"; then
  die "a keep rule above reaches every package's @Serializable classes, so this check cannot tell whether a module keeps its own; find its source in $outputs/configuration.txt and ignore it"
fi
for entry in "${self_kept[@]}"; do
  module="${entry%%=*}"
  package="${entry#*=}"
  awk -v package="$package." -v module="$module" '
    # seeds.txt: the classes the host rule kept, i.e. the @Serializable ones.
    FNR == NR {
      if (index($0, package) == 1 && index($0, ":") == 0) checked[$0] = 1
      next
    }
    # mapping.txt: "original -> obfuscated:" opens a class; indented lines are
    # its members, "[lines:]type name[(args)][:lines] -> new name".
    /^[^ #]/ { class = $1; present[class] = 1; next }
    /^    / {
      line = $0; sub(/^ +/, "", line); sub(/^[0-9]+:[0-9]+:/, "", line)
      n = split(line, tok, " ")
      if (n < 4 || tok[n - 1] != "->") next
      name = tok[2]; kept = tok[n]
      if (name == "Companion" && kept == "Companion") companion[class] = tok[1]
      if (name == "INSTANCE" && kept == "INSTANCE") instance[class] = 1
      if (name ~ /^serializer\(/ && kept == "serializer") serializer[class] = 1
    }
    END {
      count = 0
      for (c in checked) {
        count++
        if (!(c in present)) { print "  " c ": removed by R8"; bad++; continue }
        if (c in companion) {
          if (!(companion[c] in serializer)) { print "  " c ": " companion[c] ".serializer() not kept"; bad++ }
        } else if (c in instance) {
          if (!(c in serializer)) { print "  " c ": serializer() not kept"; bad++ }
        } else { print "  " c ": neither Companion nor INSTANCE kept under its own name"; bad++ }
      }
      if (count == 0) { print "  no @Serializable class of " package "** reached R8"; exit 1 }
      if (bad) exit 1
      printf "library-copy-check: %s: %d serialized types keep their serializer lookup under R8\n", module, count
    }
  ' "$outputs/seeds.txt" "$outputs/mapping.txt" ||
    die "$module does not keep its own serialized types under R8 (above); fix its consumer rules ($module/consumer-rules.pro, or src/main/resources/META-INF/proguard/ for a JVM module)"
done

say "OK: the copy set builds and shrinks as a standalone host"
