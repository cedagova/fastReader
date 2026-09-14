package com.cedagova.reader.auth

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The library's one manifest obligation, asserted at the library layer.
 *
 * Robolectric runs this module's tests against the *merged* manifest of the
 * library's own test application, so the assertion is on what a host actually
 * receives from this module — not on the source file — and a host test can
 * then check the same permission arrived on its side (FastReader's
 * `ReaderAccountManifestTest`).
 */
@RunWith(AndroidJUnit4::class)
// Robolectric has no API 36+ sandbox yet; the module's compileSdk is unaffected.
@Config(sdk = [35])
class ReaderAuthManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Read back through the package manager, so it is the merge that is under
     * test. `contains` rather than equality: test-only dependencies merge their
     * own manifests into the unit-test package (androidx.test.core adds
     * `REORDER_TASKS`), and that is not the library's doing. Exactness is
     * asserted on the source manifest below.
     */
    @Test
    fun `the internet permission survives manifest merging`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

        assertTrue("merged manifest requests $requested", ReaderAuth.REQUIRED_PERMISSION in requested)
    }

    @Test
    fun `the library manifest declares the internet permission and nothing else`() {
        val manifest = libraryManifest().readText()
        val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(listOf(ReaderAuth.REQUIRED_PERMISSION), declared)
        assertFalse("a library must not set usesCleartextTraffic", manifest.contains("usesCleartextTraffic"))
        assertFalse("a library must not set allowBackup", manifest.contains("allowBackup"))
    }

    /** The tracked source manifest, found by walking up to the Gradle root. */
    private fun libraryManifest(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) {
                return File(candidate, "reader-auth/src/main/AndroidManifest.xml").also {
                    check(it.isFile) { "$it is missing" }
                }
            }
            candidate = candidate.parentFile
        }
        error("no settings.gradle.kts above ${File("").absoluteFile}")
    }

    @Test
    fun `the required permission is the platform internet permission`() {
        assertEquals("android.permission.INTERNET", ReaderAuth.REQUIRED_PERMISSION)
    }
}
