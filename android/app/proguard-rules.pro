# ProGuard rules for the aus-roads app.
# v0.1.1: MapLibre, Room, Hilt, kotlinx.serialization, kotlinx.datetime.

# Keep our Application class.
-keep public class au.com.ausroads.AusRoadsApp { *; }

# Keep the MainActivity launcher reference.
-keep public class au.com.ausroads.MainActivity { *; }

# --- MapLibre Native Android SDK 11.5.2 ---
# Mirrors the upstream proguard-rules.pro shipped with the AAR (see
# maplibre-native/platform/android/MapLibreAndroid/proguard-rules.pro).
# R8 must not strip the JNI bridge classes or the Gson reflection targets.
-keepattributes Signature, *Annotation*, EnclosingMethod

# Reflection on classes from native code
-keep class com.google.gson.JsonArray { *; }
-keep class com.google.gson.JsonElement { *; }
-keep class com.google.gson.JsonObject { *; }
-keep class com.google.gson.JsonPrimitive { *; }
-dontnote com.google.gson.**

-keep enum org.maplibre.android.tile.TileOperation
-keep class org.maplibre.android.maps.RenderingStats { *; }
-keep class org.maplibre.android.maps.NativeMapOptions { *; }

-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontnote org.maplibre.android.maps.MapLibreMap$OnFpsChangedListener
-dontnote org.maplibre.android.style.layers.PropertyValue
-dontnote org.maplibre.android.maps.MapLibreMap
-dontnote org.maplibre.android.maps.MapLibreMapOptions
-dontnote org.maplibre.android.log.LoggerDefinition

# config for mapbox-sdk-geojson (transitive)
-keep class org.maplibre.geojson.** { *; }
-dontwarn com.google.auto.value.**

# --- Room 2.7.0 ---
# Keep Room-generated DAO implementations and TypeConverters.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.dao.Dao { *; }
-keep class * extends androidx.room.TypeConverter { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# --- Hilt / Dagger 2.52 ---
# Keep Hilt-generated component classes and entry points.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * {
    @dagger.hilt.* <fields>;
    @dagger.hilt.* <methods>;
}
-keep class **_HiltModules* { *; }
-keep class **_HiltComponents* { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_MembersInjector { *; }
-keep class **_Factory { *; }

# --- kotlinx.serialization 1.7.3 ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class au.com.ausroads.**$$serializer { *; }
-keepclassmembers class au.com.ausroads.** {
    *** Companion;
}
-keepclasseswithmembers class au.com.ausroads.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- kotlinx.datetime ---
-keep class kotlinx.datetime.** { *; }

# --- Valhalla offline routing (valhalla-mobile 0.1.6 / valhalla-models 0.0.9) ---
# ValhallaRoutingEngine resolves the engine config via reflection on the bare,
# unnamed-package class `ValhallaConfigBuilder` (Class.forName("ValhallaConfigBuilder")),
# and ValhallaConfigManager serialises the com.valhalla.* config models with Moshi
# reflection. R8 must keep all of it or a minified release feeds the native lib a
# config it rejects — directions break in release while debug works.
-keep class ValhallaConfigBuilder { *; }
-keep class com.valhalla.** { *; }
-keepclassmembers class com.valhalla.** { *; }

# Moshi reflective adapters for the Valhalla config model data classes.
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class **JsonAdapter { <init>(...); }
-keep class kotlin.Metadata { *; }
-dontwarn com.squareup.moshi.**
-dontwarn okio.**

# --- SLF4J (transitive from Valhalla) ---
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# --- config for additional notes ---
-dontnote org.robolectric.Robolectric
-dontnote libcore.io.Memory
-dontnote com.google.protobuf.**
-dontnote android.net.**
-dontnote org.apache.http.**

-dontwarn com.sun.xml.internal.ws.spi.db.*

# --- Strip verbose/debug logging from minified (release) builds ---
# Diagnostic logs that include user GPS coordinates and search-query text use
# Log.d (LocationProviders / UserLocationOverlay / FtsSearchRepository); stripping
# Log.d/Log.v keeps precise location + queries out of a shipped privacy-first
# build's logcat. Operational Log.i/w/e (init, counts, failures) are retained.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
