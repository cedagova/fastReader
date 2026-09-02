package com.cedagova.fastreader.content

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A deliberately small, lenient scanner over EPUB markup.
 *
 * Why not a DOM parser: the package document is well-formed XML and
 * [com.cedagova.fastreader.epub.SafeXml] handles it, but *content* documents are
 * the sloppy part of a real library. They carry `&nbsp;` with no DTD in reach
 * (external entity loading is off, as it must be for untrusted files), stray
 * `<br>`, mismatched tags, and EPUB 2 files that are HTML wearing an XHTML
 * extension. A strict parse of those returns nothing at all, and "nothing at all"
 * for one chapter of a book the reader owns is the wrong outcome.
 *
 * So this walks the bytes once and emits tags and text, never failing: anything
 * it cannot understand becomes text or is ignored. It is pure Kotlin with no
 * platform XML or HTML dependency, which is also why its behavior on the JVM and
 * on a device is the same behavior.
 */
/**
 * Spaces `Char.isWhitespace` does not recognise.
 *
 * A non-breaking space is what typesetting puts between "84" and "Charing Cross
 * Road", and `&#160;` is how a navigation document writes it. To the JVM it is
 * not whitespace at all, so without this it would glue two words into one token
 * and leave a stray character in a chapter title.
 */
internal fun Char.isSpaceLike(): Boolean =
    isWhitespace() || this == '\u00A0' || this == '\u202F' || this == '\u2007' || this == '\uFEFF'

