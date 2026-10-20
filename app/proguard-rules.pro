# Aster Files Release R8 rules.
# Keep model/data classes used by storage, audit and future serialization.
-keep class com.aster.files.data.** { *; }
-keep class com.aster.files.security.** { *; }
-keep class com.aster.files.network.** { *; }

# Media3 service and player callbacks.
-keep class com.aster.files.playback.** { *; }
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class * extends androidx.media3.common.BasePlayer { *; }
-dontwarn androidx.media3.**

# FileProvider / activities launched from the manifest.
-keep class com.aster.files.MainActivity { *; }
-keep class com.aster.files.SafPickerActivity { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.content.ContentProvider { *; }

# Zip4j uses reflective crypto/entry helpers.
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**

# BouncyCastle/SMBJ algorithms are loaded reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# apksig signing engine.
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# APNG decoder and local HTTP server.
-keep class com.github.penfeizhou.** { *; }
-keep class org.nanohttpd.** { *; }

# ZXing / JSON.
-keep class com.google.zxing.** { *; }
-keep class org.json.** { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn javax.el.**
-dontwarn javax.naming.**
