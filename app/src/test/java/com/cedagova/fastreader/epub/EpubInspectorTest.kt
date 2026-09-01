package com.cedagova.fastreader.epub

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubInspectorTest {

    private fun inspect(bytes: ByteArray): EpubInspection =
        EpubInspector.inspect { ByteArrayInputStream(bytes) }

    @Test
    fun `reads English metadata and cover`() {
        val result = inspect(EpubFixtures.validEpub()) as EpubInspection.Readable

        assertEquals("The Quiet Machine", result.metadata.title)
        assertEquals("Ada Fielding", result.metadata.author)
        assertEquals("en", result.metadata.language)
        assertEquals("urn:uuid:11111111-1111-1111-1111-111111111111", result.metadata.publicationId)
        assertEquals("image/png", result.cover?.mediaType)
        assertTrue(EpubFixtures.TINY_PNG.contentEquals(result.cover?.bytes))
    }

    @Test
    fun `reads Spanish metadata without mojibake`() {
        val result = inspect(EpubFixtures.spanishEpub()) as EpubInspection.Readable

        assertEquals("¿Quién teme a la máquina?", result.metadata.title)
        assertEquals("José Ramírez Ñuño", result.metadata.author)
        assertEquals("es", result.metadata.language)
    }

    @Test
    fun `a book without a cover is still readable`() {
        val result = inspect(EpubFixtures.validEpub(withCover = false)) as EpubInspection.Readable

        assertNull(result.cover)
    }

    @Test
    fun `resolves an EPUB 2 cover declared through meta name cover`() {
        val result = inspect(EpubFixtures.epub2CoverEpub()) as EpubInspection.Readable

        assertEquals("Old School", result.metadata.title)
        assertEquals("Miriam Vance", result.metadata.author)
        // The href is percent-encoded and points at a file whose name contains a space.
        assertTrue(EpubFixtures.TINY_PNG.contentEquals(result.cover?.bytes))
    }

    @Test
    fun `rejects an encrypted book as DRM protected`() {
        val result = inspect(EpubFixtures.drmProtectedEpub()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.DRM_PROTECTED, result.reason)
        assertTrue(result.detail.contains("aes256"))
    }

    @Test
    fun `rejects an Adobe rights file as DRM protected`() {
        val result = inspect(EpubFixtures.adobeRightsEpub()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.DRM_PROTECTED, result.reason)
    }

    @Test
    fun `font obfuscation is not DRM`() {
        val result = inspect(EpubFixtures.fontObfuscatedEpub())

        assertTrue("expected a readable book, got $result", result is EpubInspection.Readable)
    }

    @Test
    fun `rejects bytes that are not an archive`() {
        val result = inspect(EpubFixtures.notAZip()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.CORRUPT_ARCHIVE, result.reason)
    }

    @Test
    fun `rejects a truncated archive`() {
        val result = inspect(EpubFixtures.truncatedZip()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.CORRUPT_ARCHIVE, result.reason)
    }

    @Test
    fun `rejects a zip with no EPUB container`() {
        val result = inspect(EpubFixtures.zipWithoutContainer()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.INVALID_STRUCTURE, result.reason)
        assertTrue(result.detail.contains("container.xml"))
    }

    @Test
    fun `rejects a container pointing at a missing package document`() {
        val result = inspect(EpubFixtures.missingPackageDocument()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.INVALID_STRUCTURE, result.reason)
    }

    @Test
    fun `rejects a book that declares no readable content`() {
        val result = inspect(EpubFixtures.emptySpineEpub()) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.INVALID_STRUCTURE, result.reason)
    }

    @Test
    fun `reports the reason distinctly for DRM and for damage`() {
        val drm = inspect(EpubFixtures.drmProtectedEpub()) as EpubInspection.Rejected
        val damaged = inspect(EpubFixtures.notAZip()) as EpubInspection.Rejected

        assertNotEquals(drm.reason, damaged.reason)
        assertNotEquals(drm.detail, damaged.detail)
    }

    @Test
    fun `rejected books still get a stable identity`() {
        val bytes = EpubFixtures.drmProtectedEpub()

        assertEquals(inspect(bytes).contentDigest, inspect(bytes).contentDigest)
        assertTrue(inspect(bytes).contentDigest!!.startsWith("sha256:"))
    }

    @Test
    fun `identity depends on content, not on the source`() {
        val first = inspect(EpubFixtures.validEpub()).contentDigest
        val same = inspect(EpubFixtures.validEpub()).contentDigest
        val other = inspect(EpubFixtures.spanishEpub()).contentDigest

        assertEquals(first, same)
        assertNotEquals(first, other)
    }

    @Test
    fun `an unreadable source is reported without an identity`() {
        val result = EpubInspector.inspect { throw java.io.IOException("permission revoked") }
            as EpubInspection.Rejected

        assertEquals(EpubRejectReason.UNREADABLE, result.reason)
        assertNull(result.contentDigest)
        assertTrue(result.detail.contains("permission revoked"))
    }

    @Test
    fun `does not resolve external XML entities`() {
        val hostile = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE container [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="&leak;" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        val bytes = zipOf("META-INF/container.xml" to hostile.toByteArray(Charsets.UTF_8))

        val result = inspect(bytes) as EpubInspection.Rejected

        assertEquals(EpubRejectReason.INVALID_STRUCTURE, result.reason)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
