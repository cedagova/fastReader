#!/usr/bin/env bash
#
# fastReader release pipeline — the one repeatable release command.
#
#   scripts/release.sh                 build + verify only (no publish)
#   scripts/release.sh --publish       build, verify, publish the GitHub Release
#   scripts/release.sh --publish --prerelease --tag v1.0.0-rc1
#
# Publishing tags the current HEAD (override with --target) and refuses to run
# from a dirty worktree, so the tag always names the code that was built.
#
# Every run builds the signed release APK from the current worktree and then
# proves, on the artifact itself:
#   * it is signed by the one fastReader release key (pinned certificate SHA-256);
#   * its uses-permission lines are exactly android.permission.INTERNET (the
#     Reader account, #100) and the platform's own
#     com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION — any
#     other line, or either missing, fails (REQ-411 release proof);
#   * its manifest carries no networkSecurityConfig and no usesCleartextTraffic,
#     so every connection is TLS (REQ-411);
#   * it declares minSdkVersion 26 (REQ-040 "Android 8.0+");
#   * its versionCode/versionName match version.properties.
#
# Publishing additionally refuses to reuse an existing tag, requires the new
# versionName to be the highest published one, refuses a target commit whose
# hosted checks (.github/workflows/checks.yml: unit tests, goldens, lint, the
# R8 release minification and the instrumented-test compile) have not passed,
# and re-downloads the uploaded asset without any credential to prove the link
# works for someone who is not logged in to GitHub. Every GitHub query those
# guards make fails the release when the query itself fails (auth, network):
# an unanswered question is never read as "no such tag" or "nothing published".
#
# See docs/release.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The one fastReader release signing certificate. Pinned here so a release built
# with any other key fails before it can be published: Android only allows an
# in-place update when the new APK carries this exact certificate.
EXPECTED_CERT_SHA256="d476be8e7efbee3fe81dca8dd89f13c3434f26689a9a6979c01da54629e6485d"

# The exact permission set a release may request, sorted as `sort` orders it.
# INTERNET serves the Reader account (#100) and nothing else; the second line
# is the self-permission Android adds for its own broadcast plumbing. Adding a
# permission means editing this list on purpose, in the same change.
EXPECTED_PERMISSIONS="android.permission.INTERNET
com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

GH_COMMAND="${GH_COMMAND:-$REPO_ROOT/bin/gh-personal}"
GITHUB_REPO="cedagova/fastReader"
PUBLISH=0
PRERELEASE=0
TAG=""
NOTES_FILE=""
TARGET=""
ALLOW_DIRTY=0

die() { printf 'release: %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1 ;;
    --prerelease) PRERELEASE=1 ;;
    --tag) TAG="${2:?--tag needs a value}"; shift ;;
    --notes-file) NOTES_FILE="${2:?--notes-file needs a value}"; shift ;;
    --target) TARGET="${2:?--target needs a value}"; shift ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --gh-command) GH_COMMAND="${2:?--gh-command needs a value}"; shift ;;
    -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# --- toolchain -------------------------------------------------------------
step "Toolchain"
[ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME is unset; Gradle needs JDK 21 (see docs/release.md)"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS="$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BUILD_TOOLS" ] || die "no Android build-tools found under $SDK_DIR/build-tools"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
[ -x "$APKSIGNER" ] || die "apksigner not executable at $APKSIGNER"
[ -x "$AAPT2" ] || die "aapt2 not executable at $AAPT2"
printf 'JDK          %s\nbuild-tools  %s\n' "$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)" "$BUILD_TOOLS"

# --- version ---------------------------------------------------------------
step "Version"
read_version_property() {
  local key="$1" value
  value="$(sed -n "s/^${key}=//p" version.properties | tail -1 | tr -d '[:space:]')"
  [ -n "$value" ] || die "version.properties has no $key"
  printf '%s' "$value"
}
VERSION_CODE="$(read_version_property versionCode)"
VERSION_NAME="$(read_version_property versionName)"
case "$VERSION_CODE" in ''|*[!0-9]*) die "versionCode '$VERSION_CODE' is not an integer" ;; esac
[ -z "$TAG" ] && TAG="v$VERSION_NAME"
APK_NAME="fastReader-$VERSION_NAME.apk"
printf 'versionCode  %s\nversionName  %s\ntag          %s\n' "$VERSION_CODE" "$VERSION_NAME" "$TAG"

