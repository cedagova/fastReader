package com.cedagova.reader.auth.host

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.reader.auth.ReaderAuth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The host's obligations to `:reader-auth` (reader-auth/README.md), as a
 * regression gate. Modelled on FastReader's `AppVersionTest`: the merged
 * manifest is read back through the package manager where an API exists, and
 * the tracked source files are read where none does.
 *
 * This is the gate, not the proof: the runtime backup/transfer test with the
 * platform's backup manager belongs to #93, once there is a token store whose
 * absence from a backup can be observed.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric has no API 36+ sandbox yet; the host's compileSdk is unaffected.
@Config(sdk = [35])
class HostManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val domains = listOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    @Test
    fun `the host is its own package, not FastReader`() {
        assertEquals("com.cedagova.reader.auth.host", context.packageName)
        assertFalse(context.packageName.contains("fastreader"))
    }

    /**
     * `FLAG_ALLOW_BACKUP` is read from the *merged* manifest, so a library
     * manifest that tries to opt the host back in fails here too.
     */
    @Test
    fun `the installed host does not participate in backup`() {
        val flags = context.applicationInfo.flags

        assertEquals(
            "FLAG_ALLOW_BACKUP is set on the merged manifest",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    /**
     * The device-to-device half, which no public API reports back: both rule
     * files are referenced, every domain is excluded in both extraction
     * sections and in the API 26-30 file, and nothing is opted back in.
     */
    @Test
    fun `the manifest keeps the full-domain extraction rules`() {
        val manifest = hostFile("src/main/AndroidManifest.xml").readText()
        val rules = hostFile("src/main/res/xml/data_extraction_rules.xml").readText()
        val legacyRules = hostFile("src/main/res/xml/backup_rules.xml").readText()

        assertTrue(
            "the application element no longer points at the extraction rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "the application element no longer points at the API 26-30 rules",
            manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""),
        )
        assertTrue(
            "allowBackup is no longer declared false",
            manifest.contains("android:allowBackup=\"false\""),
        )
        assertFalse("something has been opted back in", rules.contains("<include"))
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue("$section is missing", rules.contains("<$section>"))
        }
        domains.forEach { domain ->
            assertEquals(
                "the $domain domain should be excluded from both extraction sections",
                2,
                Regex("""<exclude domain="$domain"\s*/>""").findAll(rules).count(),
            )
        }
        assertFalse("something has been opted back in", legacyRules.contains("<include"))
        domains.forEach { domain ->
            assertTrue(
                "the $domain domain should be excluded from the API 26-30 rules",
                legacyRules.contains("""<exclude domain="$domain" path="." />"""),
            )
        }
    }

    /**
     * `INTERNET` is declared by the library manifest and only there: the merged
     * package requests it although neither host manifest declares any
     * permission. `contains` rather than equality because test-only
     * dependencies merge their own manifests into the unit-test package
     * (androidx.test.core adds `REORDER_TASKS`); the release APK's badging is
     * where the exact runtime list is read (docs/evidence/92/).
     */
    @Test
    fun `the internet permission arrives from the library and nowhere else`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

        assertTrue("merged manifest requests $requested", ReaderAuth.REQUIRED_PERMISSION in requested)
        listOf("src/main/AndroidManifest.xml", "src/debug/AndroidManifest.xml").forEach { path ->
            assertFalse(
                "$path declares a permission of its own",
                hostFile(path).readText().contains("<uses-permission"),
            )
        }
    }

    /**
     * The cleartext allowance is confined to the debug source set and names the
     * emulator loopback only; the main manifest carries no configuration, and
     * no manifest in either module sets `usesCleartextTraffic`.
     */
    @Test
    fun `cleartext is a debug-only allowance for the emulator loopback`() {
        val mainManifest = hostFile("src/main/AndroidManifest.xml").readText()
        val debugManifest = hostFile("src/debug/AndroidManifest.xml").readText()
        val config = hostFile("src/debug/res/xml/network_security_config.xml").readText()

        assertFalse(
            "the main manifest references a network security configuration",
            mainManifest.contains("networkSecurityConfig"),
        )
        assertTrue(
            "the debug manifest no longer points at the configuration",
            debugManifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
        assertFalse(
            "a network security configuration exists outside the debug source set",
            hostFile("src/main").walkTopDown().any { it.name == "network_security_config.xml" },
        )
        assertTrue(
            "the base configuration must forbid cleartext explicitly",
            config.contains("<base-config cleartextTrafficPermitted=\"false\" />"),
        )
        val allowedDomains = Regex("""<domain[^>]*>([^<]+)</domain>""")
            .findAll(config)
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals(listOf("10.0.2.2"), allowedDomains)
        assertFalse("subdomains of the loopback make no sense", config.contains("includeSubdomains=\"true\""))
        assertEquals(
            "exactly one cleartext domain-config",
            1,
            Regex("""<domain-config cleartextTrafficPermitted="true">""").findAll(config).count(),
        )

        val manifests = listOf("reader-auth-host/src", "reader-auth/src")
            .flatMap { File(repositoryRoot(), it).walkTopDown().filter { f -> f.name == "AndroidManifest.xml" }.toList() }
        assertTrue("no manifests found", manifests.isNotEmpty())
        manifests.forEach { file ->
            assertFalse(
                "${file.relativeTo(repositoryRoot())} sets usesCleartextTraffic",
                file.readText().contains("usesCleartextTraffic"),
            )
        }
    }

    private fun hostFile(path: String): File =
        File(repositoryRoot(), "reader-auth-host/$path").also {
            check(it.exists()) { "reader-auth-host/$path is missing" }
        }
}
