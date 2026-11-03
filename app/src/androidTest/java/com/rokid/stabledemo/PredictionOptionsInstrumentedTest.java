package com.rokid.stabledemo;

import android.content.Context;
import android.content.Intent;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PredictionOptionsInstrumentedTest {
    @Test public void menuCyclesBothDirectionsIndependentlyAndRemembersChoices() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit()
                .remove("prediction_enabled").remove("periodic_profile").remove("gyro_prediction_ms").commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                View root=a.findViewById(android.R.id.content);
                Button periodic=button(root,"周期预测"),gyro=button(root,"陀螺外推");
                assertEquals("周期预测 · 宽松",periodic.getText().toString());
                assertEquals("陀螺外推 · 0 ms",gyro.getText().toString());
                for(String expected:new String[]{"关","原门槛","宽松"}) {
                    periodic.performClick(); assertTrue(periodic.getText().toString().endsWith(expected));
                }
                for(int expected:new int[]{15,20,0,15}) {
                    gyro.performClick(); assertEquals("陀螺外推 · "+expected+" ms",gyro.getText().toString());
                }
                assertFalse(a.demoView().isRunning()); assertNull(a.powerBenchmark());
                assertEquals("周期预测 · 宽松",periodic.getText().toString());
                float scale=Math.min(root.getWidth()/480f,root.getHeight()/640f);
                for(String prefix:new String[]{"3DoF","续航","周期预测","浮窗跟随","IMU 录制","陀螺外推"}) {
                    Button option=button(root,prefix); int[] xy=new int[2]; option.getLocationOnScreen(xy);
                    assertTrue("All options must remain inside the glasses safe region",xy[1]>=80*scale-1);
                    assertTrue("All options must remain inside the glasses safe region",xy[1]+option.getHeight()<=root.getHeight()-80*scale+1);
                }
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                View root=a.findViewById(android.R.id.content);
                assertEquals("陀螺外推 · 15 ms",button(root,"陀螺外推").getText().toString());
                assertEquals("周期预测 · 宽松",button(root,"周期预测").getText().toString());
                a.getPreferences(Context.MODE_PRIVATE).edit().remove("prediction_enabled").remove("periodic_profile").remove("gyro_prediction_ms").commit();
            });
        }
    }

    @Test public void allThreeHorizonsReachRenderingAndExportTheirActualUseWithoutImuRecording() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(int ms:new int[]{0,15,20}) {
            Intent intent=new Intent(context,MainActivity.class).putExtra("gyro_prediction_ms",ms)
                    .putExtra("prediction_enabled",false).putExtra("periodic_profile","relaxed")
                    .putExtra("imu_recording_enabled",false).putExtra("force_fusion",ms==20).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            AtomicReference<PowerRecorder> recording=new AtomicReference<>();
            AtomicReference<MainActivity> current=new AtomicReference<>();
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("recorder"); field.setAccessible(true);
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
                scenario.onActivity(a -> {
                    current.set(a);
                    button(a.findViewById(android.R.id.content),"3DoF").performClick();
                    assertTrue(a.demoView().getContentDescription().toString().contains("陀螺外推"+ms+"ms"));
                    try { recording.set((PowerRecorder)field.get(a)); } catch(IllegalAccessException e) { throw new AssertionError(e); }
                });
                SystemClock.sleep(ms==20?5500:1800);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    // Avoid waiting for an idle queue while the 3DoF view renders continuously.
                    current.get().getOnBackPressedDispatcher().onBackPressed();
                });
                long deadline=SystemClock.elapsedRealtime()+15000;
                while(!recording.get().isFinished() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
                assertTrue(recording.get().isFinished());
                File directory=recording.get().directory();
                JSONObject metadata=new JSONObject(read(new File(directory,"metadata.json")));
                assertEquals(ms,metadata.getInt("gyro_prediction_ms"));
                assertEquals(.88,metadata.getDouble("periodic_min_quality"),0);
                assertFalse(new File(directory,"imu_raw.csv").exists());
                String[] lines=read(new File(directory,"motion_frames.csv")).split("\n");
                assertTrue(lines.length>5);
                List<String> header=Arrays.asList(lines[0].split(",",-1)); int active=0;
                for(int i=1;i<lines.length;i++) {
                    String[] row=lines[i].split(",",-1); assertEquals(header.size(),row.length);
                    assertEquals(ms,Double.parseDouble(row[header.indexOf("gyro_selected_ms")]),0);
                    assertEquals(0,Double.parseDouble(row[header.indexOf("periodic_horizon_ms")]),0);
                    if("gyro".equals(row[header.indexOf("prediction_method")])) {
                        active++; assertEquals(ms,Double.parseDouble(row[header.indexOf("gyro_horizon_ms")]),0);
                    }
                }
                assertEquals(ms>0,active>0);
            }
        }
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8); }
    private static Button button(View view,String prefix) {
        if(view instanceof Button && ((Button)view).getText().toString().startsWith(prefix)) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),prefix); if(found!=null) return found;
        }
        return null;
    }
}
