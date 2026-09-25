# fastReader as the base for the Reader Android client: audit summary

- Audit ID: `A197`
- Audit key: `reader-android-base-readiness-20260924`
- Status: In progress
- Prepared: 2026-09-24
- Completed: Not complete
- Dossier PR: https://github.com/cedagova/fastReader/pull/197

## Answer

fastReader is a sound base, but not yet one the Reader Android client can take
unchanged or copy without inheriting problems. The foundations are right: the
two Reader libraries never depend on the app, all network code lives in them,
the reader-api contract is pinned and drift-tested, time, threads and storage
are injectable, and all gates are green (1,219 tests, 0 lint errors, goldens
verified). What is missing is the *boundary work* a second consumer needs:
the libraries' public API is accidental and leaks provider types, hosts must
write their own seams and fakes, about 2,070 lines of general account logic
and the Android-free EPUB/tokenizer engines are stuck inside `:app`, and there
is no recorded way for another repository to consume the libraries. As an
example to copy, the app shell (wiring, state, navigation), the oversized
screen files with copied UI primitives, the per-module build config and a CI
that never builds the shipped release would teach the wrong patterns. The docs
have no module map and contradict the code in checkable places. None of this
needs a new feature; all of it is structure, tooling and documentation.

## Why it matters

Whatever is not fixed here gets copied into the Reader client, or rewritten
there. Rewriting the account logic or the tokenizer is worse than copying:
reading positions are keyed to the tokenizer, so two diverging copies would
silently disagree on synced positions.

## What we decided

- **Recommended dispositions:** accept F001–F012; defer F013 (splitting the
  sync engine is a readability gain with real concurrency risk, and its tests
  are strong — revisit when the Reader client needs to change it).
- **Owner decisions:** Pending.
- **Decisions planning will need from the owner** (not needed to accept):
  how the Reader client consumes the libraries — source copy, submodule or
  published artifact (F010, which also scopes F002 and F003); whether general
  account logic moves into a library or stays as a documented reference
  (F003); whether the Reader client offers RSVP (scopes `timing/` in F004).

## What happens next

| Outcome | Owner | Tracking issue |
| --- | --- | --- |
| Libraries have a deliberate, checked public API (F001) | cedagova/fastReader | Pending owner decision |
| Libraries ship their own seams and test fixtures (F002) | cedagova/fastReader | Pending owner decision |
| General account logic and library assembly usable outside `:app` (F003) | cedagova/fastReader | Pending owner decision |
| EPUB and tokenizer engines in their own acyclic modules (F004) | cedagova/fastReader | Pending owner decision |
| One documented app-shell convention: wiring, state, typed navigation (F005) | cedagova/fastReader | Pending owner decision |
| Shared UI components and tokens; screens split by section (F006) | cedagova/fastReader | Pending owner decision |
| `LibraryRepository` split into narrow owners (F007) | cedagova/fastReader | Pending owner decision |
| One build convention and a static-analysis gate for every module (F008) | cedagova/fastReader | Pending owner decision |
| CI builds the minified release; release script fails loud (F009) | cedagova/fastReader | Pending owner decision |
| Recorded consumption mode, library versions and own keep rules (F010) | cedagova/fastReader | Pending owner decision |
| One contract gate for both libraries (F011) | cedagova/fastReader | Pending owner decision |
| Tracked architecture map, docs that match the code, template hygiene (F012) | cedagova/fastReader, cedagova/.github-infra | Pending owner decision |
| Sync engine readable in parts (F013) | cedagova/fastReader | Recommended deferral |

## Limits and unknowns

- No emulator run, no release (R8) build and no cold build were performed;
  the gates were run once on a warm cache.
- Engine purity (F004) is proven by imports, not by a trial compile outside
  `:app`; the general-versus-specific split in F003 is a classification, not a
  trial extraction.
- The Chunipers repositories were excluded: whether the pinned reader-api
  commit still matches stage, and whether the Reader client keeps Supabase,
  are unknown.
- Security of token and session handling was not re-audited (A83, #167, #168).

## Details

- [Technical report](report.md)
- [Dossier pull request and native independent review](https://github.com/cedagova/fastReader/pull/197)
