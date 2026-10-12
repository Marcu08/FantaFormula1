# Regole ProGuard/R8 — l'app non usa reflection su classi proprie,
# le classi @Serializable di kotlinx-serialization vengono tenute via plugin.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.fantaformula1.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.fantaformula1.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