# --- pre-publish guards ----------------------------------------------------
if [ "$PUBLISH" -eq 1 ]; then
  step "Pre-publish guards"
  # The tag must name the exact commit the APK was built from, so the tree has
  # to be clean and the target has to be this HEAD unless told otherwise.
  if [ "$ALLOW_DIRTY" -eq 0 ]; then
    WORKTREE_STATUS="$(git status --porcelain)" || die "could not read the worktree status (git status failed)"
    [ -z "$WORKTREE_STATUS" ] \
      || die "worktree has uncommitted changes; commit them so the tag names the built code (or pass --allow-dirty)"
  fi
  [ -n "$TARGET" ] || TARGET="HEAD"
  # A full SHA: the CI lookup below matches on it, and the tag must name exactly
  # the commit whose checks were read.
  TARGET="$(git rev-parse --verify --quiet "$TARGET^{commit}")" \
    || die "target $TARGET is not a commit in this repository"
  printf 'target commit: %s\n' "$TARGET"

  # Existing-tag check. `gh release view` exits non-zero both for "no such
  # release" and for a failed query (auth, network, rate limit), so only its
  # own not-found answer counts as "tag unused"; anything else stops here.
  if VIEW_ERROR="$("$GH_COMMAND" release view "$TAG" --repo "$GITHUB_REPO" 2>&1 >/dev/null)"; then
    die "release $TAG already exists; bump version.properties instead of reusing a tag"
  fi
  case "$VIEW_ERROR" in
    *"release not found"*) ;;
    *) die "could not check whether release $TAG exists (gh release view failed): ${VIEW_ERROR:-<no output>}" ;;
  esac

  # Releases are forward-only (AD-3): the new versionName must sort strictly
  # above every published stable tag. Pre-releases are exempt from the ordering
  # check but still may not reuse a tag. A failed query stops the release; an
  # empty answer from a successful one means nothing is published yet.
  if [ "$PRERELEASE" -eq 0 ]; then
    PUBLISHED_TAGS="$("$GH_COMMAND" release list --repo "$GITHUB_REPO" --exclude-pre-releases --limit 100 \
      --json tagName --jq '.[].tagName')" \
      || die "could not list the published releases (gh release list failed); the forward-only version guard cannot run"
    HIGHEST="$(printf '%s\n' "$PUBLISHED_TAGS" | sed 's/^v//' | sed '/^$/d' | sort -V | tail -1)"
    if [ -n "$HIGHEST" ]; then
      TOP="$(printf '%s\n%s\n' "$HIGHEST" "$VERSION_NAME" | sort -V | tail -1)"
      [ "$TOP" = "$VERSION_NAME" ] && [ "$HIGHEST" != "$VERSION_NAME" ] \
        || die "versionName $VERSION_NAME does not exceed the published $HIGHEST; downgrades are unsupported"
    fi
    printf 'highest published stable version: %s\n' "${HIGHEST:-none}"
  fi

  # Gates: publish only a commit the hosted checks passed on. Every run of
  # checks.yml for the target commit is read; at least one must have succeeded
  # and none may be unfinished or failed (a run superseded by a newer push is
  # cancelled, which is neither). The check covers the tests, goldens, lint, the
  # R8 release minification and the instrumented-test compile, none of which
  # this script repeats.
  CHECK_RUNS="$("$GH_COMMAND" run list --repo "$GITHUB_REPO" --workflow checks.yml --commit "$TARGET" \
    --limit 50 --json status,conclusion --jq '.[] | "\(.status) \(.conclusion)"')" \
    || die "could not read the hosted checks for $TARGET (gh run list failed); refusing to publish ungated code"
  [ -n "$CHECK_RUNS" ] \
    || die "no checks run found for $TARGET; push it and let .github/workflows/checks.yml pass before publishing"
  PASSED=0
  while read -r RUN_STATUS RUN_CONCLUSION; do
    [ "$RUN_STATUS" = "completed" ] \
      || die "the checks run for $TARGET is still $RUN_STATUS; wait for it to pass before publishing"
    case "$RUN_CONCLUSION" in
      success) PASSED=1 ;;
      cancelled|skipped) ;;
      *) die "the checks run for $TARGET concluded '$RUN_CONCLUSION'; its gates have not passed" ;;
    esac
  done <<< "$CHECK_RUNS"
  [ "$PASSED" -eq 1 ] || die "no successful checks run for $TARGET; its gates have not passed"
  printf 'hosted checks passed on %s\n' "$TARGET"
fi

# --- build -----------------------------------------------------------------
step "Build signed release APK"
./gradlew --console=plain :app:assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "expected $APK to exist after assembleRelease"

# --- verify signature ------------------------------------------------------
step "Verify signature"
SIGNER_OUTPUT="$("$APKSIGNER" verify --print-certs --verbose "$APK")"
printf '%s\n' "$SIGNER_OUTPUT" | grep -E 'Verified using v[23] scheme|certificate SHA-256|key size' || true
printf '%s\n' "$SIGNER_OUTPUT" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  || die "APK is not v2-signed"
ACTUAL_CERT="$(printf '%s\n' "$SIGNER_OUTPUT" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
[ "$ACTUAL_CERT" = "$EXPECTED_CERT_SHA256" ] \
  || die "signing certificate $ACTUAL_CERT is not the pinned fastReader release key; in-place updates would break"

