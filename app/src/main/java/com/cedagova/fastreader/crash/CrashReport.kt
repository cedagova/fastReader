package com.cedagova.fastreader.crash

/**
 * The plain-text crash report, and the redaction that makes it shareable
 * (REQ-207, REQ-303, AD-14).
 *
 * ## What the report is allowed to contain
 *
 * Four things, and nothing else: the app version, the device model, the Android
 * version, and where the code stopped. Everything a reader would recognise as
 * *theirs* — a book's words, a book's title, a file name, a folder, a content
 * URI — is absent by construction rather than by filtering.
 *
 * ## Why exception messages are dropped entirely
 *
 * A message is the one part of a stack trace that carries free text a developer
 * wrote at the throw site, and free text is exactly where a reader's library
 * leaks. The platform does it too, unprompted:
 * `FileNotFoundException`'s message *is* the path. So does this app, the moment
 * a future change writes `error("no spine item in ${'$'}{book.title}")`.
 *
 * Scrubbing those messages would mean guessing which of them are safe, and a
 * guess that is wrong once has already published the thing it was guarding. So
 * no message reaches the report at all: only the exception's type and its call
 * sites, which come from compiled code and can name nothing but this app and the
 * platform. That is a deliberate loss of detail, and [MESSAGE_NOTE] says so
 * inside the report so the person reading it knows why it looks bare.
 *
 * ## Why the shape is closed rather than open
 *
 * Every line the renderer can emit matches one of a handful of fixed forms, and
 * every value interpolated into one is passed through [safeField] or
 * [safeSymbol] first. A path needs `/` or `\`, and neither survives either
 * filter — which turns "contains no path" into something a test can assert about
 * the whole document instead of about the cases someone thought of.
 * `CrashReportTest` asserts both halves: the shape of every line, and the
 * absence of a corpus of book text, titles, file names and paths fed in through
 * messages, causes and suppressed exceptions.
 *
 * The report is deliberately not localised. It is read by whoever fixes the
 * crash, not by the reader who shares it, and a bug report that changes wording
 * with the device's language is harder to search, not friendlier.
 */

/** The facts about this build and device that a report carries (AD-14). */
data class CrashFacts(
    val versionName: String,
    val versionCode: Long,
    val deviceModel: String,
    val androidRelease: String,
    val androidSdk: Int,
)

/** The report's first line, and the marker that identifies one on sight. */
const val CRASH_REPORT_HEADING: String = "FastReader crash report"

/** Why the report carries no exception messages, stated inside the report. */
const val MESSAGE_NOTE: String =
    "Exception messages are left out on purpose: they can quote a book, " +
        "a file name or a path. Types and call sites are kept."

/** Beyond this the chain is noise; a cause loop is also bounded by [seen]. */
private const val MAX_THROWABLES = 12

/** Enough frames to find any call site in this app, then a count of the rest. */
private const val MAX_FRAMES = 40

/** No vendor string is longer than this, and nothing useful is truncated. */
private const val MAX_FIELD = 80

/** No symbol from real compiled code is longer than this. */
private const val MAX_SYMBOL = 200

/** What survives [safeField]: no separator any path could be built from. */
private val FIELD_ALLOWED = Regex("[A-Za-z0-9 ()._+-]")

/** What survives [safeSymbol]: the alphabet of a JVM class or method name. */
private val SYMBOL_ALLOWED = Regex("[A-Za-z0-9_$.<>-]")

/** Stands in for a field the device reported as empty or unprintable. */
private const val UNKNOWN = "unknown"

/**
 * Renders one crash as the text that is stored and, if the reader chooses,
 * shared.
 *
 * Never throws: it runs inside the uncaught-exception handler, where a second
 * failure would replace a real crash with this one.
 */
fun crashReportText(facts: CrashFacts, error: Throwable): String = buildString {
    appendLine(CRASH_REPORT_HEADING)
    appendLine("Version: ${safeField(facts.versionName)} (${facts.versionCode})")
    appendLine("Device: ${safeField(facts.deviceModel)}")
    appendLine("Android: ${safeField(facts.androidRelease)} (API ${facts.androidSdk})")
    appendLine(MESSAGE_NOTE)
    appendLine()
    appendChain(error)
}.trimEnd('\n')

/**
 * Walks the thrown exception, its causes and everything suppressed along the
 * way, depth first and each throwable once.
 *
 * The identity set is not defensive tidiness: `initCause` accepts a cycle, and
 * `printStackTrace` itself carries the same guard.
 */
private fun StringBuilder.appendChain(root: Throwable) {
    val seen = mutableListOf<Throwable>()
    fun visit(error: Throwable, relation: String) {
        if (seen.size >= MAX_THROWABLES) return
        if (seen.any { it === error }) return
        seen += error
        appendLine("$relation: ${safeSymbol(error.javaClass.name)}")
        appendFrames(error.stackTrace)
        error.suppressed.forEach { visit(it, "Suppressed by") }
        error.cause?.let { visit(it, "Caused by") }
    }
    visit(root, "Thrown")
}

/** The call sites, capped, with the count of whatever the cap left out. */
private fun StringBuilder.appendFrames(frames: Array<StackTraceElement>) {
    frames.take(MAX_FRAMES).forEach { frame ->
        val symbol = safeSymbol("${frame.className}.${frame.methodName}")
        val line = when {
            frame.isNativeMethod -> ":native"
            frame.lineNumber > 0 -> ":${frame.lineNumber}"
            else -> ""
        }
        appendLine("    at $symbol$line")
    }
    val dropped = frames.size - MAX_FRAMES
    if (dropped > 0) appendLine("    ... $dropped more frames")
}

/**
 * A device- or build-supplied string reduced to characters that cannot spell a
 * path or a URI.
 *
 * `Build.MODEL` is whatever a vendor put in a property file, so it is treated as
 * untrusted text even though it says nothing about the reader.
 */
internal fun safeField(raw: String): String = raw
    .map { if (FIELD_ALLOWED.matches(it.toString())) it else ' ' }
    .joinToString("")
    .replace(Regex(" +"), " ")
    .trim()
    .take(MAX_FIELD)
    .ifEmpty { UNKNOWN }

/**
 * A class or method name reduced to the alphabet such a name can use.
 *
 * The cut at `/` is not only that filter: a JVM hidden class — every lambda on a
 * modern runtime — is named `Owner$$Lambda/0x00007f...`, whose tail is the
 * address it happened to load at. It means nothing in a report and it is the one
 * place a slash could otherwise reach the text.
 */
internal fun safeSymbol(raw: String): String = raw
    .substringBefore('/')
    .map { if (SYMBOL_ALLOWED.matches(it.toString())) it else '?' }
    .joinToString("")
    .take(MAX_SYMBOL)
    .ifEmpty { UNKNOWN }
