package com.cedagova.fastreader.library

/** Why a scan is running. The library shows a different loading message per trigger. */
enum class ScanTrigger { APP_OPEN, MANUAL_REFRESH, ADD_BOOKS, ADD_FOLDER }

/**
 * Ingestion state for the library screen (LEAF102) to render: a loading state
 * while a folder is scanned, and the result of the last scan afterwards.
 */
sealed interface IngestionState {

    data object Idle : IngestionState

    data class Scanning(
        val trigger: ScanTrigger,
        val processed: Int = 0,
        val total: Int = 0,
        val currentName: String? = null,
    ) : IngestionState

    data class Completed(
        val trigger: ScanTrigger,
        val added: Int,
        val updated: Int,
        val rejected: Int,
        val unavailable: Int,
        val finishedAtEpochMs: Long,
    ) : IngestionState

    /** The catalog could not be read or written; the message is safe to show to the reader. */
    data class Failed(val message: String) : IngestionState
}
