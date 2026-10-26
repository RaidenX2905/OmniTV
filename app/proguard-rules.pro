# Keep models for deserialization/reflection if needed
-keep class app.omnitv.data.model.** { *; }

# Media3 / ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
