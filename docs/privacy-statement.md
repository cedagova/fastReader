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
device FastReader keeps its own list of them, your reading positions, your
settings and small cover thumbnails in its private storage, and nothing else.
None of that is included in this device's backup or in a transfer to a new
phone, so a reinstall or a new phone starts with an empty library.
<!-- privacy-statement:end -->

## What each sentence rests on

Every sentence is either a manifest declaration or something the app is
observed doing. Nothing here is an intention.

| Sentence | What makes it true | How it was checked |
| --- | --- | --- |
| No internet permission, so it cannot send or receive anything itself. | `AndroidManifest.xml` declares no `<uses-permission>` at all, so `android.permission.INTERNET` is absent from the merged manifest and the packaged APK. | `aapt2 dump badging` on the built APK lists no permissions; `scripts/release.sh` fails the release outright if `android.permission.INTERNET` ever appears (REQ-050). |
| Check for updates only hands a web address to your browser. | `SettingsRoute` starts `ACTION_VIEW` for `https://github.com/cedagova/fastReader/releases`; there is no HTTP client anywhere in the app, and without the permission there could not be one. | Emulator: tapping the button leaves FastReader and lands in the browser on the releases page (`docs/evidence/46/`). |
| Books stay in the folders you chose. | Books are read through SAF document URIs. No code path copies a book: `CatalogIngestor` streams for metadata and a cover, and the reader streams the text. | Emulator: after adding a folder, the app's private storage holds no `.epub`. |
| FastReader keeps its own list of them, reading positions, settings and small cover thumbnails in its private storage, and nothing else. | `LibraryGraph` wires exactly two stores, both under `filesDir`: `catalog/catalog.json` (books, positions, settings) and `covers/`. There is no `SharedPreferences`, no database, and nothing written to external storage. | Emulator: `run-as com.cedagova.fastreader ls -R` after real use lists only `files/catalog` and `files/covers`. |
| None of that is included in this device's backup or in a transfer to a new phone. | `android:allowBackup="false"` plus `res/xml/data_extraction_rules.xml`, which excludes `root`, `device_root` and `external` from both `<cloud-backup>` and `<device-transfer>` (AD-11). | Emulator: the platform backup manager refuses to back the package up, where the same run against the pre-change build captured its data (`docs/evidence/46/`). |
| A reinstall or a new phone starts with an empty library. | Follows from the line above, and from SAF grants not surviving a reinstall either (D3). | Emulator: uninstall and reinstall leaves the empty-library state. |
