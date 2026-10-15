# kotlinx.serialization: keep generated serializers for the DTOs the core module decodes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.neutrino.drive.** {
    *** Companion;
}
-keepclasseswithmembers class com.neutrino.drive.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# BouncyCastle is looked up by class in places; the primitives used are referenced directly, but
# keep the package to be safe rather than chase a release-only crypto failure.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
