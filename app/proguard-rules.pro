# Keep models for deserialization/reflection if needed
-keep class app.omnitv.data.model.** { *; }

# Media3 / ExoPlayer - keep all public API to prevent optimization bugs
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Jetpack Compose
-dontwarn androidx.compose.**
