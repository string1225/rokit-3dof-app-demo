package com.rokid.stabledemo.power;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PowerRecorderInstrumentedTest {
    @Test public void finishIsIdempotentAndPersistsActualStageAndUnknownFields() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PowerRecorder recorder = new PowerRecorder(context, "recorder_instrumentation");
        recorder.metadata("requested_stage_duration_ms", 100);
        recorder.startStage("display", "Display test");
        SystemClock.sleep(150);
        recorder.endStage("completed", "Test ended normally");
        recorder.finish("complete");
        recorder.finish("must_not_replace_first_reason");
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        while (!recorder.isFinished() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue("Recorder final save timed out", recorder.isFinished());
        assertNull(recorder.failureReason());
        File directory = recorder.directory();
        JSONObject summary = new JSONObject(read(new File(directory, "summary.json")));
        assertEquals("complete", summary.getString("finish_reason"));
        assertEquals(100, summary.getJSONObject("metadata").getLong("requested_stage_duration_ms"));
        assertEquals("completed", summary.getJSONArray("stages").getJSONObject(0).getString("outcome"));
        assertTrue(summary.getJSONArray("stages").getJSONObject(0).getLong("actual_duration_ms") >= 100);
        assertTrue(summary.getInt("samples") > 0);
        assertTrue(read(new File(directory, "samples.csv")).startsWith("utc_ms,elapsed_ms,stage_id,"));
        String boundaries = read(new File(directory, "stages.jsonl"));
        assertTrue(boundaries.contains("stage_started"));
        assertTrue(boundaries.contains("stage_ended"));
        assertTrue(boundaries.contains("session_finished"));
        assertFalse("Export must leave a final status", summary.getString("export_status").equals("pending"));
        if (summary.getLong("energy_covered_ms") == 0) assertTrue(summary.isNull("estimated_device_energy_J"));
        assertBatteryDelta(summary);
        assertBatteryDelta(summary.getJSONArray("stages").getJSONObject(0));
    }

    static void assertBatteryDelta(JSONObject object) throws Exception {
        for (String field : new String[]{"first_level_pct", "last_level_pct", "observed_level_drop_pct",
                "first_charge_counter_uAh", "last_charge_counter_uAh", "observed_charge_drop_mAh",
                "charging_observed", "plugged_observed", "all_observed_samples_discharging"})
            assertTrue("Missing battery observation field: " + field, object.has(field));
        if (!object.isNull("observed_level_drop_pct"))
            assertEquals(object.getDouble("first_level_pct") - object.getDouble("last_level_pct"),
                    object.getDouble("observed_level_drop_pct"), 1e-9);
        if (!object.isNull("observed_charge_drop_mAh"))
            assertEquals((object.getLong("first_charge_counter_uAh") - object.getLong("last_charge_counter_uAh")) / 1000.0,
                    object.getDouble("observed_charge_drop_mAh"), 1e-9);
        if (!object.optBoolean("all_observed_samples_discharging", false)) {
            assertTrue(object.isNull("discharging_only_level_drop_pct"));
            assertTrue(object.isNull("discharging_only_charge_drop_mAh"));
        }
    }

    static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
