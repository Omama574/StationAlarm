# ── Crashlytics — preserve line numbers in crash reports ─────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep public class * extends java.lang.Exception

# ── Retrofit ──────────────────────────────────────────────────────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn retrofit2.-**

# ── GSON — keep all network model classes used for JSON deserialization ───────
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }
# All classes in the network package are GSON models — keep their fields
-keep class com.omama.stationalarm.network.** { *; }
# Station domain model is also deserialized from assets/stations.json
-keep class com.omama.stationalarm.data.Station { *; }
# StationData parses stations.json with `object : TypeToken<List<Station>>() {}`. R8 full mode strips the
# generic signature of that anonymous subclass, so Gson throws "TypeToken must be created with a type
# argument" and the offline station list silently fails to load (release builds only). Gson's own
# recommended rules:
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.**

# ── OkHttp / OkIO ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── OSMDroid ──────────────────────────────────────────────────────────────────
-keep class org.osmdroid.** { *; }

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Kotlin Serialization / Reflection ─────────────────────────────────────────
-keepattributes Kotlin**
-keep class kotlin.Metadata { *; }
