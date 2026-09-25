# Implementation Plan: A197 outcomes: fastReader as the base for the Reader Android client

- Planning issue: https://github.com/cedagova/fastReader/issues/211
- Planning PR: https://github.com/cedagova/fastReader/pull/212
- Status: In progress
- Root classification: PLANNING-TODO
- Delivery topology: PLANNING-TODO
- Planner: Planning lead (Claude)
- Started: 2026-09-25

<!-- For a product-definition input, record its exact PR URL and reviewed head
here and preserve its requirements, decisions, evidence, assumptions, brief,
and native product parent throughout the plan. -->

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `7978c711e3f207ac6c727f6479cf61c87cdbdc63` |

## Preserved objective and boundaries

PLANNING-TODO

## Classification

PLANNING-TODO — classify the root and every descendant. Classification
deterministically selects the fast-leaf or full-publication path.

## Current-state evidence

PLANNING-TODO — cite only enough pinned evidence to support classification and
the selected direction.

## Selected implementation direction

PLANNING-TODO — describe system-level HOW, not files, algorithms, prototypes,
or a detailed test harness.

<!--
For DECOMPOSE, EFFORT, or INCREMENTAL, add the full-path sections required by the contract:
Architecture decisions; Execution graph and waves; Interfaces and ownership;
Risks and rabbit holes; Migration, rollout, recovery, and rollback.
Do not add them to a fast leaf merely to make the document longer.

When ROOT must preserve a native dependency on an independently planned root,
add the optional `External prerequisite manifest` level-two section before the
issue publication manifest. Use columns `Key | Blocked row | Title | Required
state | Issue | Planning plan`; keys are `EXT###`, Blocked row is `ROOT`, and
Required state is `CLOSED`. Omit the entire section otherwise. External issues
are descriptors only and never become publication rows or children.

When an existing outcome must participate fully in this plan but keep an
independent native parent—or remain intentionally parentless—add the optional
`Retained outcome manifest` level-two section. Use columns `Key | Issue |
Native parent`. `Key` must identify that same GROUP or LEAF in the issue
publication manifest; use the exact native-parent issue URL or `None`. The
publication row's `Parent` remains its logical execution parent. Add the
contract's exact mirrored retention metadata to the child and direct-child
registry line to the logical parent. Omit the section when normal native
parenting is correct.

When this plan roots a former DEFERRED increment and must preserve its one
completed immediate predecessor from the exact approved outer INCREMENTAL plan,
add a separate `Inherited predecessor manifest` level-two section. Use columns
`Key | Blocked row | Title | Required state | Issue | Outer root | Outer
planning plan | Planning head`; use one `INH###` key, `ROOT`, `CLOSED`, and the
approved full lowercase outer-plan SHA. This edge must already exist and is
read-only. Do not use this descriptor for an ordinary self-rooted external
prerequisite or as an exception flag on `EXT###`.
-->

## Issue publication manifest

Replace or extend this table. Keys are stable within this plan. Use `Pending`
for unpublished issues during review. `Delivery` is `None` except on executable
GROUP increments and TRACKING roots. `Blocked by` contains comma-separated keys
or `None`.

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | A197 outcomes: fastReader as the base for the Reader Android client | None | None | https://github.com/cedagova/fastReader/issues/211 |

## Acceptance coverage

PLANNING-TODO — map every root acceptance condition to one or more manifest
keys and call out gaps or overlaps.

## Validation and feedback

PLANNING-TODO

## Assumptions and open questions

### Owner decision brief (pending)

**Problem.** Three accepted outcomes cannot be planned without product-level
choices the audit left to the owner: how the future Reader Android client takes
the libraries (F010, which also decides how F002 ships test doubles), whether the
general account logic becomes a library or a documented pattern (F003), and
whether RSVP is part of the reusable engine (F004).

**Facts (pinned `cedagova/fastReader@7978c71`).** The repository is public, so a
git-based dependency needs no credential. No Reader Android repository exists
yet. Both libraries are consumed as `project(...)` and depend on root catalog
aliases; `:reader-library` has no keep rules of its own. `epub/` (1,392 lines)
and `content/` (2,181) are Android-free; `timing/` (442) is RSVP only; `content/`
also holds the RSVP tokenizer. **Assumptions:** the Reader client talks to the
same Supabase-backed reader-api; the Reader client lives in the Chunipers org.

**D1 — how the Reader client takes the libraries (F010, scopes F002/F011).**
- A (recommended) Source copy: the Reader client copies the library directories
  at a tagged version; each library gets a version and CHANGELOG so later fixes
  can be ported. Cheapest, no cross-org dependency; copies can drift. Reversible
  (the self-contained build it needs is also B's prerequisite).
- B Git submodule + included build of this public repo: the client pins a commit
  and stays byte-identical, but a work repo depends on a personal repo.
- C Published Maven artifact: real versions, but a registry, signing/credentials
  and a publish job for two libraries with one consumer.

**D2 — general account logic (F003).**
- A (recommended) New library module (e.g. `:reader-account`) that FastReader
  consumes; one tested copy, one error mapping, copied like the other libraries.
- B Keep it in `:app` and document it as the reference pattern to copy.

**D3 — should the Reader client be able to reuse RSVP (F004)?**
- A (recommended) Yes: move `epub/`, `content/` and `timing/` unchanged into
  engine modules. Pure move, lowest risk; a client that skips RSVP ignores it.
- B Not now: move `epub/` and `content/` unchanged; `timing/` stays in `:app`.
- C No: also split the tokenizer out of `content/` and keep it in `:app`
  (pipeline surgery, higher risk, same acceptance).

**Blocks:** F002, F003, F004, F010, F011 leaves and the delivery order.
**Resuming reply:** e.g. `D1 A, D2 A, D3 A`.

### Planner decisions (reversible, recorded here)

- F001: wrap `UserSession`/`JsonObject`/Ktor types behind library-owned types
  rather than exposing them as `api`; there is no external consumer yet.
- F008: no dependency-update automation is added (owner preference not stated).
- F012: historical evidence is marked historical in place, not deleted
  (deleting from `main` does not shrink history).

## Satisfaction proof

PLANNING-TODO — for `ALREADY_SATISFIED`, include the three required disposition
lines and map every acceptance condition to pinned evidence and concrete
verification. Otherwise state that implementation work remains.

## Publication verification

PLANNING-TODO — record structural validation and native GitHub graph
verification. Exact-head approval remains in the native PR review, not here.
