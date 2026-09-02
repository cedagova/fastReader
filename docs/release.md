# Releasing fastReader

fastReader ships as a signed APK attached to a GitHub Release. Anyone with the
link can download and sideload it; there is no store, no account, and no
network call from the app itself. This page is the whole procedure.

## Prerequisites

- JDK 21 exported for the shell: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Android SDK with build-tools (`apksigner`, `aapt2`) — `~/Library/Android/sdk`
  by default, or `ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- `local.properties` with `sdk.dir` (git-ignored):
  `echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties`
- The machine-local signing material described under
  [Signing material custody](#signing-material-custody).
- `cedagova` GitHub access through `bin/gh-personal` (publishing only).

## Cutting a release

1. Bump `version.properties` in the same commit as the release-worthy change:

   ```properties
   versionCode=2
   versionName=1.0.1
   ```

2. Build and verify without publishing anything:

   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   ./scripts/release.sh
   ```

3. Publish, from the exact commit that is on `main`:

   ```bash
   ./scripts/release.sh --publish
   ```

`scripts/release.sh` is the release command. Nothing else publishes, and no CI
job does it for you. Every run builds `:app:assembleRelease` and then proves,
on the artifact itself:

| Check | Why |
| --- | --- |
| v2/v3 APK signature present | Android 8.0+ verifies these schemes |
| Signer certificate SHA-256 equals the pinned value | The same key must sign every release forever, or in-place updates break |
| No `android.permission.INTERNET` | REQ-050 — reading data never leaves the device |
| `minSdkVersion` is 26 | REQ-040 — installs on Android 8.0+ |
| `versionCode`/`versionName` match `version.properties` | The tag, the file, and the artifact cannot drift |

Publishing adds four more gates: the worktree must be clean and the tag is
created on that exact `HEAD` commit, the tag must not already exist, a stable
release's `versionName` must be the highest published one, and the uploaded
asset is re-downloaded with plain `curl` — no token, no cookies — and compared
byte-for-byte against the artifact that was just verified. That last step is
the proof that a friend with only the link can install the build.

Useful variants:

```bash
./scripts/release.sh --publish --prerelease --tag v1.1.0-rc1   # pre-release
./scripts/release.sh --publish --notes-file notes.md           # custom notes
./scripts/release.sh --publish --target <sha>                  # tag another commit
./scripts/release.sh --publish --allow-dirty                   # skip the clean-tree gate
```

## Testing the release script itself

The publish path cannot be rehearsed against real GitHub, so it has its own
shell-level test:

```bash
./scripts/test-release-publish.sh
```

It runs the real `scripts/release.sh --publish` under `/bin/bash` inside a
throwaway sandbox with stubbed `gradlew`, `apksigner`, `aapt2`, `java`, `gh`,
and `curl`. Nothing is built, signed, published, or downloaded; it asserts that
both the stable and the `--prerelease` path reach `gh release create` and that
only the pre-release one passes `--prerelease`.

Run it after any edit to `scripts/release.sh`. It exists because macOS ships
bash 3.2, where `set -u` rejects the expansion of an *empty* array: that broke
every stable publish while `--prerelease`, whose array was populated, kept
working (#30).

## Versioning rule

`version.properties` at the repository root is the single source of truth;
`app/build.gradle.kts` and `scripts/release.sh` both read it.

- **`versionCode`** is an integer that **must strictly increase** for every
  published release. Android enforces this at install time: an in-place update
  whose `versionCode` is not higher is rejected with
  `INSTALL_FAILED_VERSION_DOWNGRADE`. Never reuse or lower it.
- **`versionName`** is the human label (`1.0.0`) and defines the release tag
  (`v1.0.0`). Use ordinary semantic versioning.

Bump both together, in the commit that the release is cut from.

## Signing material custody

Android only allows an in-place update when the new APK carries the **same**
signing certificate as the installed one. Losing the fastReader release key
means nobody can ever update without uninstalling and losing their library.
Treat it as unrecoverable state.

- **Keystore:** `~/.config/fastreader/signing/fastreader-release.jks`
  (PKCS12, 4096-bit RSA, alias `fastreader`, valid until 2056).
- **Passwords:** `~/.config/fastreader/signing/keystore.properties`
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). `storeFile` may be
  absolute or relative to that file's own directory, so a copied directory is
  self-contained.
- **Verified backup:** `~/.config/fastreader/signing-backup/` holds a
  byte-identical copy of both files (verified by `shasum -a 256`, and the copy
  was opened with `keytool -list` using the recorded password).
- **Certificate SHA-256 (public):**
  `d4:76:be:8e:7e:fb:ee:3f:e8:1d:ca:8d:d8:9f:13:c3:43:4f:26:68:9a:9a:69:79:c0:1d:a5:46:29:e6:48:5d`
  — pinned as `EXPECTED_CERT_SHA256` in `scripts/release.sh`.

Neither directory is inside a git worktree; `*.jks` and `keystore.properties`
are also git-ignored. Never commit, print, or paste these values.

To build from the backup, or from another machine:

```bash
FASTREADER_KEYSTORE_PROPERTIES=~/.config/fastreader/signing-backup/keystore.properties \
  ./scripts/release.sh
```

`-Pfastreader.keystoreProperties=<path>` does the same thing. With no signing
material present, debug builds and tests still work, and `packageRelease` fails
loudly instead of quietly producing an unsigned APK.

### Owner action still pending

> **The passwords are not yet in a password manager.** An agent cannot do this.
>
> Store a password-manager entry titled **fastReader release signing** with:
>
> - the keystore path `~/.config/fastreader/signing/fastreader-release.jks`,
> - the backup path `~/.config/fastreader/signing-backup/fastreader-release.jks`,
> - the key alias `fastreader`, and
> - the store and key passwords, copied from
>   `~/.config/fastreader/signing/keystore.properties`.
>
> Until that entry exists, the only copies of the passwords are the two local
> `keystore.properties` files on this Mac. Do this before sharing the first
> release link.

An off-machine copy of the keystore (encrypted external drive or the password
manager's file attachment) is worth having for the same reason; the local
backup directory does not survive losing the machine.

## Rollback

There is no server and no downgrade path.

- **Android does not support in-place downgrades.** Installing an older
  `versionCode` over a newer one fails; the only way back is uninstall and
  reinstall, which deletes the library, positions, and settings.
- **Migrations are forward-only** after the first shared release (plan AD-3).
  A schema change must migrate existing data, never wipe it.
- **To roll back a bad release:** delete or mark the GitHub Release as a
  pre-release so the link stops handing it out, fix the defect, and publish a
  **higher** version. Never re-publish a lower one, and never reuse a tag —
  `scripts/release.sh --publish` refuses both.
