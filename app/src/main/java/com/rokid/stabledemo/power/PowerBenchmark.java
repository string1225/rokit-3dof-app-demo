package com.rokid.stabledemo.power;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sequential foreground benchmark. Caller owns runtime permissions and display keep-awake. */
public final class PowerBenchmark {
    private static final long STARTUP_TIMEOUT_MS = 30_000;
    private final Context context;
    private final PhaseSchedule schedule;
    private final Runnable onChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("power-benchmark");
    private final Handler control;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean running;
    private volatile boolean finished;
    private volatile File resultDirectory;
    private volatile String[] lines = {"功耗测试", "准备就绪", "五阶段，每阶段五分钟"};
    private PowerRecorder recorder;
    private BenchmarkWorkload workload;
    private int index = -1;
    private int generation;
    private long stageStart;
    private boolean ready;
    private boolean stopping;
    private boolean terminating;
    private String finalReason;
    private String detail = "";

    public PowerBenchmark(Context context, long stageDurationMs, Runnable onChanged) {
        this.context = context.getApplicationContext();
        this.schedule = new PhaseSchedule(stageDurationMs);
        this.onChanged = onChanged;
        thread.start();
        control = new Handler(thread.getLooper());
        refresh("准备就绪");
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        running = true;
        control.post(() -> {
            if (terminating) return;
            try {
                recorder = new PowerRecorder(context, "sequential_power_benchmark");
                resultDirectory = recorder.directory();
                recorder.metadata("requested_stage_duration_ms", schedule.durationMs());
                recorder.metadata("requested_workload_total_ms", schedule.totalDurationMs());
                recorder.metadata("photo_interval_ms", PhaseSchedule.PHOTO_INTERVAL_MS);
                recorder.metadata("startup_timeout_ms", STARTUP_TIMEOUT_MS);
                recorder.metadata("stage_timing_policy", "Each workload timer begins only after successful workload readiness. Setup and cleanup are additional actual elapsed time. Failure aborts the entire benchmark.");
                advance();
                control.post(tick);
            } catch (IOException | RuntimeException e) { terminate("failed: " + message(e)); }
        });
    }

