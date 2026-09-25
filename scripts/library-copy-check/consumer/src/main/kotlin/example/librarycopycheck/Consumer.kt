package example.librarycopycheck

import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.ReaderLibraryOperations

/**
 * The host side of the copy check. It compiles against the copied libraries'
 * public surface the way a Reader client does, reaching :reader-auth's types
 * through :reader-library's `api` dependency.
 */
fun libraryOperations(auth: ReaderAuthClient): ReaderLibraryOperations = ReaderLibraryClient(auth.api)
