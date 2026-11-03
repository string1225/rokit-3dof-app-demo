package com.rokid.stabledemo;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.rokid.stabledemo.power.PowerRecorder;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImuRecordingInstrumentedTest {
    @Test public void menuTogglesAreExplicitAndDoNotStartSensors() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<MainActivity> current=new AtomicReference<>();
        Intent intent=new Intent(context,MainActivity.class).putExtra("follow_enabled",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
            try {
                scenario.onActivity(a -> {
                    current.set(a);
                    View root=a.findViewById(android.R.id.content);
                    Button follow=button(root,"浮窗跟随"),record=button(root,"IMU 录制");
                    assertNotNull(follow); assertNotNull(record);
                    assertTrue(record.getText().toString().endsWith("关"));
                    record.performClick(); assertTrue(record.getText().toString().endsWith("开"));
                    record.performClick(); assertTrue(record.getText().toString().endsWith("关"));
                    follow.performClick(); assertTrue(follow.getText().toString().endsWith("关"));
                    assertFalse(a.demoView().isRunning()); assertNull(a.powerBenchmark());
                    button(root,"3DoF").performClick();
                    assertTrue(a.demoView().getContentDescription().toString().contains("跟随关"));
                    a.getPreferences(Context.MODE_PRIVATE).edit().remove("follow_enabled").commit();
                });
            } finally { finish(current.get()); }
        }
    }

    @Test public void enabledCaptureExportsEveryFlushedRawAndPoseRowWhileDisabledCreatesNoImuFiles() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(int configuration=0;configuration<3;configuration++) {
            boolean enabled=configuration!=1,forceFusion=configuration==2;
            File runs=new File(context.getExternalFilesDir(null),"power-tests");
            Set<String> before=new HashSet<>(); File[] previous=runs.listFiles();
            if(previous!=null) for(File file:previous) before.add(file.getName());
            Intent intent=new Intent(context,MainActivity.class).putExtra("imu_recording_enabled",enabled)
                    .putExtra("gyro_prediction_ms",configuration==0?20:0)
                    .putExtra("force_fusion",forceFusion)
                    .putExtra("follow_enabled",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            AtomicReference<MainActivity> current=new AtomicReference<>();
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
                try {
                    scenario.onActivity(a -> { current.set(a); button(a.findViewById(android.R.id.content),"3DoF").performClick(); });
                    SystemClock.sleep(1700);
                    java.lang.reflect.Field field=MainActivity.class.getDeclaredField("recorder"); field.setAccessible(true);
                    AtomicReference<PowerRecorder> recording=new AtomicReference<>();
                    onMain(() -> { try { recording.set((PowerRecorder)field.get(current.get())); }
                        catch(IllegalAccessException e) { throw new AssertionError(e); } });
                    assertNotNull(recording.get());
                    onMain(() -> current.get().getOnBackPressedDispatcher().onBackPressed());
                    long deadline=SystemClock.elapsedRealtime()+15000;
                    while(!recording.get().isFinished() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
                    assertTrue("Both writers must close before session export completes",recording.get().isFinished());
                    File run=recording.get().directory(); assertFalse(before.contains(run.getName()));
                    File exported=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"RokidTests/"+run.getName());
                    JSONObject config=json(new File(run,"metadata.json"));
                    assertEquals(enabled,config.getBoolean("imu_recording_enabled")); assertFalse(config.getBoolean("follow_enabled"));
                    assertEquals(configuration==0?20:0,config.getInt("gyro_prediction_ms"));
                    if(enabled) {
                        for(String name:new String[]{"imu_raw.csv","imu_poses.csv","imu_metadata.json"})
                            assertArrayEquals("Export must exactly match final local data",Files.readAllBytes(new File(run,name).toPath()),Files.readAllBytes(new File(exported,name).toPath()));
                        String[] raw=lines(new File(run,"imu_raw.csv")),poses=lines(new File(run,"imu_poses.csv"));
                        assertTrue("Record actual raw sensor callbacks even on the system-fusion path",raw.length>10);
                        assertTrue("Record every fused pose, not just rendered frames",poses.length>5);
                        Set<Integer> types=new HashSet<>();
                        for(int i=1;i<raw.length;i++) {
                            String[] row=raw[i].split(",",-1); assertEquals(7,row.length);
                            assertTrue(Long.parseLong(row[0])>0); assertTrue(Long.parseLong(row[1])>=Long.parseLong(row[0]));
                            types.add(Integer.parseInt(row[2]));
                        }
                        assertTrue(types.contains(1)); assertTrue(types.contains(4));
                        for(int i=1;i<poses.length;i++) {
                            String[] row=poses[i].split(",",-1); assertEquals(13,row.length);
                            if(forceFusion) assertEquals("4",row[2]);
                        }
                        JSONObject info=json(new File(run,"imu_metadata.json"));
                        assertTrue(info.getBoolean("complete")); assertEquals("",info.getString("write_error"));
                        assertEquals(0,info.getLong("dropped_samples"));
                        assertEquals(raw.length-1,info.getLong("raw_samples_written"));
                        assertEquals(poses.length-1,info.getLong("pose_samples_written"));
                    } else for(String name:new String[]{"imu_raw.csv","imu_poses.csv","imu_metadata.json"}) {
                        assertFalse(new File(run,name).exists()); assertFalse(new File(exported,name).exists());
                    }
                } finally { finish(current.get()); }
            }
        }
    }
    private static JSONObject json(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)); }
    private static String[] lines(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).split("\n"); }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void finish(MainActivity activity) { if(activity!=null) onMain(activity::finish); }
    private static Button button(View view,String prefix) {
        if(view instanceof Button && ((Button)view).getText().toString().startsWith(prefix)) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),prefix); if(found!=null) return found;
        }
        return null;
    }
}
