# PurchaseKit consumer rules
# Keep serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclassnames class kotlinx.serialization.internal.** {
    *;
}
