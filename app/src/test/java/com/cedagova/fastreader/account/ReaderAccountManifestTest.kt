package com.cedagova.fastreader.account

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.app.repositoryFile
import com.cedagova.fastreader.app.repositoryRoot
import com.cedagova.reader.auth.ReaderAuth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FastReader's manifest obligations as the host of `:reader-auth`
 * (`reader-auth/README.md`, "What every host must declare for itself"),
 * moved from the retired `:reader-auth-host` in #100 and pointed at this
 * app's tree. The merged manifest is read back through the package manager
 * where an API exists, and the tracked source files are read where none does.
 *
 * This is the gate, not the proof: the runtime backup and transfer run with
 * the platform's backup manager is `docs/evidence/46/` for the app's own data
 * and the owner-relayed run in `docs/evidence/100/` for the live session
 * store; the release APK's exact permission list is read by
 * `scripts/release.sh`. `AppVersionTest` already pins the nine-domain rules
 * for the app's own reasons; they are pinned again here as the library's
 * requirement, so removing either reason does not silently drop the rule.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReaderAccountManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val domains = listOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    /** Host requirement 4: the application id is FastReader's, and no host package exists any more. */
    @Test
    fun `the host is FastReader under its own application id`() {
        assertEquals("com.cedagova.fastreader", context.packageName)
        assertFalse("the proving-ground host was retired in #100", File(repositoryRoot(), "reader-auth-host").exists())
        val settings = repositoryFile("settings.gradle.kts").readText()
        val included = Regex("""include\("([^"]+)"\)""").findAll(settings).map { it.groupValues[1] }.toList()
        // The exact module list, so a module reappearing (or the retired host
        // returning) is a deliberate edit here. :reader-library joined in #112,
        // :reader-engine in #201, :reader-account in #200.
        assertEquals(listOf(":app", ":reader-auth", ":reader-library", ":reader-engine", ":reader-account"), included)
    }

    /**
     * `FLAG_ALLOW_BACKUP` is read from the *merged* manifest, so a library
     * manifest that tries to opt the app back in fails here too.
     */
    @Test
    fun `the installed app does not participate in backup`() {
        val flags = context.applicationInfo.flags

        assertEquals(
            "FLAG_ALLOW_BACKUP is set on the merged manifest",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    /**
     * Host requirement 2: both rule files are referenced, every domain is
     * excluded in both extraction sections and in the API 26-30 file, and
     * nothing is opted back in.
     */
    @Test
    fun `the manifest keeps the full-domain extraction rules`() {
        val manifest = appFile("src/main/AndroidManifest.xml").readText()
        val rules = appFile("src/main/res/xml/data_extraction_rules.xml").readText()
        val legacyRules = appFile("src/main/res/xml/backup_rules.xml").readText()

        assertTrue(
            "the application element no longer points at the extraction rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "the application element no longer points at the API 26-30 rules",
            manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""),
        )
        assertTrue("allowBackup is no longer declared false", manifest.contains("android:allowBackup=\"false\""))
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
     * package requests it although no manifest under `app/` declares any
     * permission. `contains` rather than equality because test-only
     * dependencies merge their own manifests into the unit-test package
     * (androidx.test.core adds `REORDER_TASKS`); the release APK's badging is
     * where the exact runtime list is read, by `scripts/release.sh`.
     */
    @Test
    fun `the internet permission arrives from the library and nowhere else`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

        assertTrue("merged manifest requests $requested", ReaderAuth.REQUIRED_PERMISSION in requested)
        val manifests = appFile("src").walkTopDown().filter { it.name == "AndroidManifest.xml" }.toList()
        assertTrue("no manifests found under app/src", manifests.isNotEmpty())
        manifests.forEach { file ->
            assertFalse(
                "${file.relativeTo(repositoryRoot())} declares a permission of its own",
                file.readText().contains("<uses-permission"),
            )
        }
    }

    /**
     * Host requirement 3: the cleartext allowance is confined to the debug
     * source set and names the emulator loopback only; the main manifest
     * carries no configuration, and no manifest of the app or the library sets
     * `usesCleartextTraffic`.
     */
    @Test
    fun `cleartext is a debug-only allowance for the emulator loopback`() {
        val mainManifest = appFile("src/main/AndroidManifest.xml").readText()
        val debugManifest = appFile("src/debug/AndroidManifest.xml").readText()
        val config = appFile("src/debug/res/xml/network_security_config.xml").readText()

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
            appFile("src").walkTopDown().any {
                it.name == "network_security_config.xml" &&
                    !it.path.contains("/src/debug/")
            },
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

        val manifests = listOf("app/src", "reader-auth/src")
            .flatMap {
                File(repositoryRoot(), it).walkTopDown().filter { f -> f.name == "AndroidManifest.xml" }.toList()
            }
        assertTrue("no manifests found", manifests.isNotEmpty())
        manifests.forEach { file ->
            assertFalse(
                "${file.relativeTo(repositoryRoot())} sets usesCleartextTraffic",
                file.readText().contains("usesCleartextTraffic"),
            )
        }
    }

    /** REQ-414's last clause: the library depends on nothing under `:app`. */
    @Test
    fun `the library depends on no project module`() {
        val build = repositoryFile("reader-auth/build.gradle.kts").readText()

        assertFalse("reader-auth/build.gradle.kts declares a project dependency", build.contains("project("))
        val sources = File(repositoryRoot(), "reader-auth/src").walkTopDown().filter {
            it.isFile && it.extension == "kt"
        }
        assertTrue(sources.none { it.readText().contains("com.cedagova.fastreader") })
    }

    private fun appFile(path: String): File = File(repositoryRoot(), "app/$path").also {
        check(it.exists()) { "app/$path is missing" }
    }
}
