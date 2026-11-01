# Third-party notices

## x-io Fusion

- Upstream: https://github.com/xioTechnologies/Fusion
- Pinned commit: `a8d7224f36a0ec82345ef49a3db50e65f8d3bab8`
- License: MIT, copyright (c) 2021 x-io Technologies.
- Unmodified source and complete license: `third_party/Fusion/`.
- Compiled components: `FusionAhrs.c`, `FusionBias.c`, and their headers.
- Adapter: `app/src/main/cpp/fusion_jni.c` is project code; sensor units are converted from Android rad/s and m/s² to Fusion deg/s and g.

The complete Fusion MIT notice is also included in the APK's assets.

## AndroidX

AndroidX Activity, Core, and transitive AndroidX libraries are copyright The Android Open Source Project and licensed under Apache License 2.0. AndroidX Test and JUnit are test-only dependencies and are not packaged in the application APK. JUnit 4 is distributed under the Eclipse Public License 1.0.

The Apache 2.0 text is included as an APK asset. AndroidX dependencies are resolved through Gradle, rather than vendored or modified.

## Gradle wrapper and reference sample

The Gradle wrapper JAR, `gradlew`, and `gradlew.bat` originated from the user's `GlassesBareDevSample.zip`. Gradle is distributed under Apache License 2.0; the launcher scripts retain their original copyright/license headers. Wrapper distribution is set to Gradle 9.5.0.

The user-provided sample is the reference for the Rokid 480×640 display, safe area and firmware input event names. Its sensor/display assumptions are explicitly documented in README.md. No camera, audio, recording or storage modules are included.
