<p align="center">
  <img src="assets/omnitv-icon-rounded.png" alt="OmniTV App Icon" width="128" height="128">
</p>

<div align="center">

# OmniTV

[![Android](https://img.shields.io/badge/Android-Phone%20%7C%20TV-3DDC84?style=flat-square&logo=android&logoColor=white)](#) [![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#) [![Jetpack%20Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](#) [![Media3](https://img.shields.io/badge/Media3-ExoPlayer-FF6F00?style=flat-square&logo=google&logoColor=white)](#) [![APK%20Size](https://img.shields.io/badge/APK%20Size-~3.5%20MB-00C853?style=flat-square)](#) [![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](#)

<p align="center">
  Hardware-accelerated IPTV media player for Android phones, tablets, and Android TV featuring a glassmorphic interface, channel search, and D-pad remote navigation.
</p>

</div>

---

**Live Demo**: [https://github.com/RaidenX2905/OmniTV/releases](https://github.com/RaidenX2905/OmniTV/releases)

---

## Highlights

- **Universal Screen Adaptation**: Seamless experience across Android phones (touch-first), tablets (adaptive grid), and Android TV / Google TV / Fire TV (D-pad focus engine).
- **Low-Footprint Runtime**: Ultra-lean APK footprint under 4 MB with zero bloat.
- **ExoPlayer Video Engine**: Powered by AndroidX Media3 for instant buffering, hardware decoding, and aspect-ratio preservation.
- **M3U/M3U8 Support**: Built-in channel search, group categorization, and custom playlist URL configuration.

---

## Playlist Sources

Works with standard IPTV playlists. For legal and free public streams, the community-maintained `iptv-org` playlist is recommended:

```text
https://iptv-org.github.io/iptv/index.m3u
```

---

## Build from Source

```bash
./gradlew assembleRelease
```

---

## License

MIT License
