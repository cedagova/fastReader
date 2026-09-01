package com.cedagova.fastreader.epub

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthetic EPUBs built in memory.
 *
 * Small and obviously fake on purpose: these exercise the shapes real books come
 * in (English and Spanish metadata, cover or none, DRM, damage) without shipping
 * copyrighted book files in the repository.
 */
object EpubFixtures {

    /** A 1x1 PNG, so a cover is a real decodable image rather than random bytes. */
    val TINY_PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    fun validEpub(
        title: String = "The Quiet Machine",
        author: String = "Ada Fielding",
        language: String = "en",
        identifier: String = "urn:uuid:11111111-1111-1111-1111-111111111111",
        withCover: Boolean = true,
        bodyText: String = "One word at a time.",
    ): ByteArray {
        val entries = mutableListOf(
            "META-INF/container.xml" to CONTAINER.toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to opf(title, author, language, identifier, withCover).toByteArray(Charsets.UTF_8),
            "OEBPS/chapter1.xhtml" to xhtml(bodyText).toByteArray(Charsets.UTF_8),
        )
        if (withCover) entries += "OEBPS/images/cover.png" to TINY_PNG
        return zip(entries)
    }

    /** A Spanish book: accents and inverted punctuation in title and author. */
    fun spanishEpub(withCover: Boolean = false): ByteArray = validEpub(
        title = "¿Quién teme a la máquina?",
        author = "José Ramírez Ñuño",
        language = "es",
        identifier = "urn:uuid:22222222-2222-2222-2222-222222222222",
        withCover = withCover,
        bodyText = "—¿Cómo estás? —preguntó él.",
    )

    /** Encrypted with a real cipher: DRM, and never to be unlocked. */
    fun drmProtectedEpub(): ByteArray = zip(
        baseEntries() + listOf("META-INF/encryption.xml" to encryption("http://www.w3.org/2001/04/xmlenc#aes256-cbc")),
    )

    /** Adobe ADEPT leaves a rights file behind. */
    fun adobeRightsEpub(): ByteArray = zip(
        baseEntries() + listOf(
            "META-INF/rights.xml" to """<licenseToken xmlns="http://ns.adobe.com/adept"/>""".toByteArray(Charsets.UTF_8),
        ),
    )

    /**
     * Encrypted only with the IDPF font-obfuscation algorithm. This is a normal,
     * readable, DRM-free book and must not be rejected.
     */
    fun fontObfuscatedEpub(): ByteArray = zip(
        baseEntries() + listOf("META-INF/encryption.xml" to encryption("http://www.idpf.org/2008/embedding")),
    )

    /** Bytes that are not a zip at all. */
    fun notAZip(): ByteArray = "this is plain text, not an archive".toByteArray(Charsets.UTF_8)

    /** A zip truncated part way through, as a half-copied download would be. */
    fun truncatedZip(): ByteArray {
        val whole = validEpub()
        return whole.copyOfRange(0, whole.size / 2)
    }

    /** A readable zip with no EPUB container. */
    fun zipWithoutContainer(): ByteArray = zip(listOf("readme.txt" to "hello".toByteArray(Charsets.UTF_8)))

    /** A container pointing at a package document that is not in the archive. */
    fun missingPackageDocument(): ByteArray = zip(
        listOf("META-INF/container.xml" to CONTAINER.toByteArray(Charsets.UTF_8)),
    )

    /** A structurally valid package document that declares no readable content. */
    fun emptySpineEpub(): ByteArray = zip(
        listOf(
            "META-INF/container.xml" to CONTAINER.toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:empty</dc:identifier>
    <dc:title>Nothing Inside</dc:title>
  </metadata>
  <manifest/>
  <spine/>
</package>""".toByteArray(Charsets.UTF_8),
        ),
    )

    /** An EPUB 2 style cover declared through `<meta name="cover">`. */
    fun epub2CoverEpub(): ByteArray = zip(
        listOf(
            "META-INF/container.xml" to CONTAINER.toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="bookid">isbn:9780000000001</dc:identifier>
    <dc:title>Old School</dc:title>
    <dc:creator opf:role="aut">Miriam Vance</dc:creator>
    <dc:language>en</dc:language>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="cover-img" href="art/front%20cover.png" media-type="image/png"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""".toByteArray(Charsets.UTF_8),
            "OEBPS/art/front cover.png" to TINY_PNG,
            "OEBPS/chapter1.xhtml" to xhtml("Body").toByteArray(Charsets.UTF_8),
        ),
    )

    private fun baseEntries(): List<Pair<String, ByteArray>> = listOf(
        "META-INF/container.xml" to CONTAINER.toByteArray(Charsets.UTF_8),
        "OEBPS/content.opf" to opf("Locked Book", "Unknown", "en", "urn:uuid:locked", withCover = false)
            .toByteArray(Charsets.UTF_8),
        "OEBPS/chapter1.xhtml" to xhtml("Body").toByteArray(Charsets.UTF_8),
    )

    private fun encryption(algorithm: String): ByteArray = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData>
    <enc:EncryptionMethod Algorithm="$algorithm"/>
    <enc:CipherData><enc:CipherReference URI="OEBPS/chapter1.xhtml"/></enc:CipherData>
  </enc:EncryptedData>
</encryption>""".toByteArray(Charsets.UTF_8)

    private fun opf(
        title: String,
        author: String,
        language: String,
        identifier: String,
        withCover: Boolean,
    ): String {
        val coverItem = if (withCover) {
            """<item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>"""
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">${identifier.escapeXml()}</dc:identifier>
    <dc:title>${title.escapeXml()}</dc:title>
    <dc:creator>${author.escapeXml()}</dc:creator>
    <dc:language>${language.escapeXml()}</dc:language>
  </metadata>
  <manifest>
    $coverItem
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""
    }

    private fun xhtml(body: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>${body.escapeXml()}</p></body></html>"""

    private fun String.escapeXml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val stored = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            }
            zip.putNextEntry(stored)
            zip.write(mimetype)
            zip.closeEntry()

            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
