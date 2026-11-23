# Keep Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.syncbeats.app.network.**$$serializer { *; }
-keepclassmembers class com.syncbeats.app.network.** {
    *** Companion;
}
