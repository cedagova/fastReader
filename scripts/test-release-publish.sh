#!/usr/bin/env bash
#
# Shell-level test for the publish path of scripts/release.sh.
#
#   scripts/test-release-publish.sh
#
# macOS ships bash 3.2, where `set -u` rejects the expansion of an *empty*
# array. That is how the stable publish path shipped broken (issue #30): the
# `--prerelease` run populated its array, the stable one did not, and only the
# stable one aborted — after the build, right before `gh release create`.
#
# This test runs the real scripts/release.sh under /bin/bash (3.2) inside a
# throwaway sandbox: a stub gradlew, apksigner, aapt2, java, gh, and curl, so
# nothing is built, signed, published, or downloaded for real. It asserts that
# both paths reach `gh release create` and that only the pre-release one passes
# `--prerelease`.
#
# Since #100 it is also where the manifest gate's "fails it" half is proven
# (REQ-411): the stub aapt2 emits the two permissions a release may carry and a
# clean manifest by default, and four negative runs hand it a third permission,
# a missing INTERNET line, a networkSecurityConfig attribute, and a
# usesCleartextTraffic attribute — each must die before `gh release create`,
# and no rogue APK is ever built to prove it.
#
# Since #206 it also proves the pre-publish guards fail loud: a `gh` that
# fails (auth, network) on the existing-tag check, the published-version list
# or the hosted-checks lookup stops the release before anything is built, as do
# a target commit whose checks run is missing, unfinished or failed. A
# successful but empty release list (the first release) still publishes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASH32="${BASH32:-/bin/bash}"
SANDBOX_BASE="$(mktemp -d -t fastreader-release-test)"
trap 'rm -rf "$SANDBOX_BASE"' EXIT

VERSION_CODE=42
VERSION_NAME=9.9.9
PUBLISHED_HIGHEST=v9.0.0
# Public value, pinned in release.sh and documented in docs/release.md. Read it
# from the script so the stub signer never drifts from the real pin.
CERT_SHA256="$(sed -n 's/^EXPECTED_CERT_SHA256="\(.*\)"$/\1/p' "$REPO_ROOT/scripts/release.sh")"
[ -n "$CERT_SHA256" ] || { echo "test: could not read EXPECTED_CERT_SHA256 from release.sh" >&2; exit 1; }

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# What the stub aapt2 reports for the badging's uses-permission lines and for
# the manifest's <application> attributes. The defaults are a compliant release;
# a negative case overrides one of them before calling run_release.
PERMISSIONS_DEFAULT="android.permission.INTERNET
com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
PERMISSIONS="$PERMISSIONS_DEFAULT"
MANIFEST_EXTRA_ATTRIBUTES=""

# How the stub gh answers the three pre-publish queries. The defaults are a
# healthy GitHub: the tag is unused, v9.0.0 is published, and the target
# commit's one checks run passed. A negative case overrides one of them.
#   GH_VIEW  notfound | exists | fail   (`gh release view <tag>`)
#   GH_LIST  ok | empty | fail          (`gh release list`)
#   GH_RUNS  "<status> <conclusion>" lines as release.sh's --jq prints them, or fail
GH_VIEW=notfound
GH_LIST=ok
GH_RUNS="completed success"

