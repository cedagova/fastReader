package com.cedagova.fastreader.epub

import org.w3c.dom.Element

/**
 * Reads just enough of an EPUB to catalog it: identity, metadata, cover, and a
 * verdict on whether the book is a readable, DRM-free EPUB.
 *
 * Deliberately not a rendering engine — spine text extraction belongs to the
 * content pipeline (LEAF201). Nothing here throws on malformed input.
 */
object EpubInspector {

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val RIGHTS_PATH = "META-INF/rights.xml"

    private const val MAX_XML_BYTES = 8L * 1024 * 1024
    private const val MAX_COVER_BYTES = 16L * 1024 * 1024

    /**
     * Algorithms that mean "obfuscated embedded font", not DRM. An EPUB using only
     * these is a normal, readable book and must not be rejected.
     */
    private val FONT_OBFUSCATION_ALGORITHMS = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )

    fun inspect(source: EpubByteSource): EpubInspection {
        val scan = ZipReader.scan(
            source = source,
            collect = { name -> name.startsWith("META-INF/") || name.endsWith(".opf", ignoreCase = true) },
            maxEntryBytes = MAX_XML_BYTES,
        )

        scan.openFailure?.let { failure ->
            return EpubInspection.Rejected(null, EpubRejectReason.UNREADABLE, failure)
        }
        val digest = scan.digest?.let { "sha256:$it" }
            ?: return EpubInspection.Rejected(null, EpubRejectReason.UNREADABLE, "could not hash the file")

        scan.zipFailure?.let { failure ->
            return EpubInspection.Rejected(digest, EpubRejectReason.CORRUPT_ARCHIVE, "damaged archive: $failure")
        }
        if (scan.entryNames.isEmpty()) {
            return EpubInspection.Rejected(digest, EpubRejectReason.CORRUPT_ARCHIVE, "not a zip archive")
        }

        detectDrm(scan)?.let { detail ->
            return EpubInspection.Rejected(digest, EpubRejectReason.DRM_PROTECTED, detail)
        }

        val containerBytes = scan.collected[CONTAINER_PATH]
            ?: return EpubInspection.Rejected(
                digest,
                EpubRejectReason.INVALID_STRUCTURE,
                "missing $CONTAINER_PATH",
            )
        val opfPath = rootfilePath(containerBytes)
            ?: return EpubInspection.Rejected(
                digest,
                EpubRejectReason.INVALID_STRUCTURE,
                "no package document declared in $CONTAINER_PATH",
            )
        val opfBytes = scan.collected[opfPath]
            ?: ZipReader.readEntry(source, opfPath, MAX_XML_BYTES)
            ?: return EpubInspection.Rejected(
                digest,
                EpubRejectReason.INVALID_STRUCTURE,
                "package document $opfPath is missing",
            )

        val opf = OpfDocument.parse(opfPath, opfBytes)
            ?: return EpubInspection.Rejected(
                digest,
                EpubRejectReason.INVALID_STRUCTURE,
                "package document $opfPath is not readable",
            )
        if (opf.spinePaths.isEmpty()) {
            return EpubInspection.Rejected(
                digest,
                EpubRejectReason.INVALID_STRUCTURE,
                "the book declares no readable content",
            )
        }
        // A declared spine is not the same as content that is actually present.
        // An interrupted download keeps mimetype, container.xml and the OPF —
        // every one of them complete — and loses the text. Reject only when none
        // of the spine is in the archive, so an oddly written path costs an
        // acceptance rather than a wrongly rejected book.
        if (opf.spineCandidatePaths.none { it in scan.entryNameSet }) {
            return EpubInspection.Rejected(
                digest,
                EpubRejectReason.CORRUPT_ARCHIVE,
                "the book's content is missing from the file, which looks incomplete",
            )
        }

        val cover = opf.coverPath
            ?.let { path -> ZipReader.readEntry(source, path, MAX_COVER_BYTES)?.let { EpubCover(opf.coverMediaType, it) } }

        return EpubInspection.Readable(digest, opf.metadata, cover)
    }

    /** Returns a plain description of the protection found, or null when the book is unencrypted. */
    private fun detectDrm(scan: ZipScan): String? {
        if (scan.entryNameSet.contains(RIGHTS_PATH)) {
            return "the file carries DRM rights information ($RIGHTS_PATH)"
        }
        val encryption = scan.collected[ENCRYPTION_PATH]
            ?: return if (scan.entryNameSet.contains(ENCRYPTION_PATH)) {
                "the file declares encryption that could not be read"
            } else {
                null
            }

        val document = SafeXml.parse(encryption)
            ?: return "the file declares encryption that could not be read"
        val algorithms = document.descendants()
            .filter { it.hasLocalName("EncryptionMethod") }
            .mapNotNull { it.attr("Algorithm") }
            .toList()
        if (algorithms.isEmpty()) {
            return "the file declares encryption with no readable algorithm"
        }
        val protecting = algorithms.filterNot { it in FONT_OBFUSCATION_ALGORITHMS }
        // Only font obfuscation: a normal DRM-free book that happens to obfuscate its fonts.
        return protecting.firstOrNull()?.let { "the file is encrypted with $it" }
    }

    private fun rootfilePath(containerBytes: ByteArray): String? {
        val document = SafeXml.parse(containerBytes) ?: return null
        return document.descendants()
            .filter { it.hasLocalName("rootfile") }
            .mapNotNull { it.attr("full-path") }
            .mapNotNull { EpubPaths.resolve("", it) }
            .firstOrNull()
    }
}

