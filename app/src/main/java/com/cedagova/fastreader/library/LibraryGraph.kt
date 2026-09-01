package com.cedagova.fastreader.library

import android.content.Context
import com.cedagova.fastreader.library.saf.SafDocumentGateway
import com.cedagova.fastreader.library.store.CatalogCodec
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
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
    )
}