/** Trims and reduces every run of space-like characters to one ordinary space. */
internal fun String.collapseSpaces(): String {
    val out = StringBuilder(length)
    var pendingSpace = false
    for (char in this) {
        if (char.isSpaceLike()) {
            if (out.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(char)
        }
    }
    return out.toString()
}

internal sealed interface MarkupEvent {

    data class Open(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : MarkupEvent {
        fun attribute(name: String): String? = attributes[name.lowercase()]

        /** `epub:type`, `class` and friends are space-separated token lists. */
        fun hasToken(attribute: String, token: String): Boolean =
            attribute(attribute)
                ?.split(' ', '\t', '\n', '\r')
                ?.any { it.equals(token, ignoreCase = true) } == true
    }

    data class Close(val name: String) : MarkupEvent

    data class Text(val value: String) : MarkupEvent
}

internal object MarkupScanner {

    /** Elements that never have a closing tag, so nesting must not wait for one. */
    private val VOID_ELEMENTS = setOf(
        "area", "base", "basefont", "br", "col", "embed", "frame", "hr",
        "img", "input", "isindex", "link", "meta", "param", "source", "track", "wbr",
    )

    fun isVoid(name: String): Boolean = name in VOID_ELEMENTS

    /**
     * Emits every tag and text run in [markup], in document order.
     *
     * Comments, doctypes, processing instructions and CDATA wrappers are handled
     * here so callers only ever see structure and words.
     */
    fun scan(markup: String): Sequence<MarkupEvent> = sequence {
        var index = 0
        val text = StringBuilder()

        suspend fun SequenceScope<MarkupEvent>.flushText() {
            if (text.isNotEmpty()) {
                yield(MarkupEvent.Text(decodeEntities(text.toString())))
                text.setLength(0)
            }
        }

        while (index < markup.length) {
            val char = markup[index]
            if (char != '<') {
                text.append(char)
                index++
                continue
            }

            // `<` that cannot start a tag (`a < b`) is literal text, not markup.
            val next = markup.getOrNull(index + 1)
            if (next == null || !(next.isLetter() || next == '/' || next == '!' || next == '?')) {
                text.append(char)
                index++
                continue
            }

            when {
                markup.startsWith("<!--", index) -> {
                    index = skipTo(markup, index + 4, "-->", 3)
                }

                markup.startsWith("<![CDATA[", index) -> {
                    val end = markup.indexOf("]]>", index + 9)
                    val stop = if (end < 0) markup.length else end
                    // CDATA content is literal: entity decoding must not touch it.
                    flushText()
                    yield(MarkupEvent.Text(markup.substring(index + 9, stop)))
                    index = if (end < 0) markup.length else end + 3
                }

                markup.startsWith("<!", index) || markup.startsWith("<?", index) -> {
                    index = skipDeclaration(markup, index)
                }

                markup.startsWith("</", index) -> {
                    flushText()
                    val end = markup.indexOf('>', index)
                    val stop = if (end < 0) markup.length else end
                    val name = markup.substring(index + 2, stop).trim().substringBefore(' ').localName()
                    if (name.isNotEmpty()) yield(MarkupEvent.Close(name))
                    index = if (end < 0) markup.length else end + 1
                }

                else -> {
                    flushText()
                    val tagEnd = findTagEnd(markup, index)
                    val raw = markup.substring(index + 1, tagEnd)
                    val open = parseOpenTag(raw)
                    if (open != null) yield(open)
                    index = if (tagEnd < markup.length) tagEnd + 1 else markup.length
                    // `<script>`/`<style>` bodies are code, never book text.
                    if (open != null && !open.selfClosing && (open.name == "script" || open.name == "style")) {
                        val close = markup.indexOf("</${open.name}", index, ignoreCase = true)
                        index = if (close < 0) markup.length else close
                    }
                }
            }
        }
        flushText()
    }

    /** Finds the `>` that closes a tag, ignoring any inside a quoted attribute value. */
    private fun findTagEnd(markup: String, start: Int): Int {
        var index = start + 1
        var quote = ' '
        while (index < markup.length) {
            val char = markup[index]
            when {
                quote != ' ' -> if (char == quote) quote = ' '
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return index
            }
            index++
        }
        return markup.length
    }

    private fun skipTo(markup: String, from: Int, terminator: String, width: Int): Int {
        val end = markup.indexOf(terminator, from)
        return if (end < 0) markup.length else end + width
    }

    /**
     * Skips `<!DOCTYPE …>` and `<?xml …?>`.
     *
     * A doctype may carry an internal subset in brackets that itself contains
     * `>`, so bracket depth is tracked rather than jumping to the first `>`.
     */
    private fun skipDeclaration(markup: String, start: Int): Int {
        var index = start + 2
        var depth = 0
        while (index < markup.length) {
            when (markup[index]) {
                '[' -> depth++
                ']' -> if (depth > 0) depth--
                '>' -> if (depth == 0) return index + 1
            }
            index++
        }
        return markup.length
    }

    private fun parseOpenTag(raw: String): MarkupEvent.Open? {
        val body = raw.trimEnd()
        val selfClosing = body.endsWith("/")
        val inner = (if (selfClosing) body.dropLast(1) else body).trim()
        if (inner.isEmpty()) return null

        var index = 0
        while (index < inner.length && !inner[index].isWhitespace()) index++
        val name = inner.substring(0, index).localName()
        if (name.isEmpty()) return null

        val attributes = LinkedHashMap<String, String>()
        while (index < inner.length) {
            while (index < inner.length && inner[index].isWhitespace()) index++
            if (index >= inner.length) break
            val nameStart = index
            while (index < inner.length && !inner[index].isWhitespace() && inner[index] != '=') index++
            val attributeName = inner.substring(nameStart, index).lowercase()
            if (attributeName.isEmpty()) {
                index++
                continue
            }
            while (index < inner.length && inner[index].isWhitespace()) index++
            if (index < inner.length && inner[index] == '=') {
                index++
                while (index < inner.length && inner[index].isWhitespace()) index++
                val value = when (val quote = inner.getOrNull(index)) {
                    '"', '\'' -> {
                        index++
                        val valueStart = index
                        while (index < inner.length && inner[index] != quote) index++
                        val raw2 = inner.substring(valueStart, minOf(index, inner.length))
                        if (index < inner.length) index++
                        raw2
                    }

                    else -> {
                        val valueStart = index
                        while (index < inner.length && !inner[index].isWhitespace()) index++
                        inner.substring(valueStart, index)
                    }
                }
                attributes[attributeName] = decodeEntities(value)
            } else {
                attributes[attributeName] = ""
            }
        }

        return MarkupEvent.Open(
            name = name,
            attributes = attributes,
            selfClosing = selfClosing || isVoid(name),
        )
    }

    /** Namespace prefixes are used inconsistently across EPUBs; only the local name matters. */
    private fun String.localName(): String = substringAfterLast(':').trim().lowercase()

    /**
     * The named entities worth carrying.
     *
     * XHTML's full entity set lives in a DTD this pipeline deliberately never
     * loads, so the ones that actually appear in book text are inlined. Anything
     * unknown is left exactly as written rather than dropped, so a stray `&` in
     * "Marks & Co." survives.
     */
    private val NAMED_ENTITIES: Map<String, Char> = mapOf(
        "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'',
        "nbsp" to ' ', "shy" to '­', "ensp" to ' ', "emsp" to ' ',
        "thinsp" to ' ', "zwj" to '‍', "zwnj" to '‌',
        "ndash" to '–', "mdash" to '—', "horbar" to '―',
        "lsquo" to '‘', "rsquo" to '’', "sbquo" to '‚',
        "ldquo" to '“', "rdquo" to '”', "bdquo" to '„',
        "laquo" to '«', "raquo" to '»', "lsaquo" to '‹', "rsaquo" to '›',
        "hellip" to '…', "bull" to '•', "middot" to '·',
        "dagger" to '†', "Dagger" to '‡', "prime" to '′',
        "iexcl" to '¡', "iquest" to '¿',
        "aacute" to 'á', "eacute" to 'é', "iacute" to 'í', "oacute" to 'ó', "uacute" to 'ú',
        "Aacute" to 'Á', "Eacute" to 'É', "Iacute" to 'Í', "Oacute" to 'Ó', "Uacute" to 'Ú',
        "ntilde" to 'ñ', "Ntilde" to 'Ñ', "uuml" to 'ü', "Uuml" to 'Ü',
        "agrave" to 'à', "egrave" to 'è', "ccedil" to 'ç', "Ccedil" to 'Ç',
        "ordf" to 'ª', "ordm" to 'º', "deg" to '°',
        "euro" to '€', "pound" to '£', "copy" to '©', "reg" to '®',
        "times" to '×', "divide" to '÷',
    )

    /** Resolves `&amp;`, `&#233;` and `&#xE9;`; leaves anything else untouched. */
    fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '&') {
                out.append(char)
                index++
                continue
            }
            val semicolon = value.indexOf(';', index + 1)
            // A reference is short; a bare `&` followed by prose is not a reference.
            if (semicolon < 0 || semicolon - index > 12) {
                out.append(char)
                index++
                continue
            }
            val body = value.substring(index + 1, semicolon)
            val resolved = resolveEntity(body)
            if (resolved == null) {
                out.append(char)
                index++
            } else {
                out.append(resolved)
                index = semicolon + 1
            }
        }
        return out.toString()
    }

    private fun resolveEntity(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val digits = body.drop(1)
            val code = if (digits.startsWith("x") || digits.startsWith("X")) {
                digits.drop(1).toIntOrNull(16)
            } else {
                digits.toIntOrNull()
            } ?: return null
            if (code <= 0 || code > 0x10FFFF) return null
            return String(Character.toChars(code))
        }
        return NAMED_ENTITIES[body]?.toString()
    }
}

