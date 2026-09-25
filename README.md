# FastReader

An Android speed reader for EPUB files that are already on your phone. It shows
one word at a time, in place, at a pace you set — no store, and no account
needed to read.

FastReader holds **one permission of its own: the internet permission**, and
uses it for **one thing: the optional Reader account under Settings**. Since
1.6.0 the app is also the owner's testing app for the Reader stage backend, and
that screen signs in to it. Reading never touches the network: signed out, or
with no network at all, every screen outside that one behaves as it always has,
and nothing about your books is ever sent. `scripts/release.sh` refuses to
publish a build whose permissions are anything but the internet permission plus
the one Android adds for its own broadcast plumbing, or whose manifest allows a
cleartext connection.

## What it looks like

Captured on the reference emulator (`Phone_Mid_API36`, Android 16, 1080p) from
the v1.2.0 release APK.

| Reading | Library | Settings |
| --- | --- | --- |
| ![The reader paused mid-sentence: one word large in the middle of the screen with its focus letter coloured, the sentence it came from underneath, chapter, progress and speed below](docs/screenshots/reader-phone-mid-api36.png) | ![The library listing two books with their authors and how far each one has been read, under a Sort: Recently read button](docs/screenshots/library-phone-mid-api36.png) | ![Settings on a fresh install: theme, text size, and the Cues section with Highlight letter on, Fixed focus letter off and Guide marks on](docs/screenshots/settings-phone-mid-api36.png) |

## Install it

FastReader needs **Android 8.0 (API 26) or newer**. It is not on any store; it
is a signed APK on a GitHub Release.

