package com.rokid.stabledemo.power;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PowerBenchmarkInstrumentedTest {
    /** Invoke explicitly for a 60 s + setup real-media exercise. Music volume must be nonzero. */
    @Test public void allFiveRealWorkloadsCompleteAndFinalizeMedia() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        assertTrue("Set nonzero test music volume before running this real-workload fixture", originalVolume > 0);
        assertFalse("Unmute test music volume before this fixture", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(launch);
        instrumentation.runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        PowerBenchmark benchmark = new PowerBenchmark(context, 12_000, null);
        try {
            benchmark.start();
            long deadline = SystemClock.elapsedRealtime() + 160_000;
            long nextLog = 0;
            while (!benchmark.isFinished() && SystemClock.elapsedRealtime() < deadline) {
                if (SystemClock.elapsedRealtime() >= nextLog) {
                    Log.i("PowerBenchmarkTest", Arrays.toString(benchmark.screenLines()));
                    nextLog = SystemClock.elapsedRealtime() + 5000;
                }
                SystemClock.sleep(100);
            }
            assertTrue("Five-stage benchmark timed out", benchmark.isFinished());
            assertFalse(benchmark.isRunning());
            File directory = benchmark.directory();
            assertNotNull(directory);
            JSONObject summary = new JSONObject(PowerRecorderInstrumentedTest.read(new File(directory, "summary.json")));
            assertEquals(summary.toString(), "complete", summary.getString("finish_reason"));
            assertEquals("complete", summary.getString("outcome"));
            assertEquals(12_000L, summary.getJSONObject("metadata").getLong("requested_stage_duration_ms"));
            assertEquals(60_000L, summary.getJSONObject("metadata").getLong("requested_workload_total_ms"));
            PowerRecorderInstrumentedTest.assertBatteryDelta(summary);
            JSONArray stages = summary.getJSONArray("stages");
            int workloads = 0;
            for (int i = 0; i < stages.length(); i++) {
                JSONObject stage = stages.getJSONObject(i);
                if (stage.getString("id").startsWith("setup_")) continue;
                workloads++;
                assertEquals(stage.toString(), "completed", stage.getString("outcome"));
                assertTrue(stage.toString(), stage.getLong("actual_duration_ms") >= 11_500);
                PowerRecorderInstrumentedTest.assertBatteryDelta(stage);
            }
            assertEquals(5, workloads);
            File mp4 = new File(directory, "silent-video.mp4");
            assertTrue("Finalized MP4 must be nonempty", mp4.length() > 1000);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(mp4.getAbsolutePath());
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
                assertNull("Silent video must contain no audio track", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                assertTrue(Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) >= 11_000);
            } finally { retriever.release(); }
            File[] photos = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
            assertNotNull(photos);
            assertTrue("Immediate plus 10 s photo expected", photos.length >= 2);
            for (File photo : photos) {
                byte[] bytes = java.nio.file.Files.readAllBytes(photo.toPath());
                assertTrue(bytes.length > 100);
                assertEquals(0xff, bytes[0] & 0xff);
                assertEquals(0xd8, bytes[1] & 0xff);
            }
            assertEquals("Application must preserve system music volume", originalVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally {
            benchmark.abort("instrumentation cleanup");
            long cleanupDeadline = SystemClock.elapsedRealtime() + 30_000;
            while (!benchmark.isFinished() && SystemClock.elapsedRealtime() < cleanupDeadline) SystemClock.sleep(50);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void abortDuringSetupOrActiveWorkloadSavesAbortedOutcome() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PowerBenchmark benchmark = new PowerBenchmark(context, 12_000, null);
        benchmark.start();
        SystemClock.sleep(200);
        benchmark.abort("instrumented background cancellation");
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        while (!benchmark.isFinished() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue(benchmark.isFinished());
        assertFalse(benchmark.isRunning());
        assertNotNull(benchmark.directory());
        JSONObject summary = new JSONObject(PowerRecorderInstrumentedTest.read(new File(benchmark.directory(), "summary.json")));
        assertTrue(summary.getString("finish_reason").startsWith("aborted:"));
        JSONArray stages = summary.getJSONArray("stages");
        assertTrue(stages.length() > 0);
        assertEquals("aborted", stages.getJSONObject(stages.length() - 1).getString("outcome"));
    }
}
