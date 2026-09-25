# Keep rules every host of :reader-engine needs when it shrinks with R8 (#201).
#
# A plain JVM library has no `consumerProguardFiles`; R8 reads the rules a jar
# carries under META-INF/proguard/, so they ship inside the module's jar.
#
# kotlinx.serialization finds a class's generated serializer at run time
# through the class's `Companion` field and that companion's `serializer()`
# (or, for a @Serializable object, its `INSTANCE` and `serializer()`). A host
# that stores these types in its own @Serializable settings names each
# serializer directly, which R8 can see; one that looks them up by class or type
# reaches those members reflectively, which R8 cannot see.
#
# These are the serialization runtime's own rules, scoped to this module's
# packages (com.cedagova.reader.engine.**; today only timing.PauseStrength is
# @Serializable), so the module keeps its own types without relying on a host
# keeping the rules the serialization jar embeds. scripts/library-copy-check.sh
# proves it (see docs/library-consumption.md).

# @Serializable is read at run time to choose between sealed and polymorphic
# serializers; R8 full mode drops annotations it is not told to keep.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# The `Companion` field of every serializable class, under its own name.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedagova.reader.engine.** {
    static ** Companion;
}

# `serializer()` on that companion.
-if @kotlinx.serialization.Serializable class com.cedagova.reader.engine.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# `INSTANCE` and `serializer()` of a serializable object.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedagova.reader.engine.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the @Serializable annotation itself on each such class in R8 full mode.
-if @kotlinx.serialization.Serializable class com.cedagova.reader.engine.**
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class com.cedagova.reader.engine.<1>
