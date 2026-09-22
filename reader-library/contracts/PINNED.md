# The pinned Reader API contract

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

`ReaderLibraryContractTest` recomputes that digest on every run and then checks
every field name, JSON type, enum member and required flag this module sends or
reads against the document's schemas. That test — not a code generator — is the
drift gate for `:reader-library` (owner decision P1, 2026-09-14).

## Updating the pin

1. Re-extract the document at the new reader-api commit and overwrite
   `reader-api.openapi.json`.
2. Put the new digest in `reader-api.openapi.json.sha256` and the new commit sha
   in this file.
3. Run `./gradlew :reader-library:test`. Every shape the module uses that the
   new document changed fails loudly, with the schema and field named.

Drift is a proposal to Chunipers (reader-api #511 / #512), never a local
workaround: the models move to follow the published contract, and the contract
is never bent to follow the models.
