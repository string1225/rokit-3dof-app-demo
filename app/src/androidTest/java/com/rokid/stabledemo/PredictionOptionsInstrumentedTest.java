package com.rokid.stabledemo;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
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
    @Test public void liveTuningChangesHorizonAndModelWithoutRestartAndRemembersChoices() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit()
                .remove("prediction_enabled").remove("periodic_profile").remove("gyro_prediction_ms")
                .remove("gyro_model").remove("gyro_cap_deg").commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                View root=a.findViewById(android.R.id.content);
                Button periodic=button(root,"周期预测"),gyro=button(root,"陀螺外推");
                assertEquals("周期预测 · 关",periodic.getText().toString());
                assertEquals("陀螺外推 · 0 ms",gyro.getText().toString());
                for(String expected:new String[]{"原门槛","宽松","关"}) {
                    periodic.performClick(); assertTrue(periodic.getText().toString().endsWith(expected));
                }
                assertFalse(a.demoView().isRunning()); assertNull(a.powerBenchmark());
                float scale=Math.min(root.getWidth()/480f,root.getHeight()/640f);
                for(String prefix:new String[]{"3DoF","续航","周期预测","浮窗跟随","IMU 录制","陀螺外推"}) {
                    Button option=button(root,prefix); int[] xy=new int[2]; option.getLocationOnScreen(xy);
                    assertTrue("All options must remain inside the glasses safe region",xy[1]>=80*scale-1);
                    assertTrue("All options must remain inside the glasses safe region",xy[1]+option.getHeight()<=root.getHeight()-80*scale+1);
                }
                gyro.performClick(); assertTrue(a.demoView().isRunning());
                SeekBar slider=root.findViewWithTag("gyro_horizon_slider"); assertTrue(slider.isShown());
                assertEquals(100,slider.getMax());
                for(int i=0;i<5;i++) a.onKeyDown(KeyEvent.KEYCODE_DPAD_UP,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                assertEquals(50,slider.getProgress());
                assertTrue(a.demoView().getContentDescription().toString().contains("陀螺外推50ms"));
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                ((Button)root.findViewWithTag("gyro_cap")).performClick();
                assertTrue(a.demoView().getContentDescription().toString().contains("五采样"));
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                assertTrue(a.demoView().getContentDescription().toString().contains("十采样"));
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                assertEquals("10点趋势",((Button)root.findViewWithTag("gyro_model")).getText().toString());
                assertTrue(a.demoView().getContentDescription().toString().contains("趋势10"));
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                assertTrue(a.demoView().getContentDescription().toString().contains("恒速"));
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                assertTrue(a.demoView().getContentDescription().toString().contains("三采样"));
                ((Button)root.findViewWithTag("gyro_model")).performClick();
                a.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP));
                assertEquals(51,slider.getProgress());
                a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN));
                assertTrue(a.demoView().getContentDescription().toString().contains("3.0度"));
                for(int i=0;i<6;i++) a.onKeyDown(KeyEvent.KEYCODE_DPAD_UP,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                assertEquals(100,slider.getProgress());
                ((Button)root.findViewWithTag("gyro_tuner_done")).performClick();
                assertFalse(slider.isShown()); assertTrue(a.demoView().isRunning());
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                View root=a.findViewById(android.R.id.content);
                assertEquals("陀螺外推 · 100 ms",button(root,"陀螺外推").getText().toString());
                assertEquals("周期预测 · 关",button(root,"周期预测").getText().toString());
                a.getPreferences(Context.MODE_PRIVATE).edit().remove("prediction_enabled").remove("periodic_profile")
                        .remove("gyro_prediction_ms").remove("gyro_model").remove("gyro_cap_deg").commit();
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
                        active++; assertTrue(Double.parseDouble(row[header.indexOf("gyro_horizon_ms")])>=ms);
                        assertEquals("xyz",row[header.indexOf("gyro_axes")]);
                    }
                }
                assertEquals(ms>0,active>0);
            }
        }
    }
    @Test public void liveChangesKeepTheSameTrackingSessionAndExportExactPredictionTimes() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent=new Intent(context,MainActivity.class).putExtra("gyro_prediction_ms",0)
                .putExtra("gyro_model","latest").putExtra("gyro_cap_deg",.75f).putExtra("prediction_enabled",false)
                .putExtra("imu_recording_enabled",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        AtomicReference<MainActivity> current=new AtomicReference<>(); AtomicReference<PowerRecorder> recorder=new AtomicReference<>();
        AtomicReference<Object> tracker=new AtomicReference<>();
        java.lang.reflect.Field logField=MainActivity.class.getDeclaredField("recorder"),trackerField=MainActivity.class.getDeclaredField("tracker");
        logField.setAccessible(true); trackerField.setAccessible(true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
            scenario.onActivity(a -> {
                current.set(a); button(a.findViewById(android.R.id.content),"3DoF").performClick();
                try { recorder.set((PowerRecorder)logField.get(a)); tracker.set(trackerField.get(a)); }
                catch(IllegalAccessException e) { throw new AssertionError(e); }
            });
            SystemClock.sleep(1200);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MainActivity a=current.get(); View root=a.findViewById(android.R.id.content);
                ((Button)root.findViewWithTag("gyro_tuner_open")).performClick();
                SeekBar slider=root.findViewWithTag("gyro_horizon_slider"); Bundle argument=new Bundle();
                argument.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,17);
                assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),argument));
                assertEquals(17,slider.getProgress());
                for(int i=0;i<4;i++) ((Button)root.findViewWithTag("gyro_model")).performClick();
                ((Button)root.findViewWithTag("gyro_cap")).performClick();
                try { assertSame(recorder.get(),logField.get(a)); assertSame(tracker.get(),trackerField.get(a)); }
                catch(IllegalAccessException e) { throw new AssertionError(e); }
                ((Button)root.findViewWithTag("gyro_tuner_done")).performClick();
            });
            SystemClock.sleep(1200);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> current.get().getOnBackPressedDispatcher().onBackPressed());
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(!recorder.get().isFinished() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
            assertTrue(recorder.get().isFinished()); File directory=recorder.get().directory();
            String[] lines=read(new File(directory,"motion_frames.csv")).split("\n"); List<String> header=Arrays.asList(lines[0].split(",",-1));
            assertEquals(header.indexOf("baseline_residual_rotation_deg")+1,header.indexOf("gyro_trend_samples"));
            int before=0,after=0;
            for(int i=1;i<lines.length;i++) {
                String[] row=lines[i].split(",",-1); assertEquals(header.size(),row.length);
                double selected=Double.parseDouble(row[header.indexOf("gyro_selected_ms")]);
                if(selected==0) before++;
                if(selected==17) {
                    after++; assertEquals("trend10",row[header.indexOf("gyro_model")]); assertEquals(3,Double.parseDouble(row[header.indexOf("gyro_cap_deg")]),0);
                    assertNotEquals("not_selected",row[header.indexOf("gyro_trend_status")]);
                    assertTrue(Long.parseLong(row[header.indexOf("gyro_segment_start_elapsed_ns")])>0);
                    int trendSamples=Integer.parseInt(row[header.indexOf("gyro_trend_samples")]);
                    assertTrue(trendSamples==0 || trendSamples==3 || trendSamples==5);
                }
                if(Double.parseDouble(row[header.indexOf("gyro_horizon_ms")])>0) {
                    long source=Long.parseLong(row[header.indexOf("source_pose_elapsed_ns")]);
                    long read=Long.parseLong(row[header.indexOf("prediction_read_elapsed_ns")]);
                    long received=Long.parseLong(row[header.indexOf("gyro_received_elapsed_ns")]);
                    long target=Long.parseLong(row[header.indexOf("prediction_target_elapsed_ns")]);
                    assertTrue(read>=source && read>=received); assertEquals(17_000_000L,target-read);
                    assertEquals("xyz",row[header.indexOf("gyro_axes")]);
                    double norm=0; for(String part:new String[]{"applied_qx","applied_qy","applied_qz","applied_qw"}) norm+=Math.pow(Double.parseDouble(row[header.indexOf(part)]),2);
                    assertEquals(1,norm,.000003);
                }
            }
            assertTrue(before>5); assertTrue(after>5);
            String events=read(new File(directory,"stages.jsonl")); assertTrue(events.contains("prediction_options"));
            assertTrue(events.contains("gyro_prediction_ms=17")); assertTrue(events.contains("gyro_model=trend10"));
            assertTrue(read(new File(directory,"metadata.json")).contains("trend_policy"));
        } finally {
            context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().remove("gyro_prediction_ms")
                    .remove("gyro_model").remove("gyro_cap_deg").commit();
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
