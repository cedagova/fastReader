# Releasing fastReader

fastReader ships as a signed APK attached to a GitHub Release. Anyone with the
link can download and sideload it; there is no store, and reading needs no
account. This page is the whole procedure.

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

3. Write the release notes as `docs/release-notes/v<versionName>.md`, with the
   privacy statement's marked block pasted into it. `PrivacyStatementTest`
   fails until that file exists and its copy of the block matches the string
   the app shows, so a release cannot go out with notes that promise something
   different from the build.

   **If the release changes what leaves the device, the statement is part of
   the release, not a follow-up.** Four copies are held equal by that test —
   the `settings_privacy` string, the block in
   [docs/privacy-statement.md](privacy-statement.md), the README's copy and
   this version's notes — and the Spanish twin in `values-es/strings.xml` is a
   hand edit in the same commit, because lint fails a *missing* translation and
   not a stale one. The same test asserts that every retired promise is
   **absent**: "no internet permission" went with v1.6.0, and v1.7.0 retired
   "your books, your reading positions, your settings and any crash report stay
   on this device and are never sent" because the account library now sends
   `library_item` changes for the books an account already holds. The rule is
   AD-27: the statement may claim *less* than the shipped code does, never
   more. The permission and cleartext proofs in the table below did not change
   for that — the account library rides the client `:reader-auth` already
   owns — so an unchanged gate here is not evidence that the promise is still
   accurate. Read the per-sentence table in
   [docs/privacy-statement.md](privacy-statement.md) against the diff.

4. Publish, from the exact commit that is on `main`:

   ```bash
   ./scripts/release.sh --publish --notes-file docs/release-notes/v<versionName>.md
   ```

   Without `--notes-file` the script falls back to a one-line default, which has
   no privacy statement in it (REQ-107).

`scripts/release.sh` is the release command. Nothing else publishes, and no CI
job does it for you. Every run builds `:app:assembleRelease` and then proves,
on the artifact itself:

| Check | Why |
| --- | --- |
| v2/v3 APK signature present | Android 8.0+ verifies these schemes |
| Signer certificate SHA-256 equals the pinned value | The same key must sign every release forever, or in-place updates break |
| The `uses-permission` lines are exactly `android.permission.INTERNET` and `com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — any other, or either missing, fails | REQ-411 — the internet permission serves the Reader account (#100) and, since #112, the account library that rides the same client; nothing else asks for anything, and the second line is the self-permission Android adds for its own broadcast plumbing |
| The release manifest has no `networkSecurityConfig` and no `usesCleartextTraffic` | REQ-411 — the debug-only loopback allowance never ships; every connection is TLS |
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
only the pre-release one passes `--prerelease`, and — since #100 — that the
manifest gate fails a badging with a third permission, one missing the internet
permission, and a manifest carrying a `networkSecurityConfig` or a
`usesCleartextTraffic` attribute (REQ-411's "fails it" half, proven without
building a rogue APK).

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