    public void abort(String reason) {
        started.compareAndSet(false, true);
        control.post(() -> terminate("aborted: " + (reason == null ? "user" : reason)));
    }
    public boolean isRunning() { return running; }
    public boolean isFinished() { return finished; }
    public String[] screenLines() { return lines.clone(); }
    public File directory() { return resultDirectory; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (terminating) return;
            if (recorder != null && recorder.failureReason() != null) {
                terminate("failed: telemetry writer: " + recorder.failureReason());
                return;
            }
            if (ready && !stopping && workload != null) {
                try {
                    String health = workload.healthFailure();
                    if (health != null) { terminate("failed: " + schedule.id(index) + ": " + health); return; }
                } catch (RuntimeException e) { terminate("failed: workload health check: " + message(e)); return; }
            }
            refresh(stopping ? "正在释放资源…" : ready ? "测试进行中" : "正在启动负载…");
            control.postDelayed(this, 1000);
        }
    };

    private void advance() {
        if (terminating) return;
        index++;
        if (index == schedule.count()) { terminate("complete"); return; }
        int token = ++generation;
        ready = false;
        stopping = false;
        detail = "";
        recorder.startStage("setup_" + schedule.id(index), "Setup: " + schedule.label(index));
        refresh("正在启动负载…");
        if (index == 0) {
            workload = new BenchmarkWorkload() {
                @Override public void start(Callback callback) { callback.ready("Display stays on; system brightness preserved"); }
                @Override public void stop(Stopped callback) { callback.stopped(null); }
            };
        } else if (index == 1) workload = new AudioWorkloads.Microphone();
        else if (index == 2) workload = new AudioWorkloads.Speaker(context);
        else workload = new CameraWorkload(context, recorder.directory(), index == 3, control);
        control.postDelayed(() -> {
            if (token == generation && !ready && !terminating)
                terminate("failed: " + schedule.id(index) + " startup timed out after " + STARTUP_TIMEOUT_MS + " ms");
        }, STARTUP_TIMEOUT_MS);
        try {
            workload.start(new BenchmarkWorkload.Callback() {
                @Override public void ready(String facts) {
                    control.post(() -> {
                        if (token != generation || terminating || ready) return;
                        ready = true;
                        detail = facts;
                        stageStart = SystemClock.elapsedRealtime();
                        recorder.endStage("ready", facts);
                        recorder.startStage(schedule.id(index), schedule.label(index));
                        recorder.note("workload_ready", facts);
                        refresh("测试进行中");
                        control.postDelayed(() -> {
                            if (token == generation && !terminating) stopStage();
                        }, schedule.durationMs());
                    });
                }
                @Override public void failed(String reason) {
                    control.post(() -> {
                        if (token == generation && !terminating)
                            terminate("failed: " + schedule.id(index) + ": " + reason);
                    });
                }
            });
        } catch (Exception e) { terminate("failed: " + schedule.id(index) + ": " + message(e)); }
    }

    private void stopStage() {
        if (stopping || terminating) return;
        stopping = true;
        long stopRequested = SystemClock.elapsedRealtime();
        recorder.note("workload_stop_requested", "Measured run interval_ms=" + (stopRequested - stageStart));
        refresh("正在释放资源…");
        BenchmarkWorkload stoppingWorkload = workload;
        try {
            stoppingWorkload.stop(error -> control.post(() -> {
                if (terminating) return;
                workload = null;
                recorder.note("workload_cleanup_finished", "Cleanup elapsed_ms=" + (SystemClock.elapsedRealtime() - stopRequested));
                if (error != null) { terminate("failed: " + schedule.id(index) + " cleanup: " + error); return; }
                recorder.endStage("completed", detail);
                ready = false;
                advance();
            }));
        } catch (RuntimeException e) { terminate("failed: " + schedule.id(index) + " stop: " + message(e)); }
    }

    private void terminate(String reason) {
        if (terminating) return;
        terminating = true;
        running = false;
        generation++;
        finalReason = reason;
        control.removeCallbacks(tick);
        if (recorder != null) recorder.note("session_stop_requested", reason + "; active_workload_ms="
                + (ready ? SystemClock.elapsedRealtime() - stageStart : 0));
        refresh("正在保存结果…");
        long cleanupStarted = SystemClock.elapsedRealtime();
        BenchmarkWorkload closing = workload;
        workload = null;
        if (closing == null) finishRecording(null);
        else {
            try { closing.stop(error -> control.post(() -> {
                if (recorder != null) recorder.note("workload_cleanup_finished", "Cleanup elapsed_ms=" + (SystemClock.elapsedRealtime() - cleanupStarted));
                finishRecording(error);
            })); }
            catch (RuntimeException e) { finishRecording(message(e)); }
        }
    }

    private void finishRecording(String cleanupFailure) {
        if (cleanupFailure != null) finalReason += "; cleanup failed: " + cleanupFailure;
        if (recorder == null) {
            finished = true;
            refresh(finalReason);
            thread.quitSafely();
            return;
        }
        if (!"complete".equals(finalReason)) recorder.endStage(finalReason.startsWith("failed:") ? "failed" : "aborted", finalReason);
        recorder.finish(finalReason);
        awaitSave();
    }

    private void awaitSave() {
        if (!recorder.isFinished()) {
            refresh("正在保存结果…");
            control.postDelayed(this::awaitSave, 500);
            return;
        }
        finished = true;
        if (recorder.failureReason() != null) finalReason = "failed: telemetry: " + recorder.failureReason();
        refresh("complete".equals(finalReason) ? "全部测试已完成" : finalReason);
        thread.quitSafely();
    }

    private void refresh(String state) {
        String[] labels = {"仅亮屏", "亮屏＋麦克风", "亮屏＋连续播放", "亮屏＋无声录像", "亮屏＋定时拍照"};
        String stageLine = index >= 0 && index < schedule.count()
                ? String.format(Locale.US, "阶段 %d / %d  %s", index + 1, schedule.count(), labels[index])
                : String.format(Locale.US, "五阶段，每阶段 %.0f 秒", schedule.durationMs() / 1000.0);
        String timer = ready && !terminating
                ? String.format(Locale.US, "计时 %d / %d 秒", Math.max(0, (SystemClock.elapsedRealtime() - stageStart) / 1000), schedule.durationMs() / 1000)
                : String.format(Locale.US, "负载共 %d 秒，另计准备时间", schedule.totalDurationMs() / 1000);
        if (state.startsWith("failed:")) state = "测试失败，原因见结果文件";
        else if (state.startsWith("aborted:")) state = "测试已停止，结果已保存";
        String hint = index == 1 ? "仅读取音频，不保留录音" : index == 2 ? "循环播放，系统音量保持不变"
                : index == 3 ? "录像不含音轨，退出时封装保存" : index == 4 ? "每十秒拍照，照片保存在本地" : "保持屏幕常亮，亮度保持不变";
        List<String> screen = new ArrayList<>();
        screen.add("功耗测试");
        screen.add(stageLine);
        screen.add(state);
        if (finalReason != null && finalReason.startsWith("failed:")) screen.add(failureHint(finalReason));
        screen.add(timer);
        screen.add(recorder == null ? "正在准备电量采样" : recorder.displayStatus());
        screen.add(hint);
        screen.add("估算范围：整台设备");
        screen.add(finished ? "返回：回到主菜单" : "返回：停止并保存");
        if (finished && resultDirectory != null) {
            screen.add("下载 / RokidTests / ");
            screen.add(resultDirectory.getName());
        }
        lines = screen.toArray(new String[0]);
        if (onChanged != null) main.post(onChanged);
    }
    private static String failureHint(String reason) {
        String lower = reason.toLowerCase(Locale.US);
        if (lower.contains("volume")) return "请开启媒体音量后重试";
        if (lower.contains("permission") || lower.contains("securityexception")) return "请授予相机和麦克风权限";
        if (lower.contains("telemetry") || lower.contains("ioexception")) return "请检查设备存储及采样数据";
        if (lower.contains("timed out")) return "设备启动或采集超时";
        if (lower.contains("camera") || lower.contains("mp4")) return "相机不可用或媒体保存失败";
        if (lower.contains("microphone") || lower.contains("audiorecord")) return "麦克风无法持续采集音频";
        return "详细错误已记录到结果文件";
    }
    private static String message(Exception e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
}
