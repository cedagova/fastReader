package com.cedagova.reader.library

// The documents the mock server answers with. Each one is written the way
// reader-api would write it, including the fields this module does not model,
// so "unknown keys are tolerated" is proved by the ordinary success paths
// rather than only by a special test.

const val BOOK_ID = "1f0f1c9e-6a3c-4f8a-9c2b-2f1c7d3e4a5b"

val LIBRARY_BODY = """
{
  "contract_version": "reader.v1",
  "request_id": "c0ffee00-0000-4000-8000-000000000001",
  "items": [
    {
      "book": {
        "id": "$BOOK_ID",
        "title": "Tractatus",
        "author": "Wittgenstein",
        "language": "de",
        "source_type": "upload",
        "metadata": {"isbn": "9780000000000"},
        "created_at": "2026-09-01T10:00:00Z",
        "updated_at": "2026-09-10T10:00:00Z",
        "shelf_hint": "a field this client does not model"
      },
      "assets": [
        {
          "asset_id": "2a1b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
          "book_id": "$BOOK_ID",
          "kind": "epub",
          "upload_status": "complete",
          "checksum": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
          "mime_type": "application/epub+zip",
          "original_file_name": "tractatus.epub",
          "size_bytes": 482913,
          "metadata": {},
          "created_at": "2026-09-01T10:00:00Z",
          "updated_at": "2026-09-01T10:05:00Z"
        }
      ],
      "cover_status": "covered",
      "status": "reading",
      "last_opened_at": "2026-09-13T21:00:00Z",
      "created_at": "2026-09-01T10:00:00Z",
      "updated_at": "2026-09-13T21:00:00Z"
    }
  ]
}
""".trimIndent()

val PROGRESS_BODY = """
{
  "contract_version": "reader.v1",
  "request_id": "c0ffee00-0000-4000-8000-000000000002",
  "progress": [
    {
      "book_id": "$BOOK_ID",
      "progress_percent": 42.5,
      "locator": {"schemaVersion": "reader.epub-locator.v1", "cfi": "/6/14!/4/2/2"},
      "chapter_title": "Preface",
      "updated_at": "2026-09-13T21:00:00Z"
    }
  ]
}
""".trimIndent()

/** One admitted result, with the canonical payload a caller adopts. */
fun mutationResult(
    idempotencyKey: String = "key-1",
    status: String = "applied",
    admission: String? = "accepted",
    extra: String = "",
) = """
{
  "idempotency_key": "$idempotencyKey",
  "resource_type": "library_item",
  "resource_id": "$BOOK_ID",
  "mutation_kind": "upsert",
  "status": "$status",
  "canonical_payload": {"status": "reading"},
  ${if (admission == null) """"server_admission": null, "revision": null, "cursor": null, "server_admitted_at": null""" else """"server_admission": "$admission", "revision": 7, "cursor": "1042", "server_admitted_at": "2026-09-14T09:00:00Z""""}$extra
}
""".trimIndent()

fun mutationsBody(vararg results: String) = """
{
  "contract_version": "reader.sync.v1",
  "publication_membership_version": "reader.publication-membership.v1",
  "activity_convergence_version": "reader.activity-convergence.v1",
  "request_id": "c0ffee00-0000-4000-8000-000000000003",
  "results": [${results.joinToString(",")}]
}
""".trimIndent()

fun deltasBody(
    status: String = "ok",
    rebootstrap: Boolean = false,
    changes: String = "",
    nextCursor: String? = "1042",
) = """
{
  "contract_version": "reader.sync.v1",
  "publication_membership_version": "reader.publication-membership.v1",
  "request_id": "c0ffee00-0000-4000-8000-000000000004",
  "status": "$status",
  "minimum_valid_cursor": "900",
  "latest_cursor": "1042",
  "next_cursor": ${nextCursor?.let { "\"$it\"" } ?: "null"},
  "has_more": false,
  "rebootstrap_required": $rebootstrap,
  "changes": [$changes]
}
""".trimIndent()

val DELTA_CHANGE = """
{
  "cursor": "1042",
  "resource_type": "library_item",
  "resource_id": "$BOOK_ID",
  "revision": 7,
  "kind": "upsert",
  "canonical_payload": {"status": "finished"},
  "server_admitted_at": "2026-09-14T09:00:00Z"
}
""".trimIndent()

fun capabilitiesBody(syncEntry: String?) = """
{
  "schemaVersion": "reader.capabilities.v1",
  "generatedAt": "2026-09-14T09:00:00Z",
  "freshUntil": "2026-09-14T09:05:00Z",
  "staleUntil": "2026-09-14T09:30:00Z",
  "compatibility": {"status": "compatible", "requestedVersion": "1.0.0", "minimumVersion": "1.0.0", "supportedMajor": 1},
  "capabilities": [
    {"key": "reader.ai-quota.v1", "availability": "available", "reason": "available",
     "quota": {"limit": 100, "used": 3, "remaining": 97, "resetsAt": "2026-09-15T00:00:00Z"}}${syncEntry?.let { ",\n    $it" } ?: ""}
  ]
}
""".trimIndent()

fun syncEntry(availability: String = "available", reason: String = "available") =
    """{"key": "reader.sync.v1", "availability": "$availability", "reason": "$reason", "quota": null, "actorState": {"books": 1}}"""
