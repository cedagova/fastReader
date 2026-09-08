package com.cedagova.fastreader.content

/**
 * The two short texts shipped inside the APK, so a stranger has something to
 * read two taps after installing (REQ-109).
 *
 * A sample is deliberately *not* a library book. It has no catalog row, no
 * persistable source grant, and no stored reading position; it is a file inside
 * the app that the reader can stream and then forget. Everything that makes it
 * different from a book the reader added is expressed by
 * [com.cedagova.fastreader.reader.BookOrigin.SAMPLE].
 *
 * ## The identity is fixed before the app runs (AD-8)
 *
 * [identity] is the SHA-256 of the committed asset, written down here rather
 * than computed. That is the whole point of the open contract: nothing on the
 * open path hashes a file, so the sample opens at the cost of its text rather
 * than the cost of its bytes, exactly like every other book.
 *
 * The digests below are checked against the real assets on every build by
 * `SampleAssetsTest`, so a rebuilt sample whose digest was not updated fails the
 * unit-test gate instead of shipping an identity that matches nothing. The
 * builder that produces the assets is `scripts/build-sample-epubs.py`; the text's
 * provenance and licence are in `scripts/samples/README.md`.
 *
 * ## Provenance
 *
 * Both texts were written for FastReader and dedicated to the public domain
 * under CC0 1.0 Universal. Neither is an excerpt of a published work, which is
 * why their licence can be stated exactly. Each sample names its own source in
 * its final chapter, so the statement travels with the text.
 */
enum class BundledSample(
    /** Path inside the APK's assets, as [android.content.res.AssetManager] wants it. */
    val assetPath: String,

    /** The asset's whole-file SHA-256, pinned at build time and never recomputed. */
    val identity: BookIdentity,

    /** BCP-47 primary language subtag, matched against the device's language. */
    val languageTag: String,

    /**
     * The language's own name for itself, which is what the offer is labelled
     * with. An endonym does not change with the interface language, so this stays
     * out of `strings.xml` and needs no translation when `values-es` arrives.
     */
    val endonym: String,

    /** The `dc:title` inside the asset, shown while the sample is opening. */
    val title: String,
) {

    ENGLISH(
        assetPath = "samples/sample-en.epub",
        identity = BookIdentity.ofSha256Hex("b96d3d43df2f7d137a7f08c77587c84927d518ac1a7a212aeebb641be2c2305f"),
        languageTag = "en",
        endonym = "English",
        title = "A Word at a Time",
    ),

    SPANISH(
        assetPath = "samples/sample-es.epub",
        identity = BookIdentity.ofSha256Hex("54c0ca1af63a1f5c7702c0fa8549fb4216b1c9b7fc760e96ef20f3a675141356"),
        languageTag = "es",
        endonym = "Español",
        title = "Una palabra a la vez",
    ),

    ;

    /**
     * What "the same book" means to the reader for this sample.
     *
     * Not the identity: [com.cedagova.fastreader.reader.BookOpenRequest.openKey]
     * is compared against a catalog id and a document URI too, and a bare digest
     * would collide with a library book if the reader ever added this exact file
     * themselves.
     */
    val openKey: String get() = "sample:$name"

    companion object {

        /**
         * The samples in the order they should be offered on a device whose
         * language is [deviceLanguage] (a BCP-47 primary subtag).
         *
         * A sample in the reader's own language comes first; the rest keep their
         * declared order. On a Spanish device that puts Spanish first, which is
         * the whole of REQ-109's language rule — the *interface* stays English
         * until the Spanish resource set lands (D5).
         */
        fun offeredFor(deviceLanguage: String): List<BundledSample> =
            entries.sortedBy { if (it.languageTag.equals(deviceLanguage, ignoreCase = true)) 0 else 1 }

        /**
         * Whether [positionKey] names a sample rather than a real book.
         *
         * Recording a position is also what makes a book the last-read one, and
         * the sample must never become that: it has no catalog row, so a launch
         * routed into it would land on "the book could not be opened". The reader
         * keeps no position for a sample at all — out of scope for #48, and this
         * is the check that enforces it.
         */
        fun isSampleIdentity(positionKey: String): Boolean =
            entries.any { it.identity.value == positionKey }
    }
}
