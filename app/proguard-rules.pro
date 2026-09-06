# NotifWebhook ProGuard rules

# security-crypto (Tink) references javax.annotation which is not on the
# runtime classpath. R8 fails minify on these missing classes — keep them
# so the release build can resolve the references.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.checker.nullness.compatqual.**

# Tink's KeysDownloader references google-api-client and joda-time which the
# app does not use — R8 must not fail on these optional dependencies.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Tink keeps its own rules but these packages must survive minification.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.common.** { *; }
