package com.cedagova.fastreader.epub

import java.io.ByteArrayOutputStream

/** Zip-path helpers for hrefs written inside an OPF. */
internal object EpubPaths {

    /** Resolves [href] (possibly percent-encoded and relative) against the OPF's directory. */
    fun resolve(opfPath: String, href: String): String? {
        val cleaned = decodePercent(href).substringBefore('#').trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.contains("://")) return null // remote resources are never part of the package
        val base = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (cleaned.startsWith('/')) cleaned.removePrefix("/") else {
            if (base.isEmpty()) cleaned else "$base/$cleaned"
        }
        return normalize(combined)
    }

    private fun normalize(path: String): String? {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    /**
     * Percent-decodes a URI path. Unlike `URLDecoder`, `+` stays a literal plus,
     * which is what file names inside a zip mean.
     */
    fun decodePercent(value: String): String {
        if (!value.contains('%')) return value
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    bytes.write(decoded)
                    index += 3
                    continue
                }
            }
            bytes.write(char.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