# Builds one disposable repository root with every external tool stubbed, then
# runs the real release script in it. Extra arguments go to release.sh.
run_release() {
  local name="$1"; shift
  ROOT="$SANDBOX_BASE/$name"
  CALLS="$ROOT/.gh-calls"
  mkdir -p "$ROOT/scripts" "$ROOT/bin" "$ROOT/jdk/bin" "$ROOT/sdk/build-tools/36.0.0" "$CALLS"

  # The script under test, byte-identical, so the sandbox becomes its REPO_ROOT.
  cp "$REPO_ROOT/scripts/release.sh" "$ROOT/scripts/release.sh"
  chmod +x "$ROOT/scripts/release.sh"
  printf 'versionCode=%s\nversionName=%s\n' "$VERSION_CODE" "$VERSION_NAME" > "$ROOT/version.properties"

  cat > "$ROOT/gradlew" <<'STUB'
#!/bin/bash
mkdir -p app/build/outputs/apk/release
printf 'stub release apk\n' > app/build/outputs/apk/release/app-release.apk
STUB

  cat > "$ROOT/jdk/bin/java" <<'STUB'
#!/bin/bash
printf 'openjdk version "21.0.0" (stub)\n' >&2
STUB

  cat > "$ROOT/sdk/build-tools/36.0.0/apksigner" <<STUB
#!/bin/bash
cat <<'CERTS'
Verified
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate SHA-256 digest: $CERT_SHA256
Signer #1 key size (bits): 4096
CERTS
STUB

  # Answers the two queries release.sh makes: `dump badging` (package line,
  # SDK lines, one uses-permission line per PERMISSIONS entry) and
  # `dump xmltree` (a minimal manifest whose <application> element carries
  # whatever MANIFEST_EXTRA_ATTRIBUTES names).
  local permission_lines=""
  local p
  while IFS= read -r p; do
    [ -n "$p" ] && permission_lines="$permission_lines
uses-permission: name='$p'"
  done <<< "$PERMISSIONS"
  cat > "$ROOT/sdk/build-tools/36.0.0/aapt2" <<STUB
#!/bin/bash
case "\${1:-} \${2:-}" in
  "dump badging")
    cat <<'BADGING'
package: name='com.cedagova.fastreader' versionCode='$VERSION_CODE' versionName='$VERSION_NAME' compileSdkVersion='37'
minSdkVersion:'26'
targetSdkVersion:'37'$permission_lines
BADGING
    ;;
  "dump xmltree")
    cat <<'XMLTREE'
N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: package="com.cedagova.fastreader" (Raw: "com.cedagova.fastreader")
    E: application (line=20)
      A: http://schemas.android.com/apk/res/android:allowBackup(0x0101027a)=false
      A: http://schemas.android.com/apk/res/android:label(0x01010001)=@0x7f0e0000$MANIFEST_EXTRA_ATTRIBUTES
XMLTREE
    ;;
  *) echo "stub aapt2: unexpected \$*" >&2; exit 2 ;;
esac
STUB

  # Records every call one argument per line, and answers the five queries
  # release.sh makes: tag-exists check, published-version list, hosted-checks
  # lookup, create, and the asset lookup. A failing query prints what a real
  # auth or network failure prints and exits 1.
  cat > "$ROOT/bin/gh" <<STUB
#!/bin/bash
calls="$CALLS"
n=\$(ls "\$calls" | wc -l | tr -d ' ')
printf '%s\n' "\$@" > "\$calls/\$(printf '%03d' \$((n + 1)))"
case "\$1 \${2:-}" in
  "release view")
    for a in "\$@"; do
      # Only the post-publish asset lookup passes --json.
      [ "\$a" = "--json" ] && { printf 'https://example.invalid/$name.apk\n'; exit 0; }
    done
    case "$GH_VIEW" in
      exists) printf 'title:\tfastReader\ntag:\tv$VERSION_NAME\n'; exit 0 ;;
      notfound) echo "release not found" >&2; exit 1 ;;
      *) echo "HTTP 401: Bad credentials (https://api.github.com/graphql)" >&2; exit 1 ;;
    esac ;;
  "release list")
    case "$GH_LIST" in
      ok) printf '$PUBLISHED_HIGHEST\n'; exit 0 ;;
      empty) exit 0 ;;
      *) echo "error connecting to api.github.com" >&2; exit 1 ;;
    esac ;;
  "run list")
    [ "$GH_RUNS" = "fail" ] && { echo "error connecting to api.github.com" >&2; exit 1; }
    cat <<'RUNS'
$GH_RUNS
RUNS
    exit 0 ;;
  "release create") exit 0 ;;
esac
exit 1
STUB

  # No network: hand back the artifact release.sh just staged, as HTTP 200.
  cat > "$ROOT/bin/curl" <<STUB
#!/bin/bash
out=""
while [ \$# -gt 0 ]; do
  case "\$1" in -o) out="\$2"; shift ;; esac
  shift