/** The parts of the OPF package document this leaf needs. */
internal class OpfDocument(
    val metadata: EpubMetadata,
    val spinePaths: List<String>,
    /** Every form a spine item's zip entry could take: decoded, and as written. */
    val spineCandidatePaths: Set<String>,
    val coverPath: String?,
    val coverMediaType: String?,
    /** Spine items in reading order, with the manifest detail the content pipeline needs. */
    val spineItems: List<ManifestItem> = emptyList(),
    /** EPUB 3 navigation document, when the manifest declares one. */
    val navPath: String? = null,
    /** EPUB 2 NCX table of contents, when the spine or manifest points at one. */
    val ncxPath: String? = null,
) {
    companion object {

        fun parse(opfPath: String, bytes: ByteArray): OpfDocument? {
            val document = SafeXml.parse(bytes) ?: return null
            val root = document.documentElement ?: return null
            if (!root.hasLocalName("package")) return null

            val elements = root.descendants().toList()
            val manifestItems = elements
                .filter { it.hasLocalName("item") }
                .mapNotNull { element ->
                    val id = element.attr("id") ?: return@mapNotNull null
                    val href = element.attr("href") ?: return@mapNotNull null
                    val path = EpubPaths.resolve(opfPath, href) ?: return@mapNotNull null
                    ManifestItem(
                        id = id,
                        path = path,
                        rawPath = EpubPaths.resolve(opfPath, href, decode = false),
                        mediaType = element.attr("media-type"),
                        properties = element.attr("properties").orEmpty(),
                    )
                }
                .associateBy { it.id }

            val spineItems = elements
                .filter { it.hasLocalName("itemref") }
                .mapNotNull { it.attr("idref") }
                .mapNotNull { manifestItems[it] }
            val spinePaths = spineItems.map { it.path }

            val cover = resolveCover(root, elements, manifestItems)

            return OpfDocument(
                metadata = readMetadata(root, elements),
                spinePaths = spinePaths,
                spineCandidatePaths = spineItems.flatMap { listOfNotNull(it.path, it.rawPath) }.toSet(),
                coverPath = cover?.path,
                coverMediaType = cover?.mediaType,
                spineItems = spineItems,
                navPath = resolveNav(manifestItems),
                ncxPath = resolveNcx(elements, manifestItems),
            )
        }

        /** EPUB 3 marks its navigation document with `properties="nav"`. */
        private fun resolveNav(manifestItems: Map<String, ManifestItem>): String? =
            manifestItems.values
                .firstOrNull { item -> item.properties.split(Regex("\\s+")).any { it == "nav" } }
                ?.path

        /**
         * EPUB 2 points at its NCX from `<spine toc="...">`. Some books omit that
         * attribute and only declare the media type, so both are accepted.
         */
        private fun resolveNcx(elements: List<Element>, manifestItems: Map<String, ManifestItem>): String? {
            elements.firstOrNull { it.hasLocalName("spine") }
                ?.attr("toc")
                ?.let { manifestItems[it] }
                ?.let { return it.path }
            return manifestItems.values
                .firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) }
                ?.path
        }

        private fun readMetadata(root: Element, elements: List<Element>): EpubMetadata {
            val title = elements.firstText("title")
            val authors = elements
                .filter { it.hasLocalName("creator") }
                .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .take(3)
            val uniqueId = root.attr("unique-identifier")
            val identifiers = elements.filter { it.hasLocalName("identifier") }
            val publicationId = identifiers
                .firstOrNull { it.attr("id") == uniqueId }
                ?.textContent
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: identifiers.firstNotNullOfOrNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }

            return EpubMetadata(
                title = title,
                author = authors.joinToString(", ").takeIf { it.isNotEmpty() },
                language = elements.firstText("language"),
                publicationId = publicationId,
            )
        }

        private fun resolveCover(
            root: Element,
            elements: List<Element>,
            manifestItems: Map<String, ManifestItem>,
        ): ManifestItem? {
            // EPUB 3: manifest item marked with the cover-image property.
            manifestItems.values
                .firstOrNull { it.properties.split(Regex("\\s+")).any { token -> token == "cover-image" } }
                ?.let { return it }

            // EPUB 2: <meta name="cover" content="<manifest item id>"/>.
            elements
                .filter { it.hasLocalName("meta") && it.attr("name").equals("cover", ignoreCase = true) }
                .mapNotNull { it.attr("content") }
                .firstNotNullOfOrNull { manifestItems[it] }
                ?.let { return it }

            // Last resort: an image whose id or file name says "cover".
            return manifestItems.values.firstOrNull { item ->
                item.mediaType?.startsWith("image/") == true &&
                    (item.id.contains("cover", ignoreCase = true) || item.path.contains("cover", ignoreCase = true))
            }
        }

        private fun List<Element>.firstText(localName: String): String? =
            firstOrNull { it.hasLocalName(localName) }
                ?.textContent
                ?.trim()
                ?.takeIf(String::isNotEmpty)
    }
}

internal data class ManifestItem(
    val id: String,
    val path: String,
    /** The same href resolved without percent-decoding; equal to [path] unless the href was encoded. */
    val rawPath: String?,
    val mediaType: String?,
    val properties: String,
)
