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
# These are the serialization runtime's own rules, scoped to the one package of
# this module that declares @Serializable types (`timing`: PauseStrength), so
# the module keeps its own types without relying on a host keeping the rules
# the serialization jar embeds. scripts/library-copy-check.sh proves it (see
# docs/library-consumption.md). A @Serializable type added to another package
# of this module needs that package added here and in the copy set.

# @Serializable is read at run time to choose between sealed and polymorphic
# serializers; R8 full mode drops annotations it is not told to keep.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# The `Companion` field of every serializable class, under its own name.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedagova.fastreader.timing.** {
    static ** Companion;
}

# `serializer()` on that companion.
-if @kotlinx.serialization.Serializable class com.cedagova.fastreader.timing.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# `INSTANCE` and `serializer()` of a serializable object.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedagova.fastreader.timing.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the @Serializable annotation itself on each such class in R8 full mode.
-if @kotlinx.serialization.Serializable class com.cedagova.fastreader.timing.**
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class com.cedagova.fastreader.timing.<1>
