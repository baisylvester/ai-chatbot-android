# kotlinx.serialization: keep generated serializers of the network DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.embeddablechatbot.android.core.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.embeddablechatbot.android.core.data.**$$serializer { *; }
# Tink references optional annotations at compile time only.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
