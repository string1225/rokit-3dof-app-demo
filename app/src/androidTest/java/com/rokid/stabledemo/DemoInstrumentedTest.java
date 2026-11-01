package com.rokid.stabledemo;

import android.content.Intent;
import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.rokid.stabledemo.core.Quaternion;
import com.rokid.stabledemo.core.LevelFollow;
import com.rokid.stabledemo.core.MotionDiagnostics;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DemoInstrumentedTest {
    @Test public void offscreenPanelReappearsAtCenterOnTheSameFrame() throws Exception {
        java.lang.reflect.Method publish=TrackingController.class.getDeclaredMethod(
                "publish",Quaternion.class,long.class,boolean.class);
        publish.setAccessible(true);
        onMain(() -> {
            android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            TrackingController tracker=new TrackingController(context,false,false);
            DemoView view=new DemoView(context,30,1.5,12,() -> {},() -> {},() -> {});
            Bitmap bitmap=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);
            Quaternion upright=Quaternion.axisAngle(1,0,0,Math.PI/2);
            try {
                view.layout(0,0,480,640); view.begin(tracker,new MotionDiagnostics());
                publish.invoke(tracker,upright,SystemClock.elapsedRealtimeNanos(),false);
                view.draw(canvas);
                assertTrue("Baseline panel must be centered",Color.green(bitmap.getPixel(30,320))>128);
                bitmap.eraseColor(Color.BLACK);
                Quaternion turned=upright.multiply(Quaternion.axisAngle(0,1,0,Math.toRadians(16)));
                publish.invoke(tracker,turned,SystemClock.elapsedRealtimeNanos(),false);
                view.draw(canvas);
                assertTrue("Crossing 50% horizontal offset must recenter within this rendered frame",
                        Color.green(bitmap.getPixel(30,320))>128);
            } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { view.end(); bitmap.recycle(); }
        });
    }

    @Test public void tiltedStartupMustNotBecomeTheHorizontalReference() throws Exception {
        TrackingController tracker=new TrackingController(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),false,false);
        Quaternion absolute=Quaternion.axisAngle(0,0,1,.7)
                .multiply(Quaternion.axisAngle(1,0,0,Math.PI/2))
                .multiply(Quaternion.axisAngle(0,1,0,.3))
                .multiply(Quaternion.axisAngle(1,0,0,-.15))
                .multiply(Quaternion.axisAngle(0,0,1,Math.toRadians(-2)));
        // Inject a fused sensor pose without depending on the emulator's physical orientation.
        java.lang.reflect.Method publish=TrackingController.class.getDeclaredMethod(
                "publish",Quaternion.class,long.class,boolean.class);
        publish.setAccessible(true);
        long now=SystemClock.elapsedRealtimeNanos();
        publish.invoke(tracker,absolute,now,false);
        TrackingController.Snapshot state=tracker.latest();
        assertTrue(state.ready);
        LevelFollow follower=new LevelFollow(1.5,12);
        follower.recenter(state.relative,now);
        LevelFollow.Result frame=follower.update(state.relative,now+16_000_000L);
        Quaternion panelInWorld=absolute.multiply(frame.compensatedHead.inverse());
        assertEquals("A startup head tilt must not tilt the floating panel relative to gravity",
                0,panelInWorld.rotate(new double[]{1,0,0})[2],1e-8);
    }

    @Test public void nativeFusionConvergesAndIntegratesYaw() {
        long handle=FusionBridge.create(200,35);
        assertTrue(handle != 0);
        float[] q=new float[5];
        try {
            for (int i=0;i<850;i++) FusionBridge.update(handle,0,0,0,0,0,9.80665f,.005f,q);
            assertEquals(0,q[4],0);
            assertEquals(1,Math.abs(q[3]),.002);
            for (int i=0;i<200;i++) FusionBridge.update(handle,0,0,(float)(Math.PI/2),0,0,9.80665f,.005f,q);
            assertEquals(Math.sqrt(.5),Math.abs(q[2]),.01);
            assertEquals(Math.sqrt(.5),Math.abs(q[3]),.01);
        } finally { FusionBridge.destroy(handle); }
    }

    @Test public void nativeFusionRejectsInvalidInput() {
        long handle=FusionBridge.create(200,35);
        float[] sentinel={7,7,7,7,7};
        try {
            FusionBridge.update(handle,0,0,0,0,0,9.8f,1,sentinel);
            assertEquals(7,sentinel[3],0);
            FusionBridge.update(handle,Float.NaN,0,0,0,0,9.8f,.005f,sentinel);
            assertEquals(7,sentinel[3],0);
        } finally { FusionBridge.destroy(handle); }
    }

    @Test public void twoModeMenuAndAccessibleTogglePreserveText() {
        AtomicReference<MainActivity> current=new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
          try {
            scenario.onActivity(a -> {
                current.set(a);
                Button button=findButton(a.findViewById(android.R.id.content));
                assertNotNull(button); assertEquals("3DoF 测试",button.getText().toString());
                assertEquals(2,countButtons(a.findViewById(android.R.id.content)));
                assertFalse(a.demoView().isRunning()); button.performClick();
                assertTrue(a.demoView().isRunning()); assertFalse(button.isShown());
            });
            awaitText(current.get(),8);
            onMain(() -> {
                MainActivity a=current.get();
                String before=a.demoView().visibleText();
                a.demoView().performClick();
                assertFalse("Accessibility click must toggle stabilization",a.demoView().isStabilized());
                assertEquals(before,a.demoView().visibleText());
            });
            pressKey(KeyEvent.KEYCODE_BACK);
            onMain(() -> {
                MainActivity a=current.get();
                assertFalse(a.demoView().isRunning());
                assertTrue(findButton(a.findViewById(android.R.id.content)).isShown());
            });
          } finally { finish(current.get()); }
        }
    }

    @Test public void forcedRawFusionAndBackgroundResume() {
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class);
        intent.putExtra("force_fusion",true); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        AtomicReference<MainActivity> current=new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
          try {
            scenario.onActivity(a -> { current.set(a); findButton(a.findViewById(android.R.id.content)).performClick(); });
            awaitText(current.get(),4);
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
            awaitState(scenario,Lifecycle.State.CREATED);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(intent);
            awaitState(scenario,Lifecycle.State.RESUMED);
            awaitText(current.get(),4);
            pressKey(KeyEvent.KEYCODE_PROG_BLUE);
            onMain(() -> assertTrue(current.get().demoView().isRunning()));
          } finally { finish(current.get()); }
        }
    }

    @Test public void gyroCoordinateMappingPreservesRotations() {
        Quaternion gyroZ=Quaternion.axisAngle(0,0,1,Math.PI/4).toView(true);
        double[] transformed=gyroZ.rotate(new double[]{0,0,-1});
        assertEquals(-Math.sqrt(.5),transformed[0],1e-6);
        assertEquals(-Math.sqrt(.5),transformed[2],1e-6);
    }

    private static void awaitText(MainActivity activity,int length) {
        long deadline=SystemClock.elapsedRealtime()+15000;
        AtomicInteger visible=new AtomicInteger();
        while (SystemClock.elapsedRealtime() < deadline) {
            // ActivityScenario.onActivity waits for an idle queue; this app renders continuously.
            onMain(() -> visible.set(activity.demoView().visibleText().length()));
            if (visible.get() >= length) return;
            SystemClock.sleep(100);
        }
        fail("No live IMU-driven typewriter text within 15 seconds; visible="+visible.get());
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static void finish(MainActivity activity) {
        if (activity != null) onMain(activity::finish);
    }
    private static void awaitState(ActivityScenario<MainActivity> scenario,Lifecycle.State state) {
        long deadline=SystemClock.elapsedRealtime()+10000;
        while (scenario.getState()!=state && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
        assertEquals(state,scenario.getState());
    }
    private static void pressKey(int key) {
        long time=SystemClock.uptimeMillis();
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(
                new KeyEvent(time,time,KeyEvent.ACTION_DOWN,key,0),true));
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(
                new KeyEvent(time,time+1,KeyEvent.ACTION_UP,key,0),true));
    }

    private static Button findButton(View view) {
        if (view instanceof Button) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button result=findButton(((ViewGroup)view).getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }
    private static int countButtons(View view) {
        if(view instanceof Button) return 1;
        int count=0;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) count+=countButtons(((ViewGroup)view).getChildAt(i));
        return count;
    }
}
