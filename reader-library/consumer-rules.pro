# Keep rules every host of :reader-library needs when it shrinks with R8 (#207).
#
# kotlinx.serialization finds a class's generated serializer at run time
# through the class's `Companion` field and that companion's `serializer()`
# (or, for a @Serializable object, its `INSTANCE` and `serializer()`). This
# module's own calls name each serializer directly, which R8 can see; a host
# that looks one of these types up by class or type instead — a
# content-negotiation converter, `serializer(typeOf<…>())` — reaches those
# members reflectively, which R8 cannot see.
#
# These are the serialization runtime's own rules, scoped to this module's
# package, so the module keeps its own types by itself: it does not depend on
# :reader-auth's global copy of them reaching it, nor on a host keeping the
# rules the serialization jar embeds (a host can drop a dependency's rules with
# AGP's `keepRules { ignoreFrom(…) }`). scripts/library-copy-check.sh proves it:
# it shrinks a standalone host with those other sources ignored and fails if
# any serialized type below lost one of these members.

# @Serializable is read at run time to choose between sealed and polymorphic
# serializers; R8 full mode drops annotations it is not told to keep.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# The `Companion` field of every serializable class, under its own name.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedagova.reader.library.** {
    static ** Companion;
}

# `serializer()` on that companion.
-if @kotlinx.serialization.Serializable class com.cedagova.reader.library.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# `INSTANCE` and `serializer()` of a serializable object.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedagova.reader.library.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the @Serializable annotation itself on each such class in R8 full mode.
-if @kotlinx.serialization.Serializable class com.cedagova.reader.library.**
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class com.cedagova.reader.library.<1>
