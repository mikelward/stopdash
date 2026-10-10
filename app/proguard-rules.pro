# kotlinx.serialization keep rules.
#
# These mirror the consumer R8/ProGuard rules the kotlinx-serialization artifacts
# already bundle (Kotlin/kotlinx.serialization, rules/common.pro plus the R8 full-mode
# rules/r8.pro below), copied here as an explicit, redundant safety net. The app
# (de)serializes both its TfL response models
# and its persisted DataStore state, and R8 shrinks/optimizes/obfuscates on every
# release build. If a future library version or build change stops contributing its
# bundled rules, these keep the generated $serializer/Companion lookups alive so
# serialization can't fail silently in an obfuscated release. Keep rules are additive:
# this only ever preserves more, and never weakens what the library already contributes.
#
# Source: https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro

# Keep `Companion` object field of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

# Keep names for named companion object from obfuscation
# Names of a class and of a field are important in lookup of named companion in runtime
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * {
    static <1> *;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't print notes about potential mistakes or omissions in the configuration for kotlinx-serialization classes
# See also https://github.com/Kotlin/kotlinx.serialization/issues/1900
-dontnote kotlinx.serialization.**
# Serialization core uses `java.lang.ClassValue` for caching inside these specified classes.
# If there is no `java.lang.ClassValue` (for example, in Android), then R8/ProGuard will print a warning.
# However, since in this case they will not be used, we can disable these warnings
-dontwarn kotlinx.serialization.internal.ClassValueReferences

# disable optimisation for descriptor field because in some versions of ProGuard, optimization generates incorrect bytecode that causes a verification error
# see https://github.com/Kotlin/kotlinx.serialization/issues/2719
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

# R8 full-mode rules, which AGP enables by default (android.enableR8.fullMode is not
# disabled in gradle.properties). Mirrors the library's rules/r8.pro (packaged as
# META-INF/com.android.tools/r8/kotlinx-serialization-r8.pro): full mode strips runtime
# annotations and treats objects differently, so these are needed on top of common.pro
# above for the safety net to be complete.
#
# Source: https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/r8.pro

# Rule to save runtime annotations on serializable class.
# If the R8 full mode is used, annotations are removed from classes-files.
#
# For the annotation serializer, it is necessary to read the `Serializable` annotation inside the serializer<T>() function - if it is present,
# then `SealedClassSerializer` is used, if absent, then `PolymorphicSerializer'.
#
# When using R8 full mode, all interfaces will be serialized using `PolymorphicSerializer`.
#
# see https://github.com/Kotlin/kotlinx.serialization/issues/2050
-if @kotlinx.serialization.Serializable class **
-keep, allowshrinking, allowoptimization, allowobfuscation, allowaccessmodification class <1>

# Rule to save runtime annotations on named companion class.
# If the R8 full mode is used, annotations are removed from classes-files.
-if @kotlinx.serialization.internal.NamedCompanion class *
-keep, allowshrinking, allowoptimization, allowobfuscation, allowaccessmodification class <1>

# Rule to save INSTANCE field and serializer function for Kotlin serializable objects.
#
# R8 full mode works differently if the instance is not explicitly accessed in the code.
#
# see https://github.com/Kotlin/kotlinx.serialization/issues/2861
# see https://issuetracker.google.com/issues/379996140
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Glance remembers which GlanceAppWidget class each receiver hosts by class
# name; R8 renaming StopDashWidget between releases leaves that map stale.
# Widget code reads IDs by receiver (placedWidgetIds), but keep the name
# stable so Glance's own lookups agree across releases too.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
