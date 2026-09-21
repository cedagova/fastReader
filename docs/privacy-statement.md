# FastReader privacy statement

One statement, four held-equal copies, and one translation. Settings shows it
in the app (`settings_privacy` in `app/src/main/res/values/strings.xml`); the
block below is the copy this page explains; every GitHub Release carries the
same words in its notes (`docs/release-notes/v<version>.md`, passed to
`scripts/release.sh --publish --notes-file`); and the README repeats them.
`PrivacyStatementTest` compares the block below, the README's copy and this
version's release-notes copy against the string resource word for word, so none
of the four can drift: change one and the unit tests go red until the rest
match.

The Spanish copy — `settings_privacy` in
`app/src/main/res/values-es/strings.xml`, added in #55 — is the one copy no gate
holds. Lint's `MissingTranslation` fails a Spanish string that is *absent*, not
one that is *stale*, and the word-for-word comparison above is against English a
translation cannot equal by definition. **Editing this statement means editing
the Spanish string in the same commit, by hand.** v1.2.0's crash-report sentence
was the first time that mattered, v1.6.0's account sentences the second,
v1.7.0's account-library sentences the third and v1.7.0's add-to-account
sentence the fourth; the same pairing applies to `settings_visual_only` and the
`crash_offer_*` strings.

## The promises this statement has retired

Both were true when they shipped and stopped being true when code landed that
contradicted them. Neither was softened into an intention; each was replaced by
a narrower claim the build actually backs up, and `PrivacyStatementTest` asserts
the retired wording is **absent** so it cannot creep back.

