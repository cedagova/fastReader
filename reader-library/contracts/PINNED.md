# The pinned Reader API contract

`reader-api.openapi.json` in this directory is a byte-for-byte copy of

    Chunipers/reader-api@a517fc6db64560df309bce656ec4c34e0bc7e1bd
    contracts/reader-api.openapi.json

recorded with its sha256 in `reader-api.openapi.json.sha256`
(`a550abfd7046368681d02aec50e80e80e372b152416c744669dd72ee534c6f9e`).

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
