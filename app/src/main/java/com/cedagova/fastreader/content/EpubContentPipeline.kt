package com.cedagova.fastreader.content

import com.cedagova.fastreader.epub.ArchiveOpen
import com.cedagova.fastreader.epub.EpubArchive
import com.cedagova.fastreader.epub.EpubArchives
import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.epub.EpubPaths
import com.cedagova.fastreader.epub.OpfDocument
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
 *
 * ## Cost of an open (REQ-110, AD-8)
 *
 * Two things used to make opening cost the *file* rather than the *book*, and
 * both are gone:
 *
 * 1. The first pass hashed every byte, because the pipeline derived the book's
 *    identity itself. It no longer derives it — [parse] takes the identity as an
 *    argument and stamps it onto the result unchanged.
 * 2. Both passes streamed the archive forward, so reaching a chapter meant
 *    reading past every picture before it. Entries now come from
 *    [EpubArchive], which seeks straight to them through the zip central
 *    directory whenever the source can seek.
 *
 * A source that cannot seek still works: it falls back to the forward pass and
 * costs what it always did. See [EpubByteSource.openChannel].
 */
class EpubContentPipeline(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Reads [source] into a token stream, identified by [identity].
     *
     * [identity] is an input, never derived here (AD-8): it becomes
     * [BookContent.bookDigest] verbatim, which is what stored positions are
     * matched against. A null identity — an external book whose digest is still
     * being computed off the open path (#44) — yields an empty digest, so no
     * stored position resolves onto this parse and the reader starts at the
     * beginning rather than somewhere arbitrary.
     */
    suspend fun parse(
        source: EpubByteSource,
        identity: BookIdentity? = null,
        onProgress: (ContentProgress) -> Unit = {},
    ): BookContentResult = withContext(dispatcher) {
        val archive = when (val opened = EpubArchives.open(source)) {
            is ArchiveOpen.Unopenable ->
                return@withContext BookContentResult.Failed(ContentFailureReason.UNREADABLE_SOURCE, opened.detail)

            is ArchiveOpen.Opened -> opened.archive
        }

        archive.use {
            parse(archive, identity, onProgress)
        }
    }

    private suspend fun parse(
        archive: EpubArchive,
        identity: BookIdentity?,
        onProgress: (ContentProgress) -> Unit,
    ): BookContentResult {
        val opf = when (val result = openPackage(archive)) {
            is PackageResult.Failed -> return BookContentResult.Failed(result.reason, result.detail)
            is PackageResult.Opened -> result.opf
        }

        val wanted = LinkedHashSet<String>()
        opf.spineItems.forEach { item ->
            wanted += item.path
            item.rawPath?.let { wanted += it }
        }
        opf.navPath?.let { wanted += it }
        opf.ncxPath?.let { wanted += it }

        val read = archive.read(select = { it in wanted }, maxBytes = MAX_CONTENT_BYTES)
        read.unreadable?.let {
            return BookContentResult.Failed(ContentFailureReason.UNREADABLE_SOURCE, it)
        }
        // Entries already read stay usable: a zip that fails partway through still
        // hands back the chapters it managed to deliver.
        val entries = HashMap(read.entries)

        val titles = readTitles(opf, entries)
        // Read before the spine loop, which consumes entries as it goes: a book
        // that lists its own navigation document in the spine would otherwise have
        // had it removed by the time the body-start declaration is wanted.
        val declaredBody = readDeclaredBodyStart(opf, entries)
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
                        reason = if (read.damage != null) GapReason.UNREADABLE else GapReason.MISSING_FROM_ARCHIVE,
                        detail = read.damage ?: "the file does not contain ${item.path}",
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
            return BookContentResult.Failed(
                ContentFailureReason.NO_READABLE_CONTENT,
                "the book's chapters contain no readable text",
            )
        }

        return BookContentResult.Parsed(
            BookContent(
                bookDigest = identity?.value.orEmpty(),
                language = opf.metadata.language,
                tokens = classify(tokens),
                chapters = chapters,
                gaps = gaps,
                frontMatter = FrontMatterDetector.detect(chapters, declaredBody?.first, declaredBody?.second),
            ),
        )
    }

    /**
     * Reads container and package document.
     *
     * Kept to its own read so the second one can ask for exactly the spine
     * entries; collecting every XHTML file speculatively would hold a whole book
     * of markup in memory beside the tokens built from it. Under the directory
     * strategy that is two seeks; under the streaming fallback it is the same two
     * passes the pipeline always made.
     */
    private fun openPackage(archive: EpubArchive): PackageResult {
        val read = archive.read(
            select = { name -> name == CONTAINER_PATH || name.endsWith(".opf", ignoreCase = true) },
            maxBytes = MAX_XML_BYTES,
        )
        read.unreadable?.let { return PackageResult.Failed(ContentFailureReason.UNREADABLE_SOURCE, it) }
        read.damage?.let {
            return PackageResult.Failed(ContentFailureReason.CORRUPT_ARCHIVE, "damaged archive: $it")
        }
        if (read.entryNames.isEmpty()) {
            return PackageResult.Failed(ContentFailureReason.CORRUPT_ARCHIVE, "the file is not a zip archive")
        }

        val container = read.entries[CONTAINER_PATH]
            ?: return PackageResult.Failed(ContentFailureReason.INVALID_STRUCTURE, "missing $CONTAINER_PATH")
        val opfPath = rootfilePath(container)
            ?: return PackageResult.Failed(
                ContentFailureReason.INVALID_STRUCTURE,
                "no package document declared in $CONTAINER_PATH",
            )
        // A package document whose name does not end in `.opf` is legal, so the
        // selective read above can miss it; ask for it by name once it is known.
        val opfBytes = read.entries[opfPath]
            ?: archive.read(select = { it == opfPath }, maxBytes = MAX_XML_BYTES).entries[opfPath]
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
        return PackageResult.Opened(opf)
    }

    private sealed interface PackageResult {
        data class Opened(val opf: OpfDocument) : PackageResult

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

    /**
     * Where the book itself says its body starts, if it says so at all (REQ-202).
     *
     * Both declarations come from bytes this parse already holds: the EPUB 3
     * navigation document was read for its chapter titles, and the EPUB 2 guide
     * is part of the package document. Detection therefore costs one scan of
     * markup already in memory and never another read of the archive, which is
     * what keeps the open cost where REQ-110 needs it.
     */
    private fun readDeclaredBodyStart(
        opf: OpfDocument,
        entries: Map<String, ByteArray>,
    ): Pair<String, FrontMatterSource>? {
        opf.navPath?.let { path ->
            entries[path]
                ?.let { TocReader.readBodyMatterLandmark(path, ContentCharsets.decode(it)) }
                ?.let { return it to FrontMatterSource.LANDMARKS }
        }
        opf.guideTextPath?.let { return it to FrontMatterSource.GUIDE }
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
