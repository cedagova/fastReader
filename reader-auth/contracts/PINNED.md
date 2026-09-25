# The pinned Reader API contract

This directory holds the **one** reader-api contract document for this
repository. Both libraries are gated against it: `:reader-auth` owns it — it is
the lowest layer, holds the one reader-api client, and every copy of
`:reader-library` brings it along — and `:reader-library` reads it from here
(#208, A197-F011).

`reader-api.openapi.json` in this directory is a byte-for-byte copy of

    Chunipers/reader-api@909174aff6a380514da7b81263d69a4e653cfe76
    contracts/reader-api.openapi.json

recorded with its sha256 in `reader-api.openapi.json.sha256`
(`e2c184dbd51d0e3f542d73d69e56a193300615de604486615b254911b67ade90`).

That commit is the one stage runs, not merely reader-api's newest (#139,
re-pinned 2026-09-22 from `a517fc6d` / `a550abfd…6f9e`): Render's live deploy of
`reader-api-stage` (`dep-dapc8grm8hqs7399il7g`, live 2026-09-22T18:10Z) runs
image `ghcr.io/chunipers/reader-api@sha256:661308e5…1d95`, which reader-api's
Stage Build and Deploy run 35765056413 built and pushed as
`reader-api:909174af…` and then deployed (GitHub deployment 6597482143,
`stage`, success). The OpenAPI document is byte-identical at `f58bfb95` (PR
Chunipers/reader-api#517, the `locator` → `location` cutover); `909174af`
changes no contract file.

## The gate

One checker, `ReaderApiContract`
(`reader-auth/src/contractTest/kotlin/…/contract/ReaderApiContract.kt`), is
compiled into both libraries' unit tests. It recomputes the digest and then
compares every field name, JSON type, nullability, enum member and required flag
a model sends or reads with the document's schema, deriving the expectations
from the model's own serializer descriptor. That test — not a code generator —
is the drift gate (owner decision P1, 2026-09-14):

| Library | Test | What it gates |
| --- | --- | --- |
| `:reader-auth` | `ReaderAuthContractTest` | `PreAuthDocument` (a lenient read subset of `ReaderPreAuthResponse`), `ReaderProfileUpdate` (`PutReaderProfileRequest`), the error body fields, the pre-auth / capabilities / profile routes, and `CONTRACT.md`'s reader-api citations |
| `:reader-library` | `ReaderLibraryContractTest` | every model under `library/model/`, the library, progress, sync, import and asset routes, and the constants it sends |

## Updating the pin

The one procedure, for both libraries:

1. Re-extract the document at the new reader-api commit and overwrite
   `reader-api.openapi.json`:
   `git -C <reader-api checkout> show <commit>:contracts/reader-api.openapi.json > reader-auth/contracts/reader-api.openapi.json`.
2. Record the new identity: the digest in `reader-api.openapi.json.sha256`
   (`shasum -a 256 reader-api.openapi.json`), and the commit and digest in this
   file and in `ReaderApiContract.PINNED_COMMIT` / `PINNED_SHA256`. Move every
   other citation of the old commit with it — `rg -n <old commit>` over
   `reader-auth/CONTRACT.md`, `README.md` and `docs/privacy-statement.md`
   finds them; `ReaderAuthContractTest` fails while `CONTRACT.md` cites any
   other reader-api commit.
3. Run `./gradlew :reader-auth:test :reader-library:test`. Every shape either
   library uses that the new document changed fails loudly, with the schema and
   field named.

Drift is a proposal to Chunipers (reader-api #511 / #512), never a local
workaround: the models move to follow the published contract, and the contract
is never bent to follow the models.
