package com.rokid.stabledemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

public final class MainActivity extends ComponentActivity {
    static final String CLICK="com.android.action.ACTION_SPRITE_BUTTON_CLICK";
    static final String DOUBLE_CLICK="com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK";
    static final String LONG_PRESS="com.android.action.ACTION_AI_START";
    private DemoView demo;
    private Button startButton;
    private TrackingController tracker;
    private boolean registered, resumed, demoAxes, forceFusion;
    private long lastClickMs=-1000, lastCenterMs=-1000;
    private boolean longKey;

    private final BroadcastReceiver keys=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            String action=intent.getAction();
            if (CLICK.equals(action)) click();
            else if (DOUBLE_CLICK.equals(action)) returnToStart();
            else if (LONG_PRESS.equals(action)) recenter();
            else return;
            if (isOrderedBroadcast()) abortBroadcast();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Optical FOV is an engineering starting value, not a measured device specification.
        float fov=getIntent().getFloatExtra("horizontal_fov_deg",30f);
        if (!Float.isFinite(fov) || fov < 5 || fov > 120) fov=30;
        demoAxes=!"android".equals(getIntent().getStringExtra("axes"));
        forceFusion=getIntent().getBooleanExtra("force_fusion",false);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        demo=new DemoView(this,fov,this::click,this::returnToStart,this::recenter);
        root.addView(demo,new FrameLayout.LayoutParams(-1,-1));
        startButton=new Button(this); startButton.setText("开始"); startButton.setTextColor(Color.GREEN);
        float scale=Math.min(getResources().getDisplayMetrics().widthPixels/480f,
                getResources().getDisplayMetrics().heightPixels/640f);
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,26*scale);
        startButton.setAllCaps(false);
        GradientDrawable border=new GradientDrawable(); border.setColor(Color.BLACK);
        border.setStroke(Math.max(1,Math.round(2*scale)),Color.GREEN); border.setCornerRadius(10*scale);
        startButton.setBackground(border); startButton.setPadding(0,0,0,0);
        startButton.setOnClickListener(v -> click());
        FrameLayout.LayoutParams buttonParams=new FrameLayout.LayoutParams(Math.round(184*scale),Math.round(68*scale),Gravity.CENTER);
        root.addView(startButton,buttonParams); setContentView(root); windowSetup();
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (demo.isRunning()) returnToStart(); else finish();
            }
        });
    }

    private void windowSetup() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller=getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; windowSetup();
        IntentFilter filter=new IntentFilter(); filter.addAction(CLICK); filter.addAction(DOUBLE_CLICK); filter.addAction(LONG_PRESS);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        ContextCompat.registerReceiver(this,keys,filter,ContextCompat.RECEIVER_EXPORTED);
        registered=true;
        if (demo.isRunning()) begin();
    }
    @Override protected void onPause() {
        resumed=false;
        if (registered) { unregisterReceiver(keys); registered=false; }
        stopTracker(); demo.suspend();
        super.onPause();
    }

    private void begin() {
        if (!resumed) return;
        stopTracker();
        tracker=new TrackingController(this,demoAxes,forceFusion);
        tracker.start(); demo.begin(tracker);
        startButton.setVisibility(View.GONE); demo.requestFocus();
    }
    private void stopTracker() { if (tracker != null) { tracker.stop(); tracker=null; } }
    private void click() {
        long now=SystemClock.elapsedRealtime();
        if (now-lastClickMs < 140) return; // Some firmware sends both ENTER and a click broadcast.
        lastClickMs=now;
        if (!demo.isRunning() || demo.unavailable()) begin(); else demo.toggle();
    }
    private void recenter() {
        if (!demo.isRunning()) return;
        long now=SystemClock.elapsedRealtime();
        if (now-lastCenterMs < 200) return;
        lastCenterMs=now;
        if (tracker != null && tracker.latest().ready &&
                com.rokid.stabledemo.core.PoseState.isFresh(tracker.latest().timestampNs,SystemClock.elapsedRealtimeNanos())) {
            tracker.recenter(); demo.centered();
        } else begin();
    }
    private void returnToStart() {
        stopTracker(); demo.end(); startButton.setVisibility(View.VISIBLE); startButton.requestFocus();
    }

    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if (key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_SPACE) {
            if (event.getRepeatCount()==0) longKey=false;
            if (event.isLongPress() && !longKey) { longKey=true; recenter(); }
            return true;
        }
        if (key==KeyEvent.KEYCODE_PROG_BLUE) {
            if (event.getRepeatCount()==0) recenter();
            return true;
        }
        if (key==KeyEvent.KEYCODE_BACK && demo.isRunning()) return true;
        return super.onKeyDown(key,event);
    }
    @Override public boolean onKeyUp(int key,KeyEvent event) {
        if (key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_SPACE) {
            if (!longKey) click(); return true;
        }
        if (key==KeyEvent.KEYCODE_PROG_BLUE) return true;
        if (key==KeyEvent.KEYCODE_BACK && demo.isRunning()) { returnToStart(); return true; }
        return super.onKeyUp(key,event);
    }

    DemoView demoView() { return demo; }
}