# --- verify the artifact's own manifest ------------------------------------
step "Verify release manifest"
BADGING="$("$AAPT2" dump badging "$APK")"
printf '%s\n' "$BADGING" | grep -E "^package:|^minSdkVersion|^targetSdkVersion|^uses-permission" || true
# The permission set is compared whole, not searched: an extra line fails as
# surely as a missing one (REQ-411).
ACTUAL_PERMISSIONS="$(printf '%s\n' "$BADGING" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort)"
EXPECTED_SORTED="$(printf '%s\n' "$EXPECTED_PERMISSIONS" | sort)"
if [ "$ACTUAL_PERMISSIONS" != "$EXPECTED_SORTED" ]; then
  printf 'expected permissions:\n%s\nactual permissions:\n%s\n' "$EXPECTED_SORTED" "${ACTUAL_PERMISSIONS:-<none>}" >&2
  die "release APK's permissions are not exactly INTERNET plus the platform self-permission (REQ-411)"
fi
# No cleartext: the debug-only loopback allowance must not have shipped, and
# nothing may have set the attribute that would allow cleartext everywhere.
MANIFEST_TREE="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")"
if printf '%s\n' "$MANIFEST_TREE" | grep -q "networkSecurityConfig"; then
  die "release manifest carries a networkSecurityConfig; the loopback allowance is debug-only (REQ-411)"
fi
if printf '%s\n' "$MANIFEST_TREE" | grep -q "usesCleartextTraffic"; then
  die "release manifest sets usesCleartextTraffic; every connection must be TLS (REQ-411)"
fi
printf '%s\n' "$BADGING" | grep -q "^minSdkVersion:'26'" \
  || die "release APK does not declare minSdkVersion 26"
printf '%s\n' "$BADGING" | grep -q "^package: name='com.cedagova.fastreader' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" \
  || die "APK version does not match version.properties ($VERSION_CODE / $VERSION_NAME)"
printf 'permissions are exactly INTERNET + the platform self-permission, no cleartext, minSdk 26, version matches version.properties\n'

STAGED="app/build/outputs/apk/release/$APK_NAME"
cp -f "$APK" "$STAGED"
printf '\nverified artifact: %s (%s bytes, sha256 %s)\n' \
  "$STAGED" "$(wc -c < "$STAGED" | tr -d ' ')" "$(shasum -a 256 "$STAGED" | cut -d' ' -f1)"

if [ "$PUBLISH" -eq 0 ]; then
  printf '\nBuild and verification complete. Re-run with --publish to create %s.\n' "$TAG"
  exit 0
fi

# --- publish ---------------------------------------------------------------
step "Publish GitHub Release $TAG"
NOTES_ARGS=()
if [ -n "$NOTES_FILE" ]; then NOTES_ARGS=(--notes-file "$NOTES_FILE")
else NOTES_ARGS=(--notes "fastReader $VERSION_NAME (versionCode $VERSION_CODE)."); fi
PRE_ARGS=()
[ "$PRERELEASE" -eq 1 ] && PRE_ARGS=(--prerelease)
# `${PRE_ARGS[@]+...}` because macOS ships bash 3.2, where `set -u` treats an
# empty array's expansion as an unbound variable. Without the guard every
# stable (non-`--prerelease`) publish aborts here. NOTES_ARGS is assigned a
# non-empty value on both branches above, so it needs no guard.
"$GH_COMMAND" release create "$TAG" "$STAGED" \
  --repo "$GITHUB_REPO" --title "fastReader $VERSION_NAME" --target "$TARGET" \
  "${NOTES_ARGS[@]}" "${PRE_ARGS[@]+"${PRE_ARGS[@]}"}"

# --- prove the link works without repository authentication ----------------
step "Verify unauthenticated download"
ASSET_URL="$("$GH_COMMAND" release view "$TAG" --repo "$GITHUB_REPO" \
  --json assets --jq ".assets[] | select(.name == \"$APK_NAME\") | .url")"
[ -n "$ASSET_URL" ] || die "uploaded asset $APK_NAME not found on $TAG"
printf 'asset URL: %s\n' "$ASSET_URL"
DOWNLOAD="$(mktemp -t fastreader-release)"
# No token, no cookies, no netrc: exactly what a friend with the link gets.
# `|| true` keeps a transport failure from dying inside the assignment with no
# explanation; the empty code then fails loudly on the next line.
HTTP_CODE="$(curl -sSL -o "$DOWNLOAD" -w '%{http_code}' "$ASSET_URL" || true)"
printf 'HTTP %s\n' "${HTTP_CODE:-<curl failed>}"
[ "$HTTP_CODE" = "200" ] || die "unauthenticated download returned HTTP ${HTTP_CODE:-<curl failed>}"
cmp -s "$DOWNLOAD" "$STAGED" || die "downloaded asset differs from the verified artifact"
"$APKSIGNER" verify --print-certs "$DOWNLOAD" | grep -q "$EXPECTED_CERT_SHA256" \
  || die "downloaded asset is not signed by the pinned release key"
rm -f "$DOWNLOAD"
printf '\nReleased %s: signed, verified, and downloadable without authentication.\n' "$TAG"
