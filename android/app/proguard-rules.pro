# Reglas ProGuard/R8 para Phantom Pad.
# La release se publica sin minificar en v0.1; reglas defensivas para futuras versiones.
-keep class com.phantompad.app.protocol.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.phantompad.app.**$$serializer { *; }
-keepclassmembers class com.phantompad.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.phantompad.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
