package com.cedagova.fastreader.epub

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * XML parsing for untrusted book files.
 *
 * EPUBs come from wherever the reader got them, so external entity resolution is
 * switched off (XXE): no external DTD load, no general/parameter entities. A
 * `DOCTYPE` declaration itself is still tolerated because real-world EPUBs are
 * frequently sloppy and the pinned research says to parse leniently.
 */
internal object SafeXml {

    fun parse(bytes: ByteArray): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeatureQuietly(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeatureQuietly("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setQuietly { isExpandEntityReferences = false }
        // Android's parser refuses this one outright; external entities are already blocked above.
        factory.setQuietly { isXIncludeAware = false }
        run {
            val builder = factory.newDocumentBuilder()
            // Never resolve anything external, whatever the document asks for.
            builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
            builder.setErrorHandler(null)
            builder.parse(ByteArrayInputStream(bytes))
        }
    } catch (_: Exception) {
        null
    }

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        setQuietly { setFeature(name, value) }
    }

    /**
     * Parser implementations differ in what they support — Android's, for one,
     * throws outright on `setXIncludeAware`. A hardening step that a parser
     * refuses must not take the whole parse down with it.
     */
    private inline fun DocumentBuilderFactory.setQuietly(block: DocumentBuilderFactory.() -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // The remaining hardening still applies.
        }
    }
}

/** Depth-first element traversal, namespace-agnostic on the local name. */
internal fun Node.descendants(): Sequence<Element> = sequence {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element) {
            yield(child)
            yieldAll(child.descendants())
        }
    }
}

/** Local name comparison that ignores namespace prefixes, which EPUBs use inconsistently. */
internal fun Element.hasLocalName(name: String): Boolean =
    (localName ?: tagName).substringAfterLast(':').equals(name, ignoreCase = true)

internal fun Element.attr(name: String): String? =
    getAttribute(name).takeIf { it.isNotEmpty() }
        ?: descendantAttributeByLocalName(name)

private fun Element.descendantAttributeByLocalName(name: String): String? {
    val attributes = attributes ?: return null
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        val local = (attribute.localName ?: attribute.nodeName).substringAfterLast(':')
        if (local.equals(name, ignoreCase = true)) {
            return attribute.nodeValue?.takeIf { it.isNotEmpty() }
        }
    }
    return null
}
