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

  cat > "$ROOT/sdk/build-tools/36.0.0/aapt2" <<STUB
#!/bin/bash
cat <<'BADGING'
package: name='com.cedagova.fastreader' versionCode='$VERSION_CODE' versionName='$VERSION_NAME' compileSdkVersion='36'
minSdkVersion:'26'
targetSdkVersion:'36'
BADGING
STUB

  # Records every call one argument per line, and answers the four queries
  # release.sh makes: tag-exists check, published-version list, create, and the
  # asset lookup.
  cat > "$ROOT/bin/gh" <<STUB
#!/bin/bash
calls="$CALLS"
n=\$(ls "\$calls" | wc -l | tr -d ' ')
printf '%s\n' "\$@" > "\$calls/\$(printf '%03d' \$((n + 1)))"
case "\$1 \${2:-}" in
  "release view")
    for a in "\$@"; do
      # Only the post-publish asset lookup passes --json; the pre-publish
      # existence check must fail so the tag reads as unused.
      [ "\$a" = "--json" ] && { printf 'https://example.invalid/$name.apk\n'; exit 0; }
    done
    exit 1 ;;
  "release list") printf '$PUBLISHED_HIGHEST\n'; exit 0 ;;
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

# --- pre-release publish (the path that already worked) ---------------------
run_release prerelease --prerelease --tag "v$VERSION_NAME-rc1"
expect_success "prerelease"
has_arg "v$VERSION_NAME-rc1"    || fail "prerelease: tag missing from gh release create"
has_arg "--prerelease"          || fail "prerelease: gh release create lost --prerelease"
printf 'ok   pre-release path still passes --prerelease: %s\n' "$(tr '\n' ' ' < "$CREATE_CALL")"

printf '\nPASS: both publish paths reach gh release create under bash 3.2.\n'
