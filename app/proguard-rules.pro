# ── ON-SPOT POS — R8 keep rules ───────────────────────────────────────────
# The release build is minified (isMinifyEnabled = true). These rules protect
# the handful of places that rely on reflection, which R8 can't see statically.

# Keep source line numbers so a crash report from the field is still readable,
# then hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ──────────────────────────────────────────────────
# The sync layer (Dtos.kt) round-trips @Serializable DTOs by reflection over the
# generated $serializer. Without these, R8 renames them and JSON breaks.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# Keep generated serializers and the companion .serializer() accessor.
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the @Serializable model classes themselves (DTOs + any nested types).
-keep,includedescriptorclasses class com.portionspot.pos.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class com.portionspot.pos.** { *; }

# ── Room ───────────────────────────────────────────────────────────────────
# Room generates *_Impl classes and reflects over entities/DAOs at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ── OkHttp / Okio (Supabase REST transport) ────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── ZXing (Paynow QR generation) ───────────────────────────────────────────
-dontwarn com.google.zxing.**

# ── Kotlin enums (e.g. payment method / sale mode) ─────────────────────────
-keepclassmembers enum * { *; }