| Retired in | The promise | Why it stopped being true |
| --- | --- | --- |
| v1.6.0 (#100) | "FastReader has no internet permission." | The optional Reader account, by owner decision: the app is the owner's own testing app for the Reader stage backend, so it holds `INTERNET` and the statement says what using the account sends and to whom. |
| v1.7.0 (#115) | "…and nothing else does: your books, your reading positions, your settings and any crash report stay on this device and are never sent." | #113 and #114 put the account library on the shelf. A `library_item` mutation — removal, restore, `reading`, `finished`, last-opened — now leaves this device for the books the account already holds. The books half of that sentence is false; the positions, settings and crash-report half is still true and is kept, verbatim, in the narrower sentence that replaced it (AD-27). |
| v1.7.0 (#117) | "no book file is ever sent" | #117 gives a device book an **Add to account library** action. Saying yes to its question uploads that file to the account's storage and keeps it there. The replacement makes the same claim conditional on the thing the code actually requires — "a book file is sent only for a book you add that way" — and the condition is a type, not a comment: every call that can admit an import takes an `UploadConsent` with no default, and the only way to make one is the owner's answer. |
| v1.7.0 (#117) | "a book that is only on this device is never named to the Reader API" | The admission for an added book carries its name, size, format and SHA-256, so a book the owner adds *is* named. It is still true of every book the owner does not add, which is what the replacement says: "…is never named to the Reader API **until you add it**". |

The replacement claims **less** than the merged code does wherever that is
simpler, which is allowed; it never claims more, which is a hard failure. As of
#117 the statement has its import sentence and no other: it still says nothing
about downloading an account book or sending a reading position, because
increments 003 and 004 do those and the leaves that add them (LEAF812, LEAF822)
add their sentence with the code.

Earlier release-notes files keep the statement they shipped with.

## The block to paste into release notes

<!-- privacy-statement:begin -->
FastReader has the internet permission and uses it for one thing only:
the optional Reader account under Settings. Nothing is sent unless you
use that account. When you do, your email address, the code or password
you type and the account's session go to the Reader identity provider
and the Reader API. FastReader also asks the Reader API which books your
account already holds, and for those books only it tells the Reader API
that you opened one, when you last opened it, whether you have finished
it, and when you take one out of your account or put it back. When you
choose Add to account library for a book on this device and confirm,
FastReader asks the Reader API what kinds and sizes of file your account
accepts and then sends that book's file, its name, its size, its format
and its checksum to the Reader API and its storage, where your account
keeps them; nothing about that book is sent before you confirm. That is
all that leaves this device: a book file is sent only for a book you add
that way, a book that is only on this device is never named to the
Reader API until you add it, and your reading positions, your reading
speed, your other settings and any crash report stay on this device and
are never sent. The account session is kept encrypted on this device,
outside its backup, and is removed when you sign out. Check for updates
only hands a web address to your browser, and your browser makes that
request. Your books stay in the folders you chose; on this device
FastReader keeps only its own list of them, your reading positions, your
settings, small cover thumbnails and, once you sign in, a copy of your
account's own book list and a note of any book you are part-way through
adding to it, in its private storage. That copy of the account's list is
not deleted when you sign out: it stays in that private storage, so
signing in to the same account again picks up where it left off, and
only uninstalling FastReader or clearing its data removes it. If the app
stops unexpectedly it also keeps one short report about what went wrong
in that private storage: the app version, this device's model, its
Android version and where in the code it stopped, with no part of any
book in it — the next launch offers that report to you once, and it goes
nowhere unless you share it and pick an app to send it to. None of that
is included in this device's backup or in a transfer to a new phone, so
a reinstall or a new phone starts with an empty library. When another
app opens a book in FastReader and does not give lasting permission to
read it, that book is not added to your list and no permission to it is
kept; only your place in it is remembered.
<!-- privacy-statement:end -->

## What each sentence rests on

Every sentence is either a manifest declaration or something the app is
observed doing. Nothing here is an intention. The thirteen rows are the
thirteen sentences of the block above, in order.

| # | Sentence | What makes it true | The test or evidence that checks it |
| --- | --- | --- | --- |
| 1 | Has the internet permission and uses it for one thing only: the optional Reader account under Settings. | `android.permission.INTERNET` reaches the merged manifest from the `:reader-auth` library's manifest, still the one place it is declared: `:reader-library`'s manifest is deliberately empty, because that module is typed calls over the authenticated client `:reader-auth` already owns, and no manifest under `app/` declares a permission. Since #116 there are two HTTP clients rather than one, and the second is the point rather than a slip: publication bytes go to the storage provider over `PublicationTransferClient`, which holds no session, no `ReaderApiClient` and no way to reach a token, because the contract says a bearer is not a substitute for the signed grant. Both live in library modules that declare no permission of their own, and the app reaches either one only from the Reader account screen, the account library's foreground triggers, and the add-to-account action a reader taps. No analytics, no update check, no third client. | `app/src/test/java/com/cedagova/fastreader/account/ReaderAccountManifestTest.kt` reads the merged manifest back and keeps `app/`'s manifests free of permissions; `scripts/release.sh` dies unless the signed APK's permissions are exactly `android.permission.INTERNET` and `com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and its manifest allows no cleartext (REQ-411) — run recorded in `docs/evidence/106/leaf-115-release-verify.txt`. |
| 2 | Nothing is sent unless you use that account. | Signed out there is no session, so `AccountSyncEngine` holds no active account: no capability read, no library read, no delta read and no mutation batch. A build with no `reader.*` values cannot even reach the gateway — `ReaderLibraryGateway` is null and every action is a no-op. | `AccountSyncEngineTest.signing out keeps the store and takes the account rows off the shelf`; `LibraryAccountUiStateTest.signed out the shelf is exactly the device shelf` and `a build with no stage values shows no account notice`. Emulator, signed out on `Phone_Mid_API36`: the shelf and Settings are the 1.6.0 app and nothing from either library appears in logcat (`docs/evidence/106/leaf-114-device-run-phone-mid-api36.txt`, `docs/evidence/106/leaf-114-01-signed-out-shelf-phone-mid-api36.png`). |
| 3 | When you do, your email address, the code or password you type and the account's session go to the Reader identity provider and the Reader API. | Every account operation is one call of `ReaderAuthClient` (`reader-auth/CONTRACT.md`): the address, code and password travel only in the provider's sign-in, verify and recovery requests; the session's bearer token travels only to reader-api's routes. `LibraryReaderAccountGateway` is the only path from the app into `:reader-auth` and exposes exactly those operations. | `reader-auth`'s `ProviderOperationsTest` and `ReaderApiPolicyTest` pin every request body and header over a Ktor mock engine; `ReaderAccountControllerTest` pins that each form calls exactly its operation. |
| 4 | FastReader also asks the Reader API which books your account already holds, and for those books only it tells the Reader API that you opened one, when you last opened it, whether you have finished it, and when you take one out of your account or put it back. | `:reader-library` offers five typed operations and no generic call: `GET /v1/reader/library`, `GET /v1/reader/progress`, `GET /v1/reader/sync/deltas`, the `reader.sync.v1` capability read, and `POST /v1/reader/sync/mutations`. The only outbound writes `AccountSyncEngine` builds are `library_item` envelopes: `delete`, `restore`, and `upsert` carrying `status` and `last_opened_at`. The key is the **account's** book id, which `accountBookIdForDevice` resolves to null for a device book the account does not hold — such a book sends nothing at all. | `AccountSyncEngineTest.nothing but library_item mutations is ever produced` drives remove, undo, open and finish and asserts every envelope's `resourceType` is `LIBRARY_ITEM`; `LibraryAccountUiStateTest.opening a device book finds the account book it is` asserts the null for a device-only book; `reader-library`'s `ReaderLibraryClientTest` pins each of the five requests. |
| 5 | When you choose Add to account library for a book on this device and confirm, FastReader asks the Reader API what it accepts and then sends that book's file, its name, its size, its format and its checksum to the Reader API and its storage, where your account keeps them; nothing about that book is sent before you confirm. | Two methods, not one branch. `AccountImports.requestAdd` reads `GET /reader/v1/imports/policy` — a request with no body that names no book — and ends in the question or in the refusal that policy already implies; `AccountImports.confirmAdd` is the owner's answer and the only call in the app that reaches `PublicationImportEngine.start`. The consent travels as a value: every entry point that can admit an import takes an `UploadConsent`, it has one member, no parameter on the way there has a default, and `ReaderLibraryClient.admitImport` refuses locally to put a request on the wire whose `upload_consent` is not `true`. The bytes themselves never touch reader-api: they go to the storage provider over `PublicationTransferClient`, which holds no session and can send no bearer. The admission carries `client_import_id`, `source_format`, `source_mime_type`, `size_bytes`, `sha256` and the original file name — and nothing else about the book. | `AccountImportsTest.tapping Add reads the policy, asks the question, and sends nothing about the book` asserts the whole call log is one policy read and that no consent reached a sending call; `declining the question sends nothing, ever`; `a confirm that does not follow a question is ignored`; `confirming is the only call that sends, and it carries the consent`. `PublicationTransferClientTest` asserts the absence of `Authorization` on every transfer request, and `ReaderLibraryClientTest` pins the admission body against the pinned document. Reviewing the real backend records is deferred: `PENDING OWNER TEST` for zero imports and zero bytes before the tap, and exactly one import after it. |
| 6 | That is all that leaves this device: a book file is sent only for a book you add that way, a book that is only on this device is never named to the Reader API until you add it, and your reading positions, your reading speed, your other settings and any crash report stay on this device and are never sent. | The only code path that uploads a book is the one row 5 describes, and it is reachable only from a device book's own action: `addToAccountFor` returns null for every account row, every unreadable row, every row while signed out and every row while `reader.sync.v1` is unavailable, so a book nobody adds has no control that could send it. The `upsert` payload carries `status` and `last_opened_at` and nothing else — no title, no author, no checksum, no path. No `profile`, `settings`, `note` or `bookmark` envelope is constructed anywhere, and no `reading_progress` mutation is sent: positions are read *from* the stream into the account document and never written back. The crash store is untouched by the import and account code alike, and no reading position, WPM or setting is part of an admission. | `AccountSyncEngineTest.nothing but library_item mutations is ever produced` (resource type, whole batch); `LibraryAccountUiStateTest`'s six `addToAccount` cases pin every row that offers nothing; `ReaderLibraryOperations` declares the five operations exhaustively and `ReaderLibraryContractTest` checks each against the pinned document; `reader-library/contracts/PINNED.md` records the identity. Reviewing the real backend records is deferred: `PENDING OWNER TEST — step 13 of the owner list` for "only `library_item` mutations from the `reader-android` client" and `PENDING OWNER TEST — step 14 of the owner list` for the recorded field shapes. |
| 7 | The account session is kept encrypted on this device, outside its backup, and is removed when you sign out. | The library stores the session as one file, `no_backup/reader-auth/session.bin`, encrypted with an AES-256-GCM key generated in the Android Keystore; the file is under the platform's no-backup directory, and FastReader's nine-domain extraction rules exclude it a second time. Sign-out clears the store before telling the provider, so a provider failure never leaves the device signed in (`reader-auth/CONTRACT.md`). | `FileSessionStoreTest` pins the file, the encryption and the clear; `ReaderAccountManifestTest` pins the rules. Emulator, signed in: the `docs/evidence/46/` backup procedure moves zero bytes on both transports, the session survives `am force-stop` and `adb reboot`, and after sign-out `no_backup/reader-auth/` holds no `session.bin` (`docs/evidence/100/`). |
| 8 | Check for updates only hands a web address to your browser, and your browser makes that request. | `SettingsRoute` starts `ACTION_VIEW` for `https://github.com/cedagova/fastReader/releases`; the app makes no request of its own for it — the only HTTP clients in the app are the two libraries', and nothing in Settings calls either for this. | Emulator: tapping the button starts `ACTION_VIEW` for that URL and the browser takes the foreground from FastReader (`docs/evidence/46/update-handoff-browser-phone-mid-api36.png`). |
| 9 | Your books stay in the folders you chose; on this device FastReader keeps only its own list of them, your reading positions, your settings, small cover thumbnails and, once you sign in, a copy of your account's own book list and a note of any book you are part-way through adding to it, in its private storage. | Books are read through SAF document URIs and no code path copies one: `CatalogIngestor` streams for metadata and a cover, and the reader streams the text. `LibraryGraph` wires `filesDir/catalog/catalog.json` (books, positions and settings) and `filesDir/covers/`, and `SharedPreferencesThemeMirror` keeps a one-key theme copy in `shared_prefs/launch-theme.xml`. Since #113 there is one more private file: `filesDir/account-library/account-<sha256 of the user id>.json`, the account's rows, cursor and outbox — one per account, named after the digest of the id so the id itself is not legible in a directory listing. Since #117 that same document also holds the import records of AD-26 (schema 2): for each add still in flight, the derived `client_import_id`, the file's digest, size, format and name, the resumable upload's location and when its grant expires. No copy of the book is made — the import reads the reader's own file in place, one chunk at a time — and the record is dropped the moment the import is terminal. The grant's signed headers are deliberately *not* stored. The account session is the separate encrypted file named two rows up, under `no_backup/`. No database, nothing on external storage. | `AccountLibraryStoreTest` pins that file: its name (`account-` + the SHA-256 of the user id + `.json`), the atomic write through a `.tmp` sibling, the quarantine of a damaged document, the round trip of one import record and the no-op migration of a version 1 document — and `FileAccountLibraryStores` is the only writer, so the location and naming are proven here without a device. Emulator, `run-as com.cedagova.fastreader ls -R .` after adding two books and reading one: `files/catalog/catalog.json`, two files in `files/covers/`, `shared_prefs/launch-theme.xml`, and the platform's own zero-byte `files/profileInstalled` marker (`docs/evidence/106/leaf-114-device-run-phone-mid-api36.txt`). |
| 10 | That copy of the account's list is not deleted when you sign out: it stays in that private storage, so signing in to the same account again picks up where it left off, and only uninstalling FastReader or clearing its data removes it. | This is D4, and it is deliberate. `AccountSyncEngine.signOut` stops reading the store and **keeps the file** — the queue is held for the next sign-in to the same account — so the rows and cursor survive sign-out indefinitely. Signing in as a *different* user does not clear it either: `discardOtherAccountQueues` empties the other account's `outbox` and re-saves the same document, leaving its book list alone. Nothing in the app deletes `filesDir/account-library/`; only uninstalling or clearing the app's data does, which is the platform's own behaviour for `filesDir`. | `AccountSyncEngineTest.signing out keeps the store and takes the account rows off the shelf` asserts `sign-out deletes nothing` on the file itself and that the document still holds the account's books; `a different account starts empty and discards the previous account's held queue` asserts the other account's outbox is emptied *and* that "user-1's rows are left alone". The sentence states exactly that retention rather than implying its absence — the first draft of this statement said "while you are signed in", which the code does not honour, and `PrivacyStatementTest` now asserts that phrase is absent. |
| 11 | If the app stops unexpectedly it keeps one short report in that private storage; the next launch offers it once, and it goes nowhere unless you share it. | `CrashReportStore` writes a single `files/crash/report.txt` under `filesDir` — one slot, overwritten rather than appended — and the handler re-throws instead of swallowing. `CrashShare` builds an `ACTION_SEND` chooser only from the reader's own tap on "Share report"; sharing and declining both delete the file, and the offer is shown once either way. `CrashReport` redacts throwable messages, so the text carries the app version, device model, Android version and stack frames and no book title, path or file name (REQ-207, AD-14). | Emulator, induced crash whose message deliberately carried a book title, an author and a full path: the offer appeared once (`docs/evidence/54/01-offer-on-next-launch-phone-mid-api36.png`), the share sheet's own preview held none of them (`docs/evidence/54/shared-report-phone-mid-api36.txt`), and after either answer `run-as com.cedagova.fastreader ls files/crash` was empty with no dialog on the next cold launch (`docs/evidence/54/03-not-offered-again-after-declining-phone-mid-api36.png`). |
| 12 | None of that is included in this device's backup or in a transfer to a new phone, so a reinstall or a new phone starts with an empty library. | `android:allowBackup="false"` plus `res/xml/data_extraction_rules.xml`, which excludes every domain — `root`, `file`, `database`, `sharedpref`, `external` and their `device_*` twins — from both `<cloud-backup>` and `<device-transfer>` (AD-11). The account document is under `filesDir`, so the `file` and `device_file` exclusions cover it with everything else; the session is under `no_backup/` and excluded twice. The attribute alone is not enough: Android 12+ device transfer ignores it. | `ReaderAccountManifestTest` pins all nine domains. Emulator, on the same seeded library: the cloud transport answers "Backup is not allowed" and the device-transfer transport takes zero bytes, where the pre-change build handed both 9,728 bytes containing `catalog.json` and the covers (`docs/evidence/46/backup-run-phone-mid-api36.txt`). Reinstall: the empty-library state with a fresh `catalog.json` whose `books` array is empty (`docs/evidence/46/reinstall-empty-library-phone-mid-api36.png`). |
| 13 | When another app opens a book and does not give lasting permission, the book is not added to your list and no permission to it is kept; only your place in it is remembered. | `ExternalOpenController.resolveIdentity` adds a library row only when `takePersistableUriPermission` succeeds, and releases a grant that persisted but produced no row; the reading position is stored under the book's whole-file SHA-256, like every other position (AD-9), so it survives without anything else about the book surviving. Such a book has no account counterpart either, so it sends nothing (row 4). | Emulator, three grants from this package at one moment: the two books handed over by the Files app are `persistable=0x0 persisted=0x0`, the same file taken through FastReader's own picker is `persistable=0x3 persisted=0x1` (`docs/evidence/44/uri-grants-phone-mid-api36.txt`). After reading a handed-over book to 33 % and closing it, `catalog.json` holds `"books":[]` and one `readingStates` entry, and the library screen is empty (`docs/evidence/44/library-has-no-row-after-closing-phone-mid-api36.png`). |

## The pinned Reader API contract

The sentences in rows 4 and 5 are claims about *which requests exist*, so they
are only as good as the document those requests are checked against.
`reader-library/contracts/reader-api.openapi.json` is a byte-for-byte copy of
the published Reader API document at
`Chunipers/reader-api@a517fc6db64560df309bce656ec4c34e0bc7e1bd`, sha256
`a550abfd7046368681d02aec50e80e80e372b152416c744669dd72ee534c6f9e`, recorded in
`reader-library/contracts/reader-api.openapi.json.sha256` and explained in
`reader-library/contracts/PINNED.md`. `ReaderLibraryContractTest` recomputes the
digest on every run and then checks every field name, JSON type, enum member and
required flag the module sends or reads against that document's schemas. Drift
is raised with Chunipers, never worked around locally.
