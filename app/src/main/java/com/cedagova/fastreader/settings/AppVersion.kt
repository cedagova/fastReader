package com.cedagova.fastreader.settings

import android.content.Context
import android.os.Build

/**
 * The version of the APK that is actually installed and running (REQ-106).
 *
 * ## Why the package manager rather than `BuildConfig`
 *
 * The requirement is that the version a reader sees equals the release they
 * installed. `BuildConfig` would report the version the *sources* were compiled
 * with, which is the same number in a clean build and quietly different in any
 * situation where it matters — a stale install, a sideloaded APK, an update that
 * did not actually replace the package. [PackageManager.getPackageInfo] reports
 * what the system has on disk under this package name, so the row is a statement
 * about the installed artifact.
 *
 * The chain the row therefore states is `version.properties` -> the build's
 * `versionName`/`versionCode` -> the merged manifest -> the installed package ->
 * this string, and `AppVersionTest` asserts both ends of it. `scripts/release.sh`
 * makes the same assertion against the signed APK and its tag.
 */
data class AppVersion(val name: String, val code: Long) {

    companion object {

        /** What the system reports for the installed FastReader package. */
        fun of(context: Context): AppVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            return AppVersion(name = info.versionName.orEmpty(), code = code)
        }
    }
}

/**
 * Where releases are published, and the only address FastReader ever hands out.
 *
 * The app holds no network permission: "Check for updates" starts an
 * `ACTION_VIEW` for this URL and the reader's browser makes the request
 * (REQ-106, REQ-303).
 */
const val RELEASES_URL = "https://github.com/cedagova/fastReader/releases"
