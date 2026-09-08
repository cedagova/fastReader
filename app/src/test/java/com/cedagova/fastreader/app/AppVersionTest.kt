package com.cedagova.fastreader.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.settings.AppVersion
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-106's acceptance, asserted end to end at the cheapest layer that can carry
 * it: the number Settings shows is the number `version.properties` states.
 *
 * The chain has four links — `version.properties`, the Gradle `defaultConfig`,
 * the merged manifest, and the package manager — and only the last one is what a
 * reader sees. Reading the tracked file here and the installed package's
 * [AppVersion] there makes a break anywhere along it a red test rather than a
 * wrong string in About. `scripts/release.sh` asserts the same equality against
 * the signed APK and the release tag, which is the half this cannot reach.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric has no API 36 sandbox yet; the app's own compileSdk is unaffected.
@Config(sdk = [35])
class AppVersionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val declared = Properties().apply {
        repositoryFile("version.properties").inputStream().use { load(it) }
    }

    @Test
    fun `the version the app reports is the version the repository declares`() {
        val version = AppVersion.of(context)

        assertEquals(declared.getProperty("versionName").trim(), version.name)
        assertEquals(declared.getProperty("versionCode").trim().toLong(), version.code)
    }

    /**
     * The row shows both, so both have to be worth showing: an empty name or a
     * zero build number would render as "(build 0)" and still pass an equality
     * check against an equally broken properties file.
     */
    @Test
    fun `both halves of the version are real values`() {
        val version = AppVersion.of(context)

        assertFalse("versionName is blank", version.name.isBlank())
        assertTrue("versionCode is ${version.code}", version.code > 0)
    }

    /**
     * REQ-107's regression gate, not its proof.
     *
     * The proof that a device backup captures nothing is the emulator run with
     * the platform backup manager recorded in `docs/evidence/46/` — a manifest
     * cannot testify about itself. What this asserts is that the declaration is
     * still there: `FLAG_ALLOW_BACKUP` is read back through the package manager
     * from the *merged* manifest, so a library manifest that opts the app back
     * in fails here too.
     */
    @Test
    fun `the installed application does not participate in backup`() {
        val flags = context.applicationInfo.flags

        assertEquals(
            "FLAG_ALLOW_BACKUP is set on the merged manifest",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    /**
     * The device-to-device half of AD-11, which no public API reports back.
     *
     * `android:allowBackup="false"` does not stop the transfer an Android 12+
     * setup wizard performs; only `dataExtractionRules` does, and
     * [ApplicationInfo] exposes no accessor for it. Asserting on the source
     * manifest is a weaker instrument than the test above and is here for one
     * reason: deleting the attribute would otherwise be silent in every check
     * this repository runs.
     */
    @Test
    fun `the manifest keeps the device-to-device extraction rules`() {
        val manifest = repositoryFile("app/src/main/AndroidManifest.xml").readText()
        val rules = repositoryFile("app/src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(
            "the application element no longer points at the extraction rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "the application element no longer points at the API 26-30 rules",
            manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""),
        )
        assertFalse("something has been opted back in", rules.contains("<include"))
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue("$section is missing", rules.contains("<$section>"))
        }
        listOf("root", "device_root", "external").forEach { domain ->
            assertEquals(
                "the $domain domain should be excluded from both sections",
                2,
                Regex("""<exclude domain="$domain"\s*/>""").findAll(rules).count(),
            )
        }
    }
}
