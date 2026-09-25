# reader-auth

The reusable Android library for Reader authentication. It is developed in
this repository beside FastReader as a proving ground (#92, audit finding
A83-F007) and is built to be lifted into the real Reader client unchanged:

- it depends on nothing under `:app` and on no `com.cedagova.fastreader`
  symbol, and nothing in its name, path, namespace or resources says
  `fastreader`;
- its toolchain comes from the root `gradle/libs.versions.toml`, nothing is
  pinned inline.

[CONTRACT.md](CONTRACT.md) is the client contract — sign-in methods in
preference order, bootstrap, the Keystore-encrypted session store, the
single-flight refresh policy, the reader-api 401/403/429/502 policy, sign-out
semantics, and what a host must provide — and this module implements exactly
it (#93, audit finding A83-F008). `ReaderAuthClient` is the entry point:

```kotlin
val client = ReaderAuthClient.create(context, ReaderAuthConfig(supabaseUrl, publishableKey, readerApiBaseUrl))
client.awaitReady()                       // reads the stored session
client.requestEmailCode(email, createUser = true)
client.verifyEmailCode(email, code)       // stores the session
client.capabilities()                     // refreshes first when inside the margin
client.signOut()
```

[contracts/](contracts/PINNED.md) holds the one pinned reader-api contract
document both libraries are gated against, with its identity and its update
procedure (#208). `ReaderAuthContractTest` checks every shape this module sends
or reads — `PreAuthDocument`, `ReaderProfileUpdate`, the error body, the three
routes — against it, through the checker `:reader-library`'s contract test
shares (`src/contractTest/`).

Every failure is one branch of the sealed `ReaderAuthException`. The
contract's constants live in `ReaderAuthPolicy`, and the unit tests under
`src/test/` (fake cipher, fake clock, Ktor mock engine; no network, no device)
pin each rule; the real Keystore path and the stage flow are proven on an
emulator from FastReader, the library's host (`docs/evidence/100/`).

## Substituting it in a host's tests

A host holds `ReaderAuthOperations` — the interface `ReaderAuthClient`
implements — wherever it wants to swap the module out, and takes the module's
test fixtures (`src/testFixtures/`, package `com.cedagova.reader.auth.testing`,
#199) instead of writing its own:

```kotlin
testImplementation(testFixtures(project(":reader-auth")))
```

- `FakeReaderAuthOperations`: a scripted double — records each call, throws a
  scripted `ReaderAuthException`, parks on a gate, flips its session state
  the way the client does.
- `ReaderAuthHarness`: the real client over `FakeServers`, the one mock
  identity-provider and reader-api server, with an in-memory session store —
  for code that must be proven against the real call policy. `FakeServers`,
  `FakeClock`, `RecordingWaiter`, `TestSession` and the document builders
  (`preAuthJson`, `sessionJson`, `apiError`, …) come with it, and
  `:reader-library`'s fixtures build on them.

Kotlin in test fixtures needs `android.experimental.enableTestFixturesKotlinSupport=true`
in the host's `gradle.properties`.

Session storage is **not** a host seam. `SessionStore` and `StoredSession`
are internal: the storage rules (Keystore encryption, the no-backup
directory, clearing on sign-out and on a rejected token) are this module's to
keep, a host store would be one more place a token could be written in the
clear, and no public type carries a token. The production API has no
test-only entry point; the fixtures reach the module's internal wiring the
way its own tests do.

A host takes this module as a source copy at a tag `reader-auth/v<version>`
(#207): [docs/library-consumption.md](../docs/library-consumption.md) lists
what to copy and how. The version is `version` in `build.gradle.kts`, and
[CHANGELOG.md](CHANGELOG.md) records what changed between two versions.

## What the library declares for its hosts

`src/main/AndroidManifest.xml` declares `android.permission.INTERNET`. It is
the only permission the library asks for, and manifest merging delivers it to
every host, so a host does not declare it again. `ReaderAuth.REQUIRED_PERMISSION`
names it so a host can read the merge result back.

## What every host must declare for itself

A library manifest cannot impose these; each host — FastReader's `:app` now
(#100), the real Reader client later — owns them, and a host that omits one
has a host defect, not a library defect. FastReader guards each with a unit
test (`ReaderAccountManifestTest` and `ReaderAccountConfigTest` under
`app/src/test/java/com/cedagova/fastreader/account/`), which is the pattern
to copy.

1. **Full exclusion from backup and device-to-device transfer.** The module
   will store tokens, and no token may leave the device in a cloud backup or
   a setup-wizard transfer. The `<application>` element sets
   `android:allowBackup="false"`,
   `android:dataExtractionRules="@xml/data_extraction_rules"` and
   `android:fullBackupContent="@xml/backup_rules"`. Both rule files exclude
   **all nine domains** — `root`, `file`, `database`, `sharedpref`,
   `external`, `device_root`, `device_file`, `device_database`,
   `device_sharedpref` — and `data_extraction_rules.xml` does so in **both**
   its `<cloud-backup>` and `<device-transfer>` sections, with no `<include>`
   anywhere. The domains are siblings, not a hierarchy: excluding `root`
   alone still hands `files/`, `databases/` and `shared_prefs/` to the
   transport. `app/src/main/res/xml/` holds FastReader's copy to take.
2. **No cleartext allowance in a release build.** A debug build may permit
   cleartext to the emulator loopback `10.0.2.2` (and nothing else) through a
   network security configuration that lives **only in the debug source
   set**; the main manifest references no configuration and no manifest ever
   sets `android:usesCleartextTraffic`. A release build therefore has no
   `networkSecurityConfig` attribute at all.
3. **Its own application id**, distinct from any other app the module is
   developed beside. The host here is `com.cedagova.fastreader`.
