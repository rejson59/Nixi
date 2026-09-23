# NIXI — ProGuard
# Minifikacja jest wyłączona (isMinifyEnabled=false), ale trzymamy reguły,
# aby włączenie minify w przyszłości nie zepsuło refleksji / serializacji.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# OkHttp / Kotlin coroutines mają własne consumer rules
-dontwarn okhttp3.**
-dontwarn okio.**

# org.json jest dostarczane przez platformę Androida
-dontwarn org.json.**
