# `:reader-library` — the Reader account-library client

Typed, contract-tested access to the Reader API's account library, reading
progress and sync protocol, on top of the authenticated client `:reader-auth`
owns. Added by [#112](https://github.com/cedagova/fastReader/issues/112)
(LEAF701 of [#104](https://github.com/cedagova/fastReader/issues/104)).

## What it is

Five operations, and nothing else:

| Operation | Route |
| --- | --- |
| `library()` | `GET /v1/reader/library` |
| `progress()` | `GET /v1/reader/progress` |
| `applyMutations(…)` | `POST /v1/reader/sync/mutations` (1–50 envelopes) |
| `deltas(afterCursor, limit)` | `GET /v1/reader/sync/deltas` (limit ≤ 500) |
| `syncCapability()` | `GET /v1/reader/capabilities?clientVersion=…`, the `reader.sync.v1` entry |

`ReaderLibraryOperations` is the interface; `ReaderLibraryClient` is the one
implementation, constructed with the `ReaderApiClient` a `ReaderAuthClient`
exposes. There is no generic `call(path, body)` on it: every request this module
can send is one of the five above.

## What it is not

- It holds no session, no token, no refresh and no retry. All of that is
  `:reader-auth`'s, and tokens never leave that module.
- It defines no exception type. Every failure is an existing
  `ReaderAuthException` branch — `SignedOut`, `Forbidden`, `TryLater`,
  `NetworkUnavailable`, `ApiError` — thrown through unchanged.
- It depends on nothing under `:app`, declares no permission and adds no
  manifest component, so it stays liftable exactly as `:reader-auth` does.

Outcomes that are answers rather than failures stay answers: `replayed`,
`superseded`, `conflict`, `rejected`, `cursor_expired` and `cursor_invalid` are
typed results a caller reads, not exceptions it catches.

## The contract pin

`contracts/reader-api.openapi.json` is the published Reader API document at a
pinned identity, with its sha256 beside it; see
[contracts/PINNED.md](contracts/PINNED.md). The models are hand-written on the
existing kotlinx.serialization stack (owner decision P1, 2026-09-14) and
`ReaderLibraryContractTest` is the drift gate: it recomputes the digest and then
compares every field name, JSON type, enum member and required flag the models
use against the document's schemas, deriving the expectations from the models'
own serializer descriptors. Its negative case runs the same checker over
deliberately broken copies of four models, so the gate is known to fail when it
should.

Drift is a proposal to Chunipers (reader-api #511 / #512), never a local
workaround.

## Running its tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :reader-library:test
```

Every test runs against a Ktor mock engine driving a **real** `ReaderAuthClient`
— real bearer, real single-flight refresh, real 401/403/429/502 policy — so the
module is proved to inherit that behaviour rather than restate it. No network,
no device and no backend value is involved.
