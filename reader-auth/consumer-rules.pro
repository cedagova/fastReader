# Keep rules every host of :reader-auth needs when it shrinks with R8 (#100).
#
# A host's release build may be minified; the first host of this library to
# shrink was the app it is developed beside (the retired proving-ground host
# never shrank). The library's
# own code needs nothing kept, but two of its dependencies are reached through
# reflection R8 cannot see:
#
#   * kotlinx.serialization looks a class's generated serializer up through its
#     `Companion.serializer()` — the rules below are the ones the library's own
#     documentation prescribes, scoped to @Serializable classes, so the
#     provider SDK's session and user records and this module's pre-auth
#     document survive shrinking.
#   * The provider SDK's `auth-kt` and Ktor ship their own consumer rules, so
#     nothing about them is repeated here.
#
# Proven by installing the gate's own signed release APK on the emulator and
# reaching stage from it (docs/evidence/100/).
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses, Signature

# kotlinx.serialization: keep the generated Companion + serializer() of every
# @Serializable class, and the INSTANCE of a @Serializable object.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
