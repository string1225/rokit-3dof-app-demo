# Diagnostics and Power Test Implementation Plan

> User approved direct implementation. Execute inline, with the independent power subsystem delegated per dispatching-parallel-agents. Keep existing branch and push verified changes.

**Goal:** Correct observed axes and ship an APK with useful motion diagnostics plus five automatic battery workloads.

**Architecture:** Existing quaternion pipeline uses Android axes by default. A synchronized, Android-independent analyzer receives timestamped sensor poses and submitted-frame poses; it estimates residuals retrospectively and produces a four-second spectrum. A screen-fixed chart and timing HUD accompany the world-fixed text. An independent power module owns telemetry, native media resources, five-stage sequencing and durable/exported reports.

- [x] Reproduce default-axis confusion with yaw/roll regression tests; default to Android axes while retaining explicit legacy profile.
- [x] Test known sine frequencies, quiet signals, insufficient samples, gaps and frame-pose residual alignment; implement `core/MotionDiagnostics.java` and quaternion interpolation.
- [x] Wire sensor callback time and processed-pose snapshots into diagnostics; add cached chart rendering and system frame timings without filtering away real head motion.
- [x] Implement `power/PowerRecorder`, `PowerBenchmark`, native microphone/audio/camera workloads and meaningful energy/schedule tests in isolated files.
- [x] Replace single start button with two-mode menu; integrate permissions, foreground lifecycle, report saving and benchmark display; preserve existing Rokid input semantics inside3DoF.
- [x] Run Gradle unit tests, Lint and both APK builds. Run actual JNI/UI instrumentation, plus an explicitly shortened five-workload integration round. Inspect images, MP4, CSV and summary outcomes; verify background abort and missing-permission behavior.
- [x] Save final screenshots, hash and verification record; review changes, commit and push; provide new APK and exact measurement limitations.
