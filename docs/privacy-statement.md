# FastReader privacy statement

One statement, two places. Settings shows it in the app
(`settings_privacy` in `app/src/main/res/values/strings.xml`); every GitHub
Release carries the same words in its notes, and the README repeats them
(LEAF509, #49). `PrivacyStatementTest` compares the block below against the
string resource word for word, so the two cannot drift: change one and the
unit tests go red until the other matches.

## The block to paste into release notes

<!-- privacy-statement:begin -->
FastReader has no internet permission, so it cannot send or receive anything
itself. Check for updates only hands a web address to your browser, and your
browser makes that request. Your books stay in the folders you chose; on this
device FastReader keeps only its own list of them, your reading positions, your
settings and small cover thumbnails, in its private storage. None of that is
included in this device's backup or in a transfer to a new phone, so a reinstall
or a new phone starts with an empty library.
<!-- privacy-statement:end -->

## What each sentence rests on

Every sentence is either a manifest declaration or something the app is
observed doing. Nothing here is an intention.

| Sentence | What makes it true | How it was checked |
| --- | --- | --- |
| No internet permission, so it cannot send or receive anything itself. | `AndroidManifest.xml` declares no `<uses-permission>` at all, so `android.permission.INTERNET` is absent from the merged manifest and the packaged APK. | `aapt2 dump badging` on the built APK lists no permissions; `scripts/release.sh` fails the release outright if `android.permission.INTERNET` ever appears (REQ-050). |
| Check for updates only hands a web address to your browser. | `SettingsRoute` starts `ACTION_VIEW` for `https://github.com/cedagova/fastReader/releases`; there is no HTTP client anywhere in the app, and without the permission there could not be one. | Emulator: tapping the button starts `ACTION_VIEW` for that URL and the browser takes the foreground from FastReader (`docs/evidence/46/update-handoff-browser-phone-mid-api36.png`). |
| Books stay in the folders you chose. | Books are read through SAF document URIs. No code path copies a book: `CatalogIngestor` streams for metadata and a cover, and the reader streams the text. | Emulator: after adding two books, the private data directory holds no `.epub` — only the catalog, the covers and the theme mirror. |
| FastReader keeps only its own list of them, reading positions, settings and small cover thumbnails, in its private storage. | `LibraryGraph` wires two stores under `filesDir` — `catalog/catalog.json` (books, positions and settings) and `covers/` — and `SharedPreferencesThemeMirror` keeps a one-key copy of the theme in `shared_prefs/launch-theme.xml` so the splash resolves correctly. No database, nothing on external storage. | Emulator, `run-as com.cedagova.fastreader ls -R .` after adding two books and reading one: `files/catalog/catalog.json`, two files in `files/covers/`, `shared_prefs/launch-theme.xml`, and the platform's own zero-byte `files/profileInstalled` marker. Nothing else, and `databases/`, `cache/` and `no_backup/` are empty. |
| None of that is included in this device's backup or in a transfer to a new phone. | `android:allowBackup="false"` plus `res/xml/data_extraction_rules.xml`, which excludes every domain — `root`, `file`, `database`, `sharedpref`, `external` and their `device_*` twins — from both `<cloud-backup>` and `<device-transfer>` (AD-11). The attribute alone is not enough: Android 12+ device transfer ignores it. | Emulator, on the same seeded library: the cloud transport answers "Backup is not allowed" and the device-transfer transport takes zero bytes, where the pre-change build handed both 9,728 bytes containing `catalog.json` and the covers (`docs/evidence/46/backup-run-phone-mid-api36.txt`). |
| A reinstall or a new phone starts with an empty library. | Follows from the line above, and from SAF grants not surviving a reinstall either (D3). | Emulator: uninstall, reinstall, launch — the empty-library state, with a fresh `catalog.json` whose `books` array is empty (`docs/evidence/46/reinstall-empty-library-phone-mid-api36.png`). |