/**
 * Turns content-document bytes into text without mojibake.
 *
 * EPUB 3 mandates UTF-8 or UTF-16, but EPUB 2 files in a real library are
 * routinely Latin-1 with a declaration that says so — and sometimes with no
 * declaration at all. The order below is BOM, then declaration, then a strict
 * UTF-8 attempt, then Latin-1: reading valid UTF-8 as Latin-1 gives "Ã¡" for "á",
 * so UTF-8 has to be *ruled out* rather than merely defaulted away from.
 */
internal object ContentCharsets {

    private const val DECLARATION_WINDOW = 1024

    fun decode(bytes: ByteArray): String {
        bomCharset(bytes)?.let { (charset, offset) ->
            return String(bytes, offset, bytes.size - offset, charset)
        }
        declaredCharset(bytes)?.let { declared ->
            decodeStrictly(bytes, declared)?.let { return it }
        }
        decodeStrictly(bytes, Charsets.UTF_8)?.let { return it }
        return String(bytes, latin1())
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE to 2

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE to 2

        else -> null
    }

    /** Reads `<?xml encoding="…"?>` or `<meta charset="…">` from the ASCII-safe head of the file. */
    private fun declaredCharset(bytes: ByteArray): Charset? {
        val window = String(bytes, 0, minOf(bytes.size, DECLARATION_WINDOW), latin1())
        val name = ENCODING_PATTERN.find(window)?.groupValues?.getOrNull(1)
            ?: CHARSET_PATTERN.find(window)?.groupValues?.getOrNull(1)
            ?: return null
        return charsetOrNull(name.trim())
    }

    private val ENCODING_PATTERN = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CHARSET_PATTERN = Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    /**
     * Only charsets every JVM and every Android build is required to have, plus
     * `windows-1252` when the platform happens to offer it. Looking up an
     * arbitrary declared name would make decoding depend on which runtime the
     * code is on, and the whole point of this object is that it does not.
     */
    private fun charsetOrNull(name: String): Charset? = when (name.lowercase()) {
        "utf-8", "utf8" -> Charsets.UTF_8
        "utf-16" -> Charsets.UTF_16
        "utf-16le" -> Charsets.UTF_16LE
        "utf-16be" -> Charsets.UTF_16BE
        "us-ascii", "ascii" -> Charsets.US_ASCII
        "iso-8859-1", "iso8859-1", "latin1", "latin-1" -> latin1()
        "windows-1252", "cp1252" -> runCatching { Charset.forName("windows-1252") }.getOrNull() ?: latin1()
        else -> null
    }

    private fun latin1(): Charset = Charsets.ISO_8859_1

    /** Decodes only if every byte is valid in [charset]; null means "this is not that encoding". */
    private fun decodeStrictly(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
