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
#   * it declares no android.permission.INTERNET (REQ-050 release proof);
#   * it declares minSdkVersion 26 (REQ-040 "Android 8.0+");
#   * its versionCode/versionName match version.properties.
#
# Publishing additionally refuses to reuse an existing tag, requires the new
# versionName to be the highest published one, and re-downloads the uploaded
# asset without any credential to prove the link works for someone who is not
# logged in to GitHub.
#
# See docs/release.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The one fastReader release signing certificate. Pinned here so a release built
# with any other key fails before it can be published: Android only allows an
# in-place update when the new APK carries this exact certificate.
EXPECTED_CERT_SHA256="d476be8e7efbee3fe81dca8dd89f13c3434f26689a9a6979c01da54629e6485d"

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
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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
  if [ "$ALLOW_DIRTY" -eq 0 ] && [ -n "$(git status --porcelain)" ]; then
    die "worktree has uncommitted changes; commit them so the tag names the built code (or pass --allow-dirty)"
  fi
  [ -n "$TARGET" ] || TARGET="$(git rev-parse HEAD)"
  printf 'target commit: %s\n' "$TARGET"
  if "$GH_COMMAND" release view "$TAG" --repo "$GITHUB_REPO" >/dev/null 2>&1; then
    die "release $TAG already exists; bump version.properties instead of reusing a tag"
  fi
  # Releases are forward-only (AD-3): the new versionName must sort strictly
  # above every published stable tag. Pre-releases are exempt from the ordering
  # check but still may not reuse a tag.
  if [ "$PRERELEASE" -eq 0 ]; then
    HIGHEST="$("$GH_COMMAND" release list --repo "$GITHUB_REPO" --exclude-pre-releases --limit 100 \
      --json tagName --jq '.[].tagName' 2>/dev/null | sed 's/^v//' | sort -V | tail -1 || true)"
    if [ -n "$HIGHEST" ]; then
      TOP="$(printf '%s\n%s\n' "$HIGHEST" "$VERSION_NAME" | sort -V | tail -1)"
      [ "$TOP" = "$VERSION_NAME" ] && [ "$HIGHEST" != "$VERSION_NAME" ] \
        || die "versionName $VERSION_NAME does not exceed the published $HIGHEST; downgrades are unsupported"
    fi
    printf 'highest published stable version: %s\n' "${HIGHEST:-none}"
  fi
fi

# --- build -----------------------------------------------------------------
step "Build signed release APK"
./gradlew --console=plain :app:assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "expected $APK to exist after assembleRelease"

# --- verify signature ------------------------------------------------------
step "Verify signature"
SIGNER_OUTPUT="$("$APKSIGNER" verify --print-certs --verbose "$APK")"
printf '%s\n' "$SIGNER_OUTPUT" | grep -E 'Verified using v[23] scheme|certificate SHA-256|key size'
printf '%s\n' "$SIGNER_OUTPUT" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  || die "APK is not v2-signed"
ACTUAL_CERT="$(printf '%s\n' "$SIGNER_OUTPUT" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
[ "$ACTUAL_CERT" = "$EXPECTED_CERT_SHA256" ] \
  || die "signing certificate $ACTUAL_CERT is not the pinned fastReader release key; in-place updates would break"

# --- verify the artifact's own manifest ------------------------------------
step "Verify release manifest"
BADGING="$("$AAPT2" dump badging "$APK")"
printf '%s\n' "$BADGING" | grep -E "^package:|^minSdkVersion|^targetSdkVersion|^uses-permission"
if printf '%s\n' "$BADGING" | grep -q "uses-permission: name='android.permission.INTERNET'"; then
  die "release APK requests android.permission.INTERNET; reading data must never leave the device (REQ-050)"
fi
printf '%s\n' "$BADGING" | grep -q "^minSdkVersion:'26'" \
  || die "release APK does not declare minSdkVersion 26"
printf '%s\n' "$BADGING" | grep -q "^package: name='com.cedagova.fastreader' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" \
  || die "APK version does not match version.properties ($VERSION_CODE / $VERSION_NAME)"
printf 'no INTERNET permission, minSdk 26, version matches version.properties\n'

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
"$GH_COMMAND" release create "$TAG" "$STAGED" \
  --repo "$GITHUB_REPO" --title "fastReader $VERSION_NAME" --target "$TARGET" \
  "${NOTES_ARGS[@]}" "${PRE_ARGS[@]}"

# --- prove the link works without repository authentication ----------------
step "Verify unauthenticated download"
ASSET_URL="$("$GH_COMMAND" release view "$TAG" --repo "$GITHUB_REPO" \
  --json assets --jq ".assets[] | select(.name == \"$APK_NAME\") | .url")"
[ -n "$ASSET_URL" ] || die "uploaded asset $APK_NAME not found on $TAG"
printf 'asset URL: %s\n' "$ASSET_URL"
DOWNLOAD="$(mktemp -t fastreader-release)"
# No token, no cookies, no netrc: exactly what a friend with the link gets.
HTTP_CODE="$(curl -sSL --fail-with-body -o "$DOWNLOAD" -w '%{http_code}' "$ASSET_URL")"
printf 'HTTP %s\n' "$HTTP_CODE"
[ "$HTTP_CODE" = "200" ] || die "unauthenticated download returned HTTP $HTTP_CODE"
cmp -s "$DOWNLOAD" "$STAGED" || die "downloaded asset differs from the verified artifact"
"$APKSIGNER" verify --print-certs "$DOWNLOAD" | grep -q "$EXPECTED_CERT_SHA256" \
  || die "downloaded asset is not signed by the pinned release key"
rm -f "$DOWNLOAD"
printf '\nReleased %s: signed, verified, and downloadable without authentication.\n' "$TAG"