done
cp "$ROOT/app/build/outputs/apk/release/fastReader-$VERSION_NAME.apk" "\$out"
printf '200'
STUB

  chmod +x "$ROOT/gradlew" "$ROOT/jdk/bin/java" "$ROOT/bin/gh" "$ROOT/bin/curl" \
    "$ROOT/sdk/build-tools/36.0.0/apksigner" "$ROOT/sdk/build-tools/36.0.0/aapt2"

  # A real (empty) repository so the clean-tree and `git rev-parse HEAD` guards
  # run for real instead of being skipped with --allow-dirty/--target.
  git init -q "$ROOT"
  git -C "$ROOT" -c user.name=test -c user.email=test@example.invalid \
    -c commit.gpgsign=false -c core.hooksPath="$ROOT/.git/hooks" \
    commit -q --allow-empty -m "sandbox"
  printf '*\n' > "$ROOT/.git/info/exclude"

  PATH="$ROOT/bin:$PATH" JAVA_HOME="$ROOT/jdk" ANDROID_HOME="$ROOT/sdk" \
    "$BASH32" "$ROOT/scripts/release.sh" --publish --gh-command "$ROOT/bin/gh" "$@" \
    > "$ROOT/stdout.log" 2> "$ROOT/stderr.log" && status=0 || status=$?

  CREATE_CALL=""
  for f in "$CALLS"/*; do
    [ -e "$f" ] || continue
    if [ "$(sed -n '1p' "$f")" = "release" ] && [ "$(sed -n '2p' "$f")" = "create" ]; then
      CREATE_CALL="$f"
    fi
  done
}

expect_success() {
  local name="$1"
  [ "$status" -eq 0 ] || {
    sed -n '1,80p' "$ROOT/stdout.log" >&2
    sed -n '1,40p' "$ROOT/stderr.log" >&2
    fail "$name: release.sh exited $status"
  }
  grep -q 'unbound variable' "$ROOT/stderr.log" && fail "$name: bash reported an unbound variable"
  [ -n "$CREATE_CALL" ] || fail "$name: release.sh never reached 'gh release create'"
}

# The gate must die with the named reason, and nothing may have been published.
expect_gate_failure() {
  local name="$1" reason="$2"
  [ "$status" -ne 0 ] || fail "$name: release.sh exited 0 although the gate should have failed"
  grep -q "$reason" "$ROOT/stderr.log" || {
    sed -n '1,40p' "$ROOT/stderr.log" >&2
    fail "$name: release.sh did not die with '$reason'"
  }
  [ -z "$CREATE_CALL" ] || fail "$name: release.sh reached 'gh release create' despite the failed gate"
  grep -q 'unbound variable' "$ROOT/stderr.log" && fail "$name: bash reported an unbound variable"
  printf 'ok   %s: gate died with "%s" before any publish\n' "$name" "$reason"
}

# A pre-publish guard must additionally stop before anything is built.
expect_guard_failure() {
  expect_gate_failure "$@"
  [ ! -e "$ROOT/app/build/outputs/apk/release/app-release.apk" ] \
    || fail "$1: release.sh built the APK although a pre-publish guard failed"
}

# The call file whose first two arguments are "$1 $2", or nothing.
find_call() {
  local f
  for f in "$CALLS"/*; do
    [ -e "$f" ] || continue
    [ "$(sed -n '1p' "$f")" = "$1" ] && [ "$(sed -n '2p' "$f")" = "$2" ] && { printf '%s' "$f"; return 0; }
  done
  return 0
}

has_arg() { grep -qxF -- "$1" "$CREATE_CALL"; }

printf 'bash under test: %s\n' "$("$BASH32" --version | head -1)"

# --- stable publish (the path that was broken) ------------------------------
run_release stable
expect_success "stable"
has_arg "v$VERSION_NAME"        || fail "stable: tag missing from gh release create"
has_arg "--notes"               || fail "stable: notes missing from gh release create"
has_arg "--prerelease"          && fail "stable: gh release create was given --prerelease"
has_arg "" && fail "stable: gh release create was given an empty argument"
grep -q "Released v$VERSION_NAME" "$ROOT/stdout.log" || fail "stable: script did not finish the publish"
printf 'ok   stable path reached gh release create: %s\n' "$(tr '\n' ' ' < "$CREATE_CALL")"
# The hosted-checks lookup names the full SHA the tag is created on.
HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD)"
RUNS_CALL="$(find_call run list)"
[ -n "$RUNS_CALL" ] || fail "stable: release.sh never read the hosted checks"
grep -qxF -- "$HEAD_SHA" "$RUNS_CALL" || fail "stable: the checks lookup did not name $HEAD_SHA"
has_arg "$HEAD_SHA" || fail "stable: gh release create did not target $HEAD_SHA"
printf 'ok   stable path read the checks of %s and tagged that commit\n' "$HEAD_SHA"

# --- pre-release publish (the path that already worked) ---------------------
run_release prerelease --prerelease --tag "v$VERSION_NAME-rc1"
expect_success "prerelease"
has_arg "v$VERSION_NAME-rc1"    || fail "prerelease: tag missing from gh release create"
has_arg "--prerelease"          || fail "prerelease: gh release create lost --prerelease"
printf 'ok   pre-release path still passes --prerelease: %s\n' "$(tr '\n' ' ' < "$CREATE_CALL")"

# --- REQ-411, the "fails it" half of the manifest gate ------------------------

PERMISSIONS="$PERMISSIONS_DEFAULT
android.permission.ACCESS_NETWORK_STATE"
run_release extra-permission
expect_gate_failure "extra-permission" "not exactly INTERNET plus the platform self-permission"
grep -q "ACCESS_NETWORK_STATE" "$ROOT/stderr.log" || fail "extra-permission: the offending line was not printed"

PERMISSIONS="com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
run_release missing-internet
expect_gate_failure "missing-internet" "not exactly INTERNET plus the platform self-permission"
PERMISSIONS="$PERMISSIONS_DEFAULT"

MANIFEST_EXTRA_ATTRIBUTES="
      A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f100000"
run_release network-security-config
expect_gate_failure "network-security-config" "carries a networkSecurityConfig"

MANIFEST_EXTRA_ATTRIBUTES="
      A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=true"
run_release cleartext
expect_gate_failure "cleartext" "sets usesCleartextTraffic"
MANIFEST_EXTRA_ATTRIBUTES=""

# --- #206, the pre-publish guards fail loud -----------------------------------

GH_LIST=empty
run_release first-release
expect_success "first-release"
grep -q "highest published stable version: none" "$ROOT/stdout.log" \
  || fail "first-release: an empty release list was not read as 'nothing published'"
printf 'ok   first-release: an empty (successful) release list still publishes\n'
GH_LIST=ok

GH_VIEW=exists
run_release tag-exists
expect_guard_failure "tag-exists" "release v$VERSION_NAME already exists"
GH_VIEW=fail
run_release tag-query-fails
expect_guard_failure "tag-query-fails" "could not check whether release v$VERSION_NAME exists"
grep -q "Bad credentials" "$ROOT/stderr.log" || fail "tag-query-fails: gh's own error was not printed"
GH_VIEW=notfound

GH_LIST=fail
run_release list-query-fails
expect_guard_failure "list-query-fails" "could not list the published releases"
GH_LIST=ok

run_release target-not-a-commit --target no-such-ref
expect_guard_failure "target-not-a-commit" "target no-such-ref is not a commit in this repository"

GH_RUNS=fail
run_release checks-query-fails
expect_guard_failure "checks-query-fails" "could not read the hosted checks"
GH_RUNS=""
run_release checks-missing
expect_guard_failure "checks-missing" "no checks run found"
GH_RUNS="in_progress "
run_release checks-running
expect_guard_failure "checks-running" "is still in_progress"
GH_RUNS="completed failure"
run_release checks-failed
expect_guard_failure "checks-failed" "concluded 'failure'"
GH_RUNS="completed success
completed failure"
run_release checks-mixed
expect_guard_failure "checks-mixed" "concluded 'failure'"
GH_RUNS="completed cancelled"
run_release checks-only-cancelled
expect_guard_failure "checks-only-cancelled" "no successful checks run"
GH_RUNS="completed cancelled
completed success"
run_release checks-superseded
expect_success "checks-superseded"
printf 'ok   checks-superseded: a cancelled run beside a passing one still publishes\n'
GH_RUNS="completed success"

printf '\nPASS: both publish paths reach gh release create under bash 3.2, the manifest gate fails its four negatives, and every pre-publish guard fails loud.\n'
