package com.cedagova.fastreader.content

import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.epub.EpubPaths
import com.cedagova.fastreader.epub.OpfDocument
import com.cedagova.fastreader.epub.ZipReader
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads one EPUB into its token stream.
 *
 * This is the reader's entry point to a book's text. It never throws on a bad
 * file: every way an EPUB can be unusable comes back as
 * [BookContentResult.Failed] with a reason the UI can phrase.
 *
 * **Partial books degrade, they do not fail.** Ingestion already rejects a
 * download that lost *all* of its content, but one interrupted midway through a
 * multi-chapter book keeps some chapters and loses others, and that book is still
 * worth reading. A spine item the archive does not contain becomes a
 * [SkipKind.MISSING_CONTENT] marker in book order plus a [ContentGap], so the
 * reader is told where the hole is instead of silently reading past it. Only a
 * book with no readable text at all fails.
 *
 * Parsing runs on [dispatcher] and reports [ContentProgress] per spine item, which
 * is what lets LEAF203 open a large book without blocking the main thread.
 */
class EpubContentPipeline(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun parse(
        source: EpubByteSource,
        onProgress: (ContentProgress) -> Unit = {},
    ): BookContentResult = withContext(dispatcher) {
        val opened = when (val result = openPackage(source)) {
            is PackageResult.Failed -> return@withContext BookContentResult.Failed(result.reason, result.detail)
            is PackageResult.Opened -> result
        }
        val opf = opened.opf

        val wanted = LinkedHashSet<String>()
        opf.spineItems.forEach { item ->
            wanted += item.path
            item.rawPath?.let { wanted += it }
        }
        opf.navPath?.let { wanted += it }
        opf.ncxPath?.let { wanted += it }

        val scan = ZipReader.scan(
            source = source,
            collect = { it in wanted },
            maxEntryBytes = MAX_CONTENT_BYTES,
            computeDigest = false,
        )
        scan.openFailure?.let {
            return@withContext BookContentResult.Failed(ContentFailureReason.UNREADABLE_SOURCE, it)
        }
        // Entries already read stay usable: a zip that fails partway through still
        // hands back the chapters it managed to deliver.
        val entries = HashMap(scan.collected)

        val titles = readTitles(opf, entries)
        val spineItems = opf.spineItems
        onProgress(ContentProgress(completedItems = 0, totalItems = spineItems.size))

        val state = Tokenizer.StreamState()
        val tokens = ArrayList<Token>()
        val chapters = ArrayList<Chapter>()
        val gaps = ArrayList<ContentGap>()

        spineItems.forEachIndexed { chapterIndex, item ->
            coroutineContext.ensureActive()
            val start = state.nextIndex
            val bytes = entries.remove(item.path) ?: item.rawPath?.let { entries.remove(it) }
            val blocks = bytes?.let { XhtmlExtractor.extract(ContentCharsets.decode(it)) }

            when {
                bytes == null -> {
                    gaps += ContentGap(
                        spinePath = item.path,
                        chapterIndex = chapterIndex,
                        reason = if (scan.zipFailure != null) GapReason.UNREADABLE else GapReason.MISSING_FROM_ARCHIVE,
                        detail = scan.zipFailure ?: "the file does not contain ${item.path}",
                    )
                    tokens += missingContentMarker(chapterIndex, state)
                }

                blocks.isNullOrEmpty() -> gaps += ContentGap(
                    spinePath = item.path,
                    chapterIndex = chapterIndex,
                    reason = GapReason.NO_TEXT,
                    detail = "${item.path} contains no readable text",
                )

                else -> tokens += Tokenizer.tokenize(blocks, chapterIndex, state)
            }

            chapters += Chapter(
                index = chapterIndex,
                title = titles[item.path]
                    ?: titles[item.rawPath]
                    ?: blocks?.firstHeading()
                    ?: fallbackTitle(chapterIndex),
                titleSource = when {
                    titles.containsKey(item.path) || titles.containsKey(item.rawPath) -> ChapterTitleSource.TOC
                    blocks?.firstHeading() != null -> ChapterTitleSource.HEADING
                    else -> ChapterTitleSource.FALLBACK
                },
                startTokenIndex = start,
                endTokenIndex = state.nextIndex,
                spinePath = item.path,
            )
            onProgress(ContentProgress(completedItems = chapterIndex + 1, totalItems = spineItems.size))
        }

        if (tokens.none { it is WordToken }) {
            return@withContext BookContentResult.Failed(
                ContentFailureReason.NO_READABLE_CONTENT,
                "the book's chapters contain no readable text",
            )
        }

        BookContentResult.Parsed(
            BookContent(
                bookDigest = opened.digest,
                language = opf.metadata.language,
                tokens = classify(tokens),
                chapters = chapters,
                gaps = gaps,
            ),
        )
    }

    /**
     * Reads container and package document.
     *
     * Kept to its own small zip pass so the second pass can ask for exactly the
     * spine entries; collecting every XHTML file speculatively would hold a whole
     * book of markup in memory beside the tokens built from it.
     */
    private fun openPackage(source: EpubByteSource): PackageResult {
        val scan = ZipReader.scan(
            source = source,
            collect = { name -> name == CONTAINER_PATH || name.endsWith(".opf", ignoreCase = true) },
            maxEntryBytes = MAX_XML_BYTES,
        )
        scan.openFailure?.let { return PackageResult.Failed(ContentFailureReason.UNREADABLE_SOURCE, it) }
        val digest = scan.digest
            ?: return PackageResult.Failed(ContentFailureReason.UNREADABLE_SOURCE, "the file could not be read")
        scan.zipFailure?.let {
            return PackageResult.Failed(ContentFailureReason.CORRUPT_ARCHIVE, "damaged archive: $it")
        }
        if (scan.entryNames.isEmpty()) {
            return PackageResult.Failed(ContentFailureReason.CORRUPT_ARCHIVE, "the file is not a zip archive")
        }

        val container = scan.collected[CONTAINER_PATH]
            ?: return PackageResult.Failed(ContentFailureReason.INVALID_STRUCTURE, "missing $CONTAINER_PATH")
        val opfPath = rootfilePath(container)
            ?: return PackageResult.Failed(
                ContentFailureReason.INVALID_STRUCTURE,
                "no package document declared in $CONTAINER_PATH",
            )
        val opfBytes = scan.collected[opfPath]
            ?: ZipReader.readEntry(source, opfPath, MAX_XML_BYTES)
            ?: return PackageResult.Failed(
                ContentFailureReason.INVALID_STRUCTURE,
                "package document $opfPath is missing",
            )
        val opf = OpfDocument.parse(opfPath, opfBytes)
            ?: return PackageResult.Failed(
                ContentFailureReason.INVALID_STRUCTURE,
                "package document $opfPath is not readable",
            )
        if (opf.spineItems.isEmpty()) {
            return PackageResult.Failed(
                ContentFailureReason.INVALID_STRUCTURE,
                "the book declares no readable content",
            )
        }
        return PackageResult.Opened("sha256:$digest", opf)
    }

    private sealed interface PackageResult {
        data class Opened(val digest: String, val opf: OpfDocument) : PackageResult

        data class Failed(val reason: ContentFailureReason, val detail: String) : PackageResult
    }

    private fun rootfilePath(containerBytes: ByteArray): String? {
        val markup = ContentCharsets.decode(containerBytes)
        for (event in MarkupScanner.scan(markup)) {
            if (event is MarkupEvent.Open && event.name == "rootfile") {
                val full = event.attribute("full-path") ?: continue
                EpubPaths.resolve("", full)?.let { return it }
            }
        }
        return null
    }

    private fun readTitles(opf: OpfDocument, entries: Map<String, ByteArray>): Map<String, String> {
        opf.navPath?.let { path ->
            entries[path]?.let { bytes ->
                val titles = TocReader.readNavigationDocument(path, ContentCharsets.decode(bytes))
                if (titles.isNotEmpty()) return titles
            }
        }
        opf.ncxPath?.let { path ->
            entries[path]?.let { bytes ->
                val titles = TocReader.readNcx(path, ContentCharsets.decode(bytes))
                if (titles.isNotEmpty()) return titles
            }
        }
        return emptyMap()
    }

    private fun missingContentMarker(chapterIndex: Int, state: Tokenizer.StreamState): SkipMarkerToken {
        state.paragraphIndex++
        state.sentenceIndex++
        return SkipMarkerToken(
            index = state.nextIndex++,
            kind = SkipKind.MISSING_CONTENT,
            chapterIndex = chapterIndex,
            paragraphIndex = state.paragraphIndex,
            sentenceIndex = state.sentenceIndex,
            label = XhtmlExtractor.MISSING_LABEL,
        )
    }

    /**
     * Second pass over the finished stream.
     *
     * Rarity is defined against the whole book, so it cannot be decided while the
     * book is still being read.
     */
    private fun classify(tokens: List<Token>): List<Token> {
        val counts = HashMap<String, Int>()
        for (token in tokens) {
            if (token is WordToken) {
                val key = WordClassifier.normalize(token.text)
                if (key.isNotEmpty()) counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return tokens.map { token ->
            if (token !is WordToken) {
                token
            } else {
                val key = WordClassifier.normalize(token.text)
                token.copy(classes = WordClassifier.classify(token.text, counts[key] ?: 1))
            }
        }
    }

    private fun List<ContentBlock>.firstHeading(): String? =
        firstOrNull { it is ContentBlock.Paragraph && it.isHeading }
            ?.let { (it as ContentBlock.Paragraph).text }
            ?.takeIf { it.isNotBlank() }

    private fun fallbackTitle(chapterIndex: Int): String = "Section ${chapterIndex + 1}"

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val MAX_XML_BYTES = 8L * 1024 * 1024
        const val MAX_CONTENT_BYTES = 16L * 1024 * 1024
    }
}