1. On the phone, open the
   [latest release](https://github.com/cedagova/fastReader/releases/latest) and
   download `fastReader-<version>.apk`.
2. Tap the downloaded file. Android will ask whether the app you downloaded it
   with — your browser or your files app — may install apps; allow it for that
   app.
3. Tap **Install**, then **Open**.

Nothing else is needed: no sign-in, no permission prompt, no first-run setup.
The Reader account under Settings is optional and is for testing the Reader
backend; the app reads books without it.

**Updating** is the same three steps with a newer APK. Install it *over* the
installed one — do not uninstall first. Your books, your place in each of them
and your settings are kept; uninstalling throws them away, and so does a
reinstall, because none of that data is included in a backup or a phone-to-phone
transfer.

Every release is signed with the same key, which is what lets Android install
one over the other. **Settings → Check for updates** opens the releases page in
your browser; FastReader itself never checks anything.

## Adding books

Two ways, both from the library screen:

- **Add books** — pick individual EPUB files.
- **Add folder** — every EPUB inside it, subfolders included, appears in the
  library, and books you drop in later show up the next time you open the app,
  or straight away if you pull the list down to refresh.

The **Sort** button above the list orders your books by *Title*, *Recently
read* or *Recently added*. It starts at *Recently read*.

Books are read where they are. FastReader never copies, changes, or deletes your
files, and removing a book or a folder from the library leaves the files alone.

You can also send an EPUB to FastReader from another app — **Open with** or the
share sheet. That opens the book straight away and remembers your place in it,
but the book is only added to your library when the app that handed it over
grants lasting permission to read it; the Files app does not, so those books are
read for that session and leave no row behind.

## English and Spanish

FastReader follows your phone's language: set the phone — or FastReader alone,
in **Settings → Apps → FastReader → Language** — to Spanish and every screen,
button and TalkBack label is Spanish. There is nothing to choose inside the
app, and no other language is included.

## What stays on this device

This is the same statement the app shows under **Settings → What stays on this
device**, word for word — a unit test compares the two so they cannot drift.

<!-- privacy-statement:begin -->
FastReader has the internet permission and uses it for one thing only:
the optional Reader account under Settings. Nothing is sent unless you
use that account. When you do, your email address, the code or
password you type and the account's session go to the Reader identity
provider and the Reader API. FastReader also asks the Reader API which
books your account already holds, and for those books only it tells
the Reader API that you opened one, when you last opened it, how far
through it you are and which chapter you are in, whether you have
finished it, and when you take one out of your account or put it back;
it also asks for the place another device left in those books, so it
can offer to take you there. When you choose Add to account library
for a book on this device and confirm, FastReader asks the Reader API
what kinds and sizes of file your account accepts and then sends that
book's file, its name, its size, its format and its checksum to the
Reader API and its storage, where your account keeps them; nothing
about that book is sent before you confirm. When you choose Download
and open for a book your account already holds, FastReader asks the
Reader API for a one-off address for that book's file and fetches the
file from that address; the request names only a book your account
already has, and the account's sign-in is never given to the storage
the file comes from. That is all that leaves this device: a book file
is sent only for a book you add that way, a book that is only on this
device is never named to the Reader API until you add it, your place
in a book leaves this device only for a book your account holds and
only as the chapter and how far through it you are, and the exact word
you are on, your reading speed, your other settings and any crash
report stay on this device and are never sent. The account session is
kept encrypted on this device, outside its backup, and is removed when
you sign out. Check for updates only hands a web address to your
browser, and your browser makes that request. Your books stay in the
folders you chose; on this device FastReader keeps only its own list
of them, your reading positions, your settings, small cover thumbnails
and, once you sign in, a copy of your account's own book list, a note
of any book you are part-way through adding to it and the file of any
account book you have downloaded, in its private storage. A downloaded
copy is a file in that private storage like the rest: it is left out
of this device's backup and of a transfer to a new phone, and Remove
downloaded copy on the book's row deletes it from this device without
taking the book out of your Reader account. That copy of the account's
list is not deleted when you sign out: it stays in that private
storage, so signing in to the same account again picks up where it
left off, and only uninstalling FastReader or clearing its data
removes it. If the app stops unexpectedly it also keeps one short
report about what went wrong in that private storage: the app version,
this device's model, its Android version and where in the code it
stopped, with no part of any book in it — the next launch offers that
report to you once, and it goes nowhere unless you share it and pick
an app to send it to. None of that is included in this device's backup
or in a transfer to a new phone, so a reinstall or a new phone starts
with an empty library. When another app opens a book in FastReader and
does not give lasting permission to read it, that book is not added to
your list and no permission to it is kept; only your place in it is
remembered.
<!-- privacy-statement:end -->

What each sentence rests on, and how it was checked, is in
[docs/privacy-statement.md](docs/privacy-statement.md).

## Your Reader account's books on the shelf

Signing in under **Settings → Reader account** brings the books that account
holds onto the shelf beside the ones on this phone.

- **A book in both places is one row.** The match is the file's own SHA-256, so
  the same EPUB uploaded to the account and sitting in your folder shows once,
  with this device's cover, author and your place in it — not twice.
- **A book only the account has is an account-only row.** Title, author and "In
  your Reader account. Not on this device." No cover — the bytes are not here —
  and one action: **Download and open**.
- **Download and open** fetches that book through a one-off address the Reader
  API issues, checks the file's SHA-256 against the one your account holds for
  it, and only then opens it. Nothing opens before that check passes, and a
  file that arrives wrong is refused with the reason while the book stays in
  your account. Once the copy is here it is an ordinary book on the shelf: it
  opens in airplane mode, keeps your place, and stays after you sign out.
- **Remove downloaded copy** frees those bytes again. It is the only removal in
  the app that deletes a file, and it deletes only FastReader's own private
  copy: the book stays in your Reader account, on every device, and your place
  in it is kept for the next time you fetch it. Downloaded copies live in
  FastReader's private storage and are left out of this device's backup and of
  a transfer to a new phone, like everything else FastReader keeps.
- **Remove from account** takes a book out of the Reader account on *every*
  device signed in to it, with an Undo bar for about eight seconds. The file on
  this phone and your place in it are untouched either way.
- **Opening and finishing** a book the account already holds is recorded for the
  account. A book only on this phone records nothing.
- **Add to account library** puts a book on this phone into the account. It asks
  first, and the question is the whole point: it says the file's bytes will be
  uploaded to your Reader account and kept there, how large that is, and what
  size your account accepts — all of it read from the backend, none of it
  built in. Until you say **Add**, nothing about that book has left the phone.
  The upload shows on the row and can be called off; killing the app in the
  middle resumes it rather than starting a second one. A refusal shows the
  backend's own category — too large, unsupported, copy-protected — and leaves
  the book here exactly as it was.
- The shelf's refresh rescans your folders **and** asks the account for its
  current list.

**This is the owner's own testing app against the Reader *stage* backend**, and
nothing else. There is no production Reader account here. The three public stage
values live in the untracked `local.properties` (see
[the Reader libraries](#the-reader-libraries-fastreader-hosts) below); without
them the Reader account screen reads "Not configured" and the shelf is exactly
the device shelf. Signed out, the account library sends nothing at all.

What leaves this device when you use it — and what still never does — is the
statement above, sentence by sentence, in
[docs/privacy-statement.md](docs/privacy-statement.md).

## Personal use, distributed by link

FastReader is **for personal use and distributed by link**. It is not published
to Google Play or any other store, and it is not offered to the public.

That is a deliberate limit, not an oversight. The reader's cue set overlaps a
patent on RSVP presentation, and that is
[an open launch blocker (#33)](https://github.com/cedagova/fastReader/issues/33)
that has to be resolved before FastReader could be published anywhere. Since
v1.0.1 the presentation defaults to plain centered words — the long-standing
prior-art form — and the off-center **Fixed focus letter** alignment is an
opt-in toggle that ships off. Passing the APK to someone who asks for it is
fine; putting it in a store is not, until #33 is closed.

## The Reader libraries FastReader hosts

This repository also carries `reader-auth/`, a reusable Android library for the
Reader client's sign-in, built to be lifted into the real Reader client
unchanged. Since #100 **FastReader is its host**: `app/` depends on it, the
Reader account screen under Settings is the library's sign-in, session and
capabilities surface on a real device against the stage backend, and the
library's manifest is where the app's internet permission comes from. There is
no separate host app any more. The library depends on nothing under `app/`.
See [reader-auth/README.md](reader-auth/README.md); the client contract the
library implements — sign-in methods, session storage, refresh, error policy,
sign-out, and what a host must declare — is
[reader-auth/CONTRACT.md](reader-auth/CONTRACT.md).

Since #112 there is a second one, `reader-library/`: the account-library module
(`com.cedagova.reader.library`). It depends on `:reader-auth` and on nothing
under `app/`, declares no permission of its own, and offers exactly the twelve
typed operations `ReaderLibraryOperations` declares — six library ones
(`GET /v1/reader/library`, `GET /v1/reader/progress`,
`POST /v1/reader/sync/mutations`, `GET /v1/reader/sync/deltas` and the
`reader.sync.v1` and `reader.publication-import.v1` capability reads), five
for the publication-import lifecycle and one for the asset download grant —
plus the account sync engine. There is deliberately no generic
`call(path, body)`. See [reader-library/README.md](reader-library/README.md).

Since #200 a third, `reader-account/` (`com.cedagova.reader.account`), holds
the account pipeline on top of both — session state, shelf, verified copies,
downloads and imports — and `ReaderAccountGraph`, the one call that assembles
the libraries for a host. FastReader implements its four host seams (device
catalog, device book bytes, book identity, resume-offer note) and makes that
call in its composition root, `AppGraph`. See
[reader-account/README.md](reader-account/README.md).

A fourth, `reader-engine/` (`com.cedagova.reader.engine`, #201), is the EPUB,
content and RSVP timing engines as a plain Kotlin/JVM module with no Android
dependency. See [reader-engine/README.md](reader-engine/README.md).

**Starting a new client from this repository?** Read
[docs/architecture.md](docs/architecture.md) first: the module map, which way
the modules depend, what to copy and what to use as a pattern, and what each
library needs from its host.

**The Reader API contract it is built against is pinned, by identity, in this
repository:**

| | |
| --- | --- |
| Document | [`reader-auth/contracts/reader-api.openapi.json`](reader-auth/contracts/reader-api.openapi.json) — the one document both libraries are gated against (#208) |
| Source | `Chunipers/reader-api@909174aff6a380514da7b81263d69a4e653cfe76`, `contracts/reader-api.openapi.json`, byte for byte |
| sha256 | `e2c184dbd51d0e3f542d73d69e56a193300615de604486615b254911b67ade90`, recorded in [`reader-auth/contracts/reader-api.openapi.json.sha256`](reader-auth/contracts/reader-api.openapi.json.sha256) |
| Gate | `ReaderLibraryContractTest` and `ReaderAuthContractTest`, through one shared checker, recompute that digest on every run, then check every field name, JSON type, enum member and required flag either library sends or reads against the document's schemas |
| Updating the pin | [`reader-auth/contracts/PINNED.md`](reader-auth/contracts/PINNED.md) |

Drift between the module and a newer published contract is a proposal to
Chunipers, never a local workaround: the models move to follow the contract, and
the contract is never bent to follow the models.

Building with the account working needs three public stage values in the
untracked `local.properties` (`reader.supabaseUrl`,
`reader.supabasePublishableKey`, `reader.apiBaseUrl`); without them the build,
lint and tests are unchanged and the screen reads "Not configured".

## Build it yourself

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
./gradlew testDebugUnitTest verifyRoborazziDebug lint spotlessCheck
```

Gradle needs **JDK 21**. A debug build needs no signing material; a release
build does, and only the owner has it — see
[docs/release.md](docs/release.md) for the whole release procedure and
[docs/agent-first-development.md](docs/agent-first-development.md) for how the
project is developed and verified. How the code is laid out — modules,
dependency direction, the app shell — is
[docs/architecture.md](docs/architecture.md).

## Licence

[MIT](LICENSE).
