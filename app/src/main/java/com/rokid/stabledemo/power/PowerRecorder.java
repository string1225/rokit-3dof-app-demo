package com.rokid.stabledemo.power;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** A foreground-session recorder. All numbers describe the whole device, including other apps. */
public final class PowerRecorder {
    private static final long SAMPLE_MS = 5000;
    private final Context context;
    private final File directory;
    private final long startedElapsed = SystemClock.elapsedRealtime();
    private final long startedUtc = System.currentTimeMillis();
    private final HandlerThread thread = new HandlerThread("power-telemetry");
    private final Handler worker;
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final BatteryEnergy energy = new BatteryEnergy();
    private final BatteryDelta batteryDelta = new BatteryDelta();
    private final List<Stage> stages = new ArrayList<>();
    private final JSONObject metadata = new JSONObject();
    private final Map<String, Integer> missing = new LinkedHashMap<>();
    private final FileOutputStream samples;
    private final FileOutputStream boundaries;
    private Stage stage;
    private int sampleCount;
    private volatile String failure;
    private volatile String display = "电量采样中…";
    private volatile boolean finished;
    private volatile String exportStatus = "pending";
    private String finishReason = "running";
    private long finishedElapsed;

    public PowerRecorder(Context context, String mode) throws IOException {
        this.context = context.getApplicationContext();
        File external = this.context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app files storage unavailable");
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        directory = new File(new File(external, "power-tests"), name);
        if (!directory.mkdirs()) throw new IOException("Cannot create " + directory);
        FileOutputStream csv = null;
        FileOutputStream events = null;
        try {
            csv = new FileOutputStream(new File(directory, "samples.csv"));
            events = new FileOutputStream(new File(directory, "stages.jsonl"));
            write(csv, "utc_ms,elapsed_ms,stage_id,level_pct,voltage_mV,temperature_C,charge_counter_uAh,current_now_uA,battery_status,plugged,thermal_status,device_discharge_W\n");
            csv.getFD().sync();
            deviceMetadata(mode);
            writeJson(new File(directory, "metadata.json"), metadata);
        } catch (Exception e) {
            if (csv != null) try { csv.close(); } catch (IOException ignored) { }
            if (events != null) try { events.close(); } catch (IOException ignored) { }
            throw new IOException("Cannot initialize power telemetry", e);
        }
        samples = csv;
        boundaries = events;
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            checkpoint("running");
            sampleSafely();
        });
    }

    /** Timestamp is captured at the call, then written in order on the telemetry thread. */
    public void startStage(String id, String label) {
        if (finishing.get()) return;
        long now = SystemClock.elapsedRealtime();
        worker.post(() -> {
            if (finishedElapsed != 0) return;
            if (stage != null) closeStage(now, "ended", "Next stage began");
            energy.breakContinuity();
            stage = new Stage(id, label, now);
            stages.add(stage);
            event("stage_started", stage, now, "running", "");
            checkpoint("running");
            sampleOnceSafely();
        });
    }

    /** Additional facts about the requested workload, without changing the generic recorder API. */
    public void metadata(String key, Object value) {
        if (finishing.get()) return;
        worker.post(() -> {
            if (finishedElapsed != 0) return;
            put(metadata, key, value);
            try { writeJson(new File(directory, "metadata.json"), metadata); }
            catch (IOException e) { failed(e); }
        });
    }

    public void endStage(String outcome, String detail) {
        if (finishing.get()) return;
        long now = SystemClock.elapsedRealtime();
        worker.post(() -> {
            if (finishedElapsed != 0) return;
            sampleOnceSafely();
            closeStage(now, outcome, detail);
            checkpoint("running");
        });
    }

    public void note(String kind, String detail) {
        if (finishing.get()) return;
        long now = SystemClock.elapsedRealtime();
        worker.post(() -> { if (finishedElapsed == 0) event(kind, stage, now, "", detail); });
    }

    /** Returns immediately; the recorder retains itself until final save and export complete. */
    public void finish(String reason) {
        if (!finishing.compareAndSet(false, true)) return;
        long now = SystemClock.elapsedRealtime();
        worker.post(() -> {
            worker.removeCallbacks(sampler);
            finishedElapsed = now;
            finishReason = reason == null ? "finished" : reason;
            sampleOnceSafely();
            closeStage(now, "complete".equals(finishReason) ? "completed" : "aborted", finishReason);
            event("session_finished", null, now, failure == null ? finishReason : "telemetry_failure", failure);
            try { samples.getFD().sync(); } catch (IOException e) { failed(e); }
            try { samples.close(); } catch (IOException e) { failed(e); }
            try { boundaries.close(); } catch (IOException e) { failed(e); }
            checkpoint(failure == null ? finishReason : "telemetry_failure");
            exportFiles();
            finished = true;
            thread.quitSafely();
        });
    }

    public String displayStatus() {
        if (failure != null) return "电量采样失败，已保留本地结果";
        if (finished) return exportStatus.startsWith("failed:") ? "已保存在本地；导出失败" : "结果已保存并导出至下载目录";
        if (finishing.get()) return "正在保存和导出结果…";
        return display;
    }
    public File directory() { return directory; }
    public boolean isFinished() { return finished; }
    public String failureReason() { return failure; }

    private final Runnable sampler = this::sampleSafely;
    private void sampleSafely() {
        sampleOnceSafely();
        if (!finishing.get() && failure == null) worker.postDelayed(sampler, SAMPLE_MS);
    }
    private void sampleOnceSafely() {
        try { sample(); } catch (Exception e) { failed(e); }
    }

    private void sample() throws IOException {
        long elapsed = SystemClock.elapsedRealtime();
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int level = extra(battery, BatteryManager.EXTRA_LEVEL);
        int scale = extra(battery, BatteryManager.EXTRA_SCALE);
        Double percent = level >= 0 && scale > 0 && level <= scale ? level * 100.0 / scale : null;
        Integer voltage = validRange(extra(battery, BatteryManager.EXTRA_VOLTAGE), 1000, 20_000);
        Integer rawTemp = validRange(extra(battery, BatteryManager.EXTRA_TEMPERATURE), -500, 1500);
        Double temperature = rawTemp == null ? null : rawTemp / 10.0;
        Integer charge = property(manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (charge != null && charge < 0) charge = null;
        Integer current = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        int status = extra(battery, BatteryManager.EXTRA_STATUS);
        int plugged = extra(battery, BatteryManager.EXTRA_PLUGGED);
        Integer thermal = null;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) thermal = power.getCurrentThermalStatus();
        } catch (RuntimeException ignored) { }
        boolean discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING && plugged == 0;
        boolean knownStatus = status >= BatteryManager.BATTERY_STATUS_CHARGING
                && status <= BatteryManager.BATTERY_STATUS_FULL;
        Boolean chargingObserved = knownStatus ? status == BatteryManager.BATTERY_STATUS_CHARGING : null;
        Boolean pluggedObserved = plugged == Integer.MIN_VALUE ? null : plugged != 0;
        Boolean dischargeObserved = knownStatus && pluggedObserved != null ? discharging : null;
        long observedElapsed = elapsed - startedElapsed;
        batteryDelta.sample(observedElapsed, percent, charge, chargingObserved, pluggedObserved, dischargeObserved);
        if (stage != null) stage.batteryDelta.sample(observedElapsed, percent, charge,
                chargingObserved, pluggedObserved, dischargeObserved);
        int ua = current == null ? Integer.MIN_VALUE : current;
        int mv = voltage == null ? Integer.MIN_VALUE : voltage;
        energy.sample(elapsed, ua, mv, discharging);
        if (stage != null) stage.energy.sample(elapsed, ua, mv, discharging);
        Double watts = discharging && current != null && current < 0 && voltage != null
                ? -(double) current * voltage / 1_000_000_000.0 : null;
        countMissing("level_pct", percent);
        countMissing("voltage_mV", voltage);
        countMissing("temperature_C", temperature);
        countMissing("charge_counter_uAh", charge);
        countMissing("current_now_uA", current);
        countMissing("thermal_status", thermal);
        countMissing("battery_status", status == Integer.MIN_VALUE ? null : status);
        countMissing("plugged", plugged == Integer.MIN_VALUE ? null : plugged);
        String row = System.currentTimeMillis() + "," + (elapsed - startedElapsed) + ","
                + csv(stage == null ? "" : stage.id) + "," + value(percent) + "," + value(voltage)
                + "," + value(temperature) + "," + value(charge) + "," + value(current) + ","
                + value(status == Integer.MIN_VALUE ? null : status) + ","
                + value(plugged == Integer.MIN_VALUE ? null : plugged) + "," + value(thermal)
                + "," + value(watts) + "\n";
        write(samples, row);
        samples.getFD().sync();
        sampleCount++;
        String powerText = watts == null ? "功率未知" : String.format(Locale.US, "%.2f W", watts);
        String levelText = percent == null ? "未知" : String.format(Locale.US, "%.0f%%", percent);
        Double observedDrop = batteryDelta.levelDrop();
        display = observedDrop == null ? String.format(Locale.US, "电量%s  %s", levelText, powerText)
                : String.format(Locale.US, "电量%s Δ%+.1f%% %s", levelText, observedDrop, powerText);
    }

    private void deviceMetadata(String mode) {
        put(metadata, "schema_version", 1);
        put(metadata, "mode", mode);
        put(metadata, "started_utc_ms", startedUtc);
        put(metadata, "sample_interval_ms", SAMPLE_MS);
        put(metadata, "manufacturer", Build.MANUFACTURER);
        put(metadata, "model", Build.MODEL);
        put(metadata, "device", Build.DEVICE);
        put(metadata, "android_release", Build.VERSION.RELEASE);
        put(metadata, "sdk_int", Build.VERSION.SDK_INT);
        put(metadata, "build_fingerprint", Build.FINGERPRINT);
        put(metadata, "package", context.getPackageName());
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        put(metadata, "display_width_px", metrics.widthPixels);
        put(metadata, "display_height_px", metrics.heightPixels);
        put(metadata, "display_density_dpi", metrics.densityDpi);
        put(metadata, "system_screen_brightness", Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
        put(metadata, "system_screen_brightness_mode", Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1));
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            put(metadata, "music_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            put(metadata, "music_volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            put(metadata, "music_muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        }
        put(metadata, "measurement_scope", "Whole-device battery telemetry; not app-specific power. Includes display, OS, other apps and hardware.");
        put(metadata, "energy_method", "Trapezoidal integral of negative current_now_uA * voltage_mV only while battery status is DISCHARGING and unplugged. Unsupported/zero/positive current, charging, unknown values and gaps >15 s are excluded. No extrapolation or capacity assumptions.");
        put(metadata, "battery_delta_method", "Observed drops = first supported reading minus last supported reading; positive is a decrease and negative is an increase. charge_counter_uAh drop is divided by 1000 for mAh. Missing or fewer than two observations at distinct times yields null. These are device-level net observed changes, including charging and fuel-gauge recalibration, not app consumption or energy. Discharging-only fields repeat the signed observed delta only if EVERY sampled state was known DISCHARGING and unplugged; otherwise null. Even qualified negative drops remain negative, never converted to consumption. Unknown intervals between snapshots are not validated.");
        put(metadata, "display_policy", "Caller keeps the display on; brightness and system audio volume are preserved.");
        put(metadata, "local_directory", directory.getAbsolutePath());
    }

    private void closeStage(long now, String outcome, String detail) {
        if (stage == null) return;
        stage.end = now;
        stage.outcome = outcome;
        stage.detail = detail;
        event("stage_ended", stage, now, outcome, detail);
        stage = null;
        energy.breakContinuity();
    }

    private void event(String kind, Stage item, long now, String outcome, String detail) {
        JSONObject record = new JSONObject();
        put(record, "event", kind);
        put(record, "elapsed_ms", now - startedElapsed);
        put(record, "utc_ms", startedUtc + now - startedElapsed);
        put(record, "stage_id", item == null ? null : item.id);
        put(record, "outcome", outcome);
        put(record, "detail", detail);
        try { write(boundaries, record + "\n"); boundaries.getFD().sync(); }
        catch (IOException e) { failed(e); }
    }

    private void checkpoint(String outcome) {
        try { writeJson(new File(directory, "summary.json"), summary(outcome)); }
        catch (IOException e) { failed(e); }
    }

    private JSONObject summary(String outcome) {
        long now = SystemClock.elapsedRealtime();
        JSONObject result = new JSONObject();
        put(result, "schema_version", 1);
        put(result, "outcome", outcome);
        put(result, "finish_reason", finishReason);
        put(result, "started_utc_ms", startedUtc);
        put(result, "actual_elapsed_ms", (finishedElapsed == 0 ? now : finishedElapsed) - startedElapsed);
        put(result, "finished_utc_ms", finishedElapsed == 0 ? null : startedUtc + finishedElapsed - startedElapsed);
        put(result, "samples", sampleCount);
        put(result, "telemetry_failure", failure);
        put(result, "measurement_scope", "DEVICE LEVEL estimate; no per-app energy attribution");
        put(result, "stage_duration_policy", "actual_duration_ms includes cleanup after the timed workload. The workload_stop_requested and workload_cleanup_finished events in stages.jsonl give measured workload and cleanup durations separately. Setup has separate setup_* stages.");
        energyJson(result, energy);
        batteryDeltaJson(result, batteryDelta);
        JSONObject unavailable = new JSONObject();
        for (Map.Entry<String, Integer> entry : missing.entrySet()) put(unavailable, entry.getKey(), entry.getValue());
        put(result, "missing_samples_by_field", unavailable);
        put(result, "unavailable_policy", "CSV missing fields are blank; JSON unavailable energy is null; values are never invented. Valid energy covers only reported intervals.");
        put(result, "export_status", exportStatus);
        put(result, "metadata", metadata);
        JSONArray list = new JSONArray();
        for (Stage item : stages) {
            JSONObject object = new JSONObject();
            put(object, "id", item.id);
            put(object, "label", item.label);
            put(object, "start_elapsed_ms", item.start - startedElapsed);
            put(object, "end_elapsed_ms", item.end == 0 ? null : item.end - startedElapsed);
            put(object, "actual_duration_ms", (item.end == 0 ? now : item.end) - item.start);
            put(object, "outcome", item.outcome);
            put(object, "detail", item.detail);
            energyJson(object, item.energy);
            batteryDeltaJson(object, item.batteryDelta);
            list.put(object);
        }
        put(result, "stages", list);
        return result;
    }

    private static void energyJson(JSONObject json, BatteryEnergy energy) {
        put(json, "estimated_device_energy_J", energy.coveredMillis() > 0 ? energy.joules() : null);
        put(json, "estimated_device_energy_Wh", energy.coveredMillis() > 0 ? energy.joules() / 3600.0 : null);
        put(json, "energy_covered_ms", energy.coveredMillis());
        put(json, "average_device_power_W_over_covered_intervals", energy.coveredMillis() > 0
                ? energy.joules() * 1000.0 / energy.coveredMillis() : null);
    }

    private static void batteryDeltaJson(JSONObject json, BatteryDelta observations) {
        put(json, "first_level_pct", observations.firstLevel());
        put(json, "last_level_pct", observations.lastLevel());
        put(json, "observed_level_drop_pct", observations.levelDrop());
        put(json, "first_level_observed_elapsed_ms", observations.firstLevelElapsed());
        put(json, "last_level_observed_elapsed_ms", observations.lastLevelElapsed());
        put(json, "level_observation_count", observations.levelCount());
        put(json, "first_charge_counter_uAh", observations.firstCharge());
        put(json, "last_charge_counter_uAh", observations.lastCharge());
        put(json, "observed_charge_drop_mAh", observations.chargeDropMah());
        put(json, "first_charge_observed_elapsed_ms", observations.firstChargeElapsed());
        put(json, "last_charge_observed_elapsed_ms", observations.lastChargeElapsed());
        put(json, "charge_counter_observation_count", observations.chargeCount());
        put(json, "charging_observed", observations.chargingObserved());
        put(json, "plugged_observed", observations.pluggedObserved());
        put(json, "all_observed_samples_discharging", observations.allSamplesDischarging());
        put(json, "unknown_power_state_samples", observations.unknownPowerStateSamples());
        put(json, "discharging_only_level_drop_pct", observations.dischargingLevelDrop());
        put(json, "discharging_only_charge_drop_mAh", observations.dischargingChargeDropMah());
        put(json, "battery_delta_scope", "SIGNED DEVICE-LEVEL OBSERVED DELTA, not app consumption. Discharging-only fields require all sampled states to be known discharging/unplugged; between-sample charging is not observable.");
    }

    private void exportFiles() {
        try {
            exportStatus = "exporting";
            for (String name : new String[]{"samples.csv", "metadata.json", "stages.jsonl"}) exportFile(name);
            // Caller completes MotionLog's final flush/close before finish(), so copy exact bytes.
            for (String name : new String[]{"motion_frames.csv", "motion_metrics.csv"}) {
                if (new File(directory, name).isFile()) exportFile(name);
            }
            exportStatus = "Downloads/RokidTests/" + directory.getName();
            checkpoint(failure == null ? finishReason : "telemetry_failure");
            exportFile("summary.json");
        } catch (Exception e) {
            exportStatus = "failed: " + message(e) + "; local files preserved";
            checkpoint(failure == null ? finishReason : "telemetry_failure");
        }
    }

    private void exportFile(String name) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, name.endsWith("csv") ? "text/csv" : name.endsWith("jsonl") ? "application/x-ndjson" : "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RokidTests/" + directory.getName());
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Downloads insertion returned null");
        try {
            try (FileInputStream input = new FileInputStream(new File(directory, name));
                 java.io.OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException("Downloads output unavailable");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, complete, null, null) < 1) throw new IOException("Downloads publish failed");
        } catch (Exception e) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { }
            throw new IOException("Export " + name + ": " + message(e), e);
        }
    }

    private void countMissing(String field, Object value) {
        if (value == null) missing.put(field, missing.getOrDefault(field, 0) + 1);
        else if (!missing.containsKey(field)) missing.put(field, 0);
    }
    private void failed(Exception e) { if (failure == null) failure = message(e); }
    private static String message(Exception e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
    private static int extra(Intent intent, String key) { return intent == null ? Integer.MIN_VALUE : intent.getIntExtra(key, Integer.MIN_VALUE); }
    private static Integer validRange(int v, int low, int high) { return v >= low && v <= high ? v : null; }
    private static Integer property(BatteryManager manager, int property) {
        if (manager == null) return null;
        try { int v = manager.getIntProperty(property); return v == Integer.MIN_VALUE ? null : v; }
        catch (RuntimeException e) { return null; }
    }
    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON value: " + key, e); }
    }
    private static void write(FileOutputStream stream, String value) throws IOException {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeJson(File target, JSONObject value) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            write(output, value.toString());
            output.getFD().sync();
        }
        if (!temp.renameTo(target)) throw new IOException("Cannot replace " + target.getName());
    }
    private static final class Stage {
        final String id;
        final String label;
        final long start;
        final BatteryEnergy energy = new BatteryEnergy();
        final BatteryDelta batteryDelta = new BatteryDelta();
        long end;
        String outcome = "running";
        String detail = "";
        Stage(String id, String label, long start) { this.id = id; this.label = label; this.start = start; }
    }
}
