package com.cedagova.fastreader.library

import android.content.Context
import com.cedagova.fastreader.external.ExternalOpenController
import com.cedagova.fastreader.library.saf.SafDocumentGateway
import com.cedagova.fastreader.library.store.CatalogCodec
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.settings.SharedPreferencesThemeMirror
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Wiring for the library layer. Plain constructor injection — the app is small
 * enough that a DI framework would cost more than it saves.
 */
class LibraryGraph(context: Context, scope: CoroutineScope) {

    private val applicationContext = context.applicationContext

    val gateway: DocumentGateway = SafDocumentGateway(applicationContext)

    val covers = CoverStore(File(applicationContext.filesDir, "covers"))

    private val store = FileCatalogStore(
        file = File(File(applicationContext.filesDir, "catalog"), "catalog.json"),
        codec = CatalogCodec(),
    )

    val repository = LibraryRepository(
        store = store,
        ingestor = CatalogIngestor(gateway, covers),
        gateway = gateway,
        covers = covers,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        themeMirror = SharedPreferencesThemeMirror(applicationContext),
    )

    /**
     * The one book handed over from outside the app, if any (REQ-103).
     *
     * Lives here, on the process-scoped graph, because that is exactly the
     * lifetime a session-only open is allowed: it survives a rotation and it does
     * not survive process death, after which the reader is back in the library
     * with no row for that book and its position kept (AD-9).
     */
    val external = ExternalOpenController(
        repository = repository,
        gateway = gateway,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
    )
}
