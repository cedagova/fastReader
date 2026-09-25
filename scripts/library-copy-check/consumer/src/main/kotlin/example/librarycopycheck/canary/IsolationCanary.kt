package example.librarycopycheck.canary

import kotlinx.serialization.Serializable

/**
 * The copy check's isolation canary. Nothing references it and the host's rules
 * keep only the class itself, never its serializer lookup, so after R8 its
 * `Companion.serializer()` must be gone. If it survives, some rule R8 received
 * keeps the serializer lookup of every package, whatever that rule's shape, and
 * a library whose own keep rules were missing would pass unnoticed; the script
 * then fails.
 */
@Serializable
internal class IsolationCanary(val value: Int = 0)
