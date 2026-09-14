# FastReader privacy statement

One statement, three published places, and one translation. Settings shows it
in the app (`settings_privacy` in `app/src/main/res/values/strings.xml`); every
GitHub Release carries the same words in its notes
(`docs/release-notes/v<version>.md`, passed to `scripts/release.sh --publish
--notes-file`); and the README repeats them. `PrivacyStatementTest` compares the
block below, the README's copy and this version's release-notes copy against the
string resource word for word, so none of them can drift: change one and the
unit tests go red until the rest match.

The Spanish copy — `settings_privacy` in
`app/src/main/res/values-es/strings.xml`, added in #55 — is the one copy no gate
holds. Lint's `MissingTranslation` fails a Spanish string that is *absent*, not
one that is *stale*, and the word-for-word comparison above is against English a
translation cannot equal by definition. **Editing this statement means editing
the Spanish string in the same commit, by hand.** v1.2.0's crash-report sentence
was the first time that mattered and v1.6.0's account sentences the second; the
same pairing applies to `settings_visual_only` and the `crash_offer_*` strings.

Until v1.5.0 the statement opened with "FastReader has no internet permission".
#100 replaced that promise, by owner decision, with the optional Reader account
(the app is the owner's own testing app for the Reader stage backend), and the
statement now says what using the account sends and to whom. Earlier
release-notes files keep the statement they shipped with.

## The block to paste into release notes

<!-- privacy-statement:begin -->
FastReader has the internet permission and uses it for one thing only: the
optional Reader account under Settings. Nothing is sent unless you use that
account. When you do, your email address, the code or password you type and
the account's session go to the Reader identity provider and the Reader API,
and nothing else does: your books, your reading positions, your settings and
any crash report stay on this device and are never sent. The account session
is kept encrypted on this device, outside its backup, and is removed when you
sign out. Check for updates only hands a web address to your browser, and your
browser makes that request. Your books stay in the folders you chose; on this
device FastReader keeps only its own list of them, your reading positions, your
settings and small cover thumbnails, in its private storage. If the app stops
unexpectedly it also keeps one short report about what went wrong in that
private storage: the app version, this device's model, its Android version and
where in the code it stopped, with no part of any book in it — the next launch
offers that report to you once, and it goes nowhere unless you share it and
pick an app to send it to. None of that is included in this device's backup or
in a transfer to a new phone, so a reinstall or a new phone starts with an
empty library. When another app opens a book in FastReader and does not give
lasting permission to read it, that book is not added to your list and no
permission to it is kept; only your place in it is remembered.
<!-- privacy-statement:end -->

## What each sentence rests on

Every sentence is either a manifest declaration or something the app is
observed doing. Nothing here is an intention.

| Sentence | What makes it true | How it was checked |
| --- | --- | --- |
| Has the internet permission and uses it for one thing only: the optional Reader account under Settings. Nothing is sent unless you use that account. | `android.permission.INTERNET` reaches the merged manifest from the `:reader-auth` library's manifest, the one place it is declared; no manifest under `app/` declares a permission. The only code that opens a connection is the library, and the app reaches it only from the Reader account screen and from the foreground hook the library contract requires — which sends nothing when no session is stored. There is no other HTTP client, no analytics, no update check. | `ReaderAccountManifestTest` reads the merged manifest back and keeps `app/`'s manifests free of permissions; `scripts/release.sh` dies unless the signed APK's permissions are exactly `android.permission.INTERNET` and `com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and its manifest allows no cleartext (REQ-411). Emulator, signed out: the v1.5.0 flows show no difference and nothing from the library appears in logcat (`docs/evidence/100/`). |
| When you use it, your email address, the code or password you type and the account's session go to the Reader identity provider and the Reader API, and nothing else does: books, positions, settings and crash reports stay on this device and are never sent. | Every account operation is one call of `ReaderAuthClient` (`reader-auth/CONTRACT.md`): the address, code and password travel only in the provider's sign-in, verify and recovery requests; the session's bearer token travels only to reader-api's pre-auth and capabilities routes. `LibraryReaderAccountGateway` is the only path from the app into the library and exposes exactly those operations; nothing in the account surface reads the catalog, the positions, the settings or the crash store. | The library's mock-engine tests pin every request body and header it sends (`ProviderOperationsTest`, `ReaderApiPolicyTest`); `ReaderAccountControllerTest` pins that each form calls exactly its operation. Emulator: the owner-relayed stage run signs up, signs in, fetches capabilities and signs out from FastReader with the requests recorded under the library's own tag only (`docs/evidence/100/`). |
| The account session is kept encrypted on this device, outside its backup, and is removed when you sign out. | The library stores the session as one file, `no_backup/reader-auth/session.bin`, encrypted with an AES-256-GCM key generated in the Android Keystore; the file is under the platform's no-backup directory, and FastReader's nine-domain extraction rules exclude it a second time. Sign-out clears the store before telling the provider, so a provider failure never leaves the device signed in (`reader-auth/CONTRACT.md`, "Session storage and backup exclusion", "Sign-out semantics"). | `FileSessionStoreTest` pins the file, the encryption and the clear; `ReaderAccountManifestTest` pins the rules. Emulator, signed in: the `docs/evidence/46/` backup procedure moves zero bytes on both transports, the session survives `am force-stop` and `adb reboot`, and after sign-out — in airplane mode — `no_backup/reader-auth/` holds no `session.bin` (`docs/evidence/100/`). |
| Check for updates only hands a web address to your browser. | `SettingsRoute` starts `ACTION_VIEW` for `https://github.com/cedagova/fastReader/releases`; the app makes no request of its own for it — the only HTTP client in the app is the account library's, and nothing in Settings calls it for this. | Emulator: tapping the button starts `ACTION_VIEW` for that URL and the browser takes the foreground from FastReader (`docs/evidence/46/update-handoff-browser-phone-mid-api36.png`). |
| Books stay in the folders you chose. | Books are read through SAF document URIs. No code path copies a book: `CatalogIngestor` streams for metadata and a cover, and the reader streams the text. | Emulator: after adding two books, the private data directory holds no `.epub` — only the catalog, the covers and the theme mirror. |
| FastReader keeps only its own list of them, reading positions, settings and small cover thumbnails, in its private storage. | `LibraryGraph` wires two stores under `filesDir` — `catalog/catalog.json` (books, positions and settings) and `covers/` — and `SharedPreferencesThemeMirror` keeps a one-key copy of the theme in `shared_prefs/launch-theme.xml` so the splash resolves correctly. No database, nothing on external storage. The account session, when there is one, is the separate encrypted file named three rows up, under `no_backup/`. | Emulator, `run-as com.cedagova.fastreader ls -R .` after adding two books and reading one: `files/catalog/catalog.json`, two files in `files/covers/`, `shared_prefs/launch-theme.xml`, and the platform's own zero-byte `files/profileInstalled` marker. Nothing else, and `databases/`, `cache/` and `no_backup/` are empty. |
| None of that is included in this device's backup or in a transfer to a new phone. | `android:allowBackup="false"` plus `res/xml/data_extraction_rules.xml`, which excludes every domain — `root`, `file`, `database`, `sharedpref`, `external` and their `device_*` twins — from both `<cloud-backup>` and `<device-transfer>` (AD-11). The attribute alone is not enough: Android 12+ device transfer ignores it. | Emulator, on the same seeded library: the cloud transport answers "Backup is not allowed" and the device-transfer transport takes zero bytes, where the pre-change build handed both 9,728 bytes containing `catalog.json` and the covers (`docs/evidence/46/backup-run-phone-mid-api36.txt`). |
| If the app stops unexpectedly it keeps one short report in that private storage; the next launch offers it once, and it goes nowhere unless you share it. | `CrashReportStore` writes a single `files/crash/report.txt` under `filesDir` — one slot, overwritten rather than appended — and the handler re-throws instead of swallowing. `CrashShare` builds an `ACTION_SEND` chooser only from the reader's own tap on "Share report"; sharing and declining both delete the file, and the offer is shown once either way. `CrashReport` redacts throwable messages, so the text carries the app version, device model, Android version and stack frames and no book title, path or file name (REQ-207, AD-14). | Emulator, induced crash whose message deliberately carried a book title, an author and a full path: the offer appeared once (`docs/evidence/54/01-offer-on-next-launch-phone-mid-api36.png`), the share sheet's own preview of the intent text held none of them (`docs/evidence/54/shared-report-phone-mid-api36.txt`), and after either answer `run-as com.cedagova.fastreader ls files/crash` was empty with no dialog on the next cold launch (`docs/evidence/54/03-not-offered-again-after-declining-phone-mid-api36.png`). |
| A reinstall or a new phone starts with an empty library. | Follows from the line above, and from SAF grants not surviving a reinstall either (D3). | Emulator: uninstall, reinstall, launch — the empty-library state, with a fresh `catalog.json` whose `books` array is empty (`docs/evidence/46/reinstall-empty-library-phone-mid-api36.png`). |
| When another app opens a book and does not give lasting permission, the book is not added to your list and no permission to it is kept; only your place in it is remembered. | `ExternalOpenController.resolveIdentity` adds a library row only when `takePersistableUriPermission` succeeds, and releases a grant that persisted but produced no row; the reading position is stored under the book's whole-file SHA-256, like every other position (AD-9), so it survives without anything else about the book surviving. | Emulator, three grants from this package at one moment: the two books handed over by the Files app are `persistable=0x0 persisted=0x0`, the same file taken through FastReader's own picker is `persistable=0x3 persisted=0x1` (`docs/evidence/44/uri-grants-phone-mid-api36.txt`). After reading a handed-over book to 33 % and closing it, `catalog.json` holds `"books":[]` and one `readingStates` entry, and the library screen is empty (`docs/evidence/44/library-has-no-row-after-closing-phone-mid-api36.png`). |
