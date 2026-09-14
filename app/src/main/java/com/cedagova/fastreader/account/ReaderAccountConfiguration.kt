package com.cedagova.fastreader.account

import com.cedagova.fastreader.BuildConfig
import com.cedagova.reader.auth.ReaderAuthConfig

/**
 * FastReader's half of the library's first host requirement
 * (`reader-auth/CONTRACT.md`, "Host requirements"): the three public stage
 * values reach the module as one [ReaderAuthConfig], and a build without them
 * is *not configured* — the surface says so and nothing is called.
 *
 * The values travel `local.properties` (or the environment) → `BuildConfig`
 * → here; `app/build.gradle.kts` blanks any that is absent, which is the
 * hosted runner's situation on every push. They are public browser-runtime
 * values of the stage deployment, never a service key, and they are still
 * never committed: `ReaderAccountConfigTest` reads the tracked files for any
 * fragment of them.
 */
object ReaderAccountConfiguration {

    /** The `local.properties` keys, in the order the not-configured screen names them. */
    val PROPERTY_KEYS: List<String> = listOf(
        "reader.supabaseUrl",
        "reader.supabasePublishableKey",
        "reader.apiBaseUrl",
    )

    /** The three values as built in, with the library's `reader-android` 1.0.0 identity. */
    fun fromBuild(): ReaderAuthConfig = ReaderAuthConfig(
        supabaseUrl = BuildConfig.READER_SUPABASE_URL,
        publishableKey = BuildConfig.READER_SUPABASE_PUBLISHABLE_KEY,
        readerApiBaseUrl = BuildConfig.READER_API_BASE_URL,
    )

    /** Which of [PROPERTY_KEYS] are blank in [config]; empty when it is configured. */
    fun missingValues(config: ReaderAuthConfig): List<String> = buildList {
        if (config.supabaseUrl.isBlank()) add(PROPERTY_KEYS[0])
        if (config.publishableKey.isBlank()) add(PROPERTY_KEYS[1])
        if (config.readerApiBaseUrl.isBlank()) add(PROPERTY_KEYS[2])
    }
}
