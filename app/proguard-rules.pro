# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# Die generierten Serializer werden nur über Reflection gefunden. Ohne diese
# Regeln liefert R8 im Release-Build "Serializer for class X not found".
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Alle DTOs samt ihrer generierten $$serializer-Klassen behalten.
-keep,includedescriptorclasses class com.streamitv.tv.data.remote.**$$serializer { *; }
-keepclassmembers class com.streamitv.tv.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.streamitv.tv.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Media3 / ExoPlayer
# ---------------------------------------------------------------------------
# Die Extension-Renderer (Software-Decoder) werden per Reflection geladen.
-dontwarn androidx.media3.decoder.**
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.** { *; }

# ---------------------------------------------------------------------------
# OkHttp / Okio
# ---------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
-dontwarn dagger.hilt.**

# ---------------------------------------------------------------------------
# Compose
# ---------------------------------------------------------------------------
# Hilft beim Lesen von Stacktraces aus Crash-Berichten.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
