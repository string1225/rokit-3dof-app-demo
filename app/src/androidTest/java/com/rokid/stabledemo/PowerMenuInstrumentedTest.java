package com.rokid.stabledemo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.rokid.stabledemo.power.PowerBenchmark;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PowerMenuInstrumentedTest {
    @Test public void menuLongPressKeyExitsWithoutStartingATest() {
        AtomicReference<MainActivity> current=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
          try {
            scenario.onActivity(current::set);
            key(KeyEvent.KEYCODE_PROG_BLUE);
            onMain(() -> {
                assertTrue("Menu long press must exit",current.get().isFinishing()||current.get().isDestroyed());
                assertFalse(current.get().demoView().isRunning());
                assertNull(current.get().powerBenchmark());
            });
          } finally { if(current.get()!=null) onMain(current.get()::finish); }
        }
    }

    @Test public void menuLongPressBroadcastExitsWithoutStartingATest() {
        AtomicReference<MainActivity> current=new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean exited=new java.util.concurrent.atomic.AtomicBoolean();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
          try {
            scenario.onActivity(current::set);
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            context.sendBroadcast(new Intent(MainActivity.LONG_PRESS).setPackage(context.getPackageName()));
            long deadline=SystemClock.elapsedRealtime()+2000;
            while(!exited.get() && SystemClock.elapsedRealtime()<deadline) {
                onMain(() -> exited.set(current.get().isFinishing()||current.get().isDestroyed()));
                if(!exited.get()) SystemClock.sleep(50);
            }
            assertTrue("Firmware menu long press must exit",exited.get());
            onMain(() -> {
                assertFalse(current.get().demoView().isRunning());
                assertNull(current.get().powerBenchmark());
            });
          } finally { if(current.get()!=null) onMain(current.get()::finish); }
        }
    }

    @Test public void glassesMenuSelectsPowerAndBackAbortsAndSaves() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String permission:new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO})
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),permission);
        Intent intent=new Intent(context,MainActivity.class).putExtra("benchmark_stage_seconds",12).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        AtomicReference<MainActivity> current=new AtomicReference<>(); AtomicReference<PowerBenchmark> test=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
          try {
            scenario.onActivity(current::set);
            key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_BACK);
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(test.get()==null && SystemClock.elapsedRealtime()<deadline) {
                onMain(() -> test.set(current.get().powerBenchmark())); SystemClock.sleep(50);
            }
            assertNotNull("Firmware menu keys must enter the selected power test",test.get());
            SystemClock.sleep(400); key(KeyEvent.KEYCODE_BACK);
            deadline=SystemClock.elapsedRealtime()+15000;
            while(!test.get().isFinished()&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
            assertTrue(test.get().isFinished());
            JSONObject report=new JSONObject(new String(Files.readAllBytes(new File(test.get().directory(),"summary.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(report.getString("finish_reason").startsWith("aborted:"));
            onMain(() -> assertTrue(button(current.get().findViewById(android.R.id.content),"3DoF 测试").isShown()));
          } finally { if(current.get()!=null) onMain(current.get()::finish); }
        }
    }

    @Test public void diagnosticCsvExportIncludesFinalFlush() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File runs=new File(context.getExternalFilesDir(null),"power-tests"); Set<String> before=new HashSet<>();
        File[] previous=runs.listFiles(); if(previous!=null) for(File f:previous) before.add(f.getName());
        AtomicReference<MainActivity> current=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
          try {
            scenario.onActivity(a -> { current.set(a); button(a.findViewById(android.R.id.content),"3DoF 测试").performClick(); });
            SystemClock.sleep(3500); key(KeyEvent.KEYCODE_BACK);
            File run=null; File[] now=runs.listFiles();
            if(now!=null) for(File f:now) if(!before.contains(f.getName())) run=f;
            assertNotNull(run);
            File exported=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"RokidTests/"+run.getName());
            long deadline=SystemClock.elapsedRealtime()+15000;
            File finalSummary=new File(exported,"summary.json");
            while(!finalSummary.isFile()&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
            assertTrue("Session export must complete",finalSummary.isFile());
            for(String name:new String[]{"motion_frames.csv","motion_metrics.csv"}) {
                byte[] local=Files.readAllBytes(new File(run,name).toPath());
                assertTrue("Motion CSV must contain live rows",new String(local,java.nio.charset.StandardCharsets.UTF_8).split("\n").length>2);
                assertArrayEquals("Final export must match all flushed bytes",local,Files.readAllBytes(new File(exported,name).toPath()));
            }
            JSONObject metadata=new JSONObject(new String(Files.readAllBytes(new File(run,"metadata.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("android",metadata.getString("axes"));
          } finally { if(current.get()!=null) onMain(current.get()::finish); }
        }
    }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void key(int code) {
        long t=SystemClock.uptimeMillis();
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(new KeyEvent(t,t,KeyEvent.ACTION_DOWN,code,0),true));
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(new KeyEvent(t,t+1,KeyEvent.ACTION_UP,code,0),true));
    }
    private static Button button(View view,String text) {
        if(view instanceof Button && text.contentEquals(((Button)view).getText())) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),text); if(found!=null) return found;
        }
        return null;
    }
}
