package com.rokid.stabledemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.FrameMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.rokid.stabledemo.core.AxisProfile;
import com.rokid.stabledemo.core.MotionDiagnostics;
import com.rokid.stabledemo.core.GyroPredictor;
import com.rokid.stabledemo.core.PitchPredictor;
import com.rokid.stabledemo.power.PowerBenchmark;
import com.rokid.stabledemo.power.PowerRecorder;
import java.io.IOException;

public final class MainActivity extends ComponentActivity {
    static final String CLICK="com.android.action.ACTION_SPRITE_BUTTON_CLICK";
    static final String DOUBLE_CLICK="com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK";
    static final String LONG_PRESS="com.android.action.ACTION_AI_START";
    private enum Mode { MENU,DOF,POWER }
    private Mode mode=Mode.MENU;
    private DemoView demo;
    private PowerTestView powerView;
    private LinearLayout menu;
    private final Button[] options=new Button[6];
    private TextView menuStatus;
    private TrackingController tracker;
    private PowerRecorder recorder;
    private MotionLog motionLog;
    private ImuLog imuLog;
    private PowerBenchmark benchmark;
    private ActivityResultLauncher<String[]> permissions;
    private boolean registered,resumed,demoAxes,forceFusion,pendingPowerStart,requestingPermissions;
    private long lastClickMs=-1000,lastCenterMs=-1000,lastBackMs=-1000;
    private String loggingError="";
    private boolean longKey;
    private int selected,benchmarkGeneration;
    private float horizontalFov=30;
    private float followTau=1.5f,followSpeed=12f;
    private boolean predictionEnabled=true;
    private boolean followEnabled=true,imuRecordingEnabled;
    private float predictionLeadMs=15;
    private int gyroHorizonMs;
    private PitchPredictor.Profile periodicProfile=PitchPredictor.Profile.RELAXED;
    private long stageDurationMs=300_000;
    private final Window.OnFrameMetricsAvailableListener frameListener=(window,metrics,dropped) -> {
        if(mode==Mode.DOF && demo!=null) demo.setFrameDurationNs(metrics.getMetric(FrameMetrics.TOTAL_DURATION));
    };

    private final BroadcastReceiver keys=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            String action=intent.getAction();
            if(CLICK.equals(action)) click();
            else if(DOUBLE_CLICK.equals(action)) doubleClick();
            else if(LONG_PRESS.equals(action)) longPress();
            else return;
            if(isOrderedBroadcast()) abortBroadcast();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        horizontalFov=getIntent().getFloatExtra("horizontal_fov_deg",30f);
        if(!Float.isFinite(horizontalFov)||horizontalFov<5||horizontalFov>120) horizontalFov=30;
        followTau=getIntent().getFloatExtra("follow_tau_seconds",1.5f);
        if(!Float.isFinite(followTau)||followTau<.2f||followTau>10) followTau=1.5f;
        followSpeed=getIntent().getFloatExtra("follow_speed_deg_s",12f);
        if(!Float.isFinite(followSpeed)||followSpeed<1||followSpeed>60) followSpeed=12f;
        demoAxes=AxisProfile.usesLegacyDemo(getIntent().getStringExtra("axes"));
        forceFusion=getIntent().getBooleanExtra("force_fusion",false);
        predictionEnabled=getIntent().getBooleanExtra("prediction_enabled",getPreferences(MODE_PRIVATE).getBoolean("prediction_enabled",true));
        String profile=getIntent().getStringExtra("periodic_profile");
        if(profile==null) profile=getPreferences(MODE_PRIVATE).getString("periodic_profile","relaxed");
        periodicProfile="standard".equals(profile)?PitchPredictor.Profile.STANDARD:PitchPredictor.Profile.RELAXED;
        gyroHorizonMs=getIntent().getIntExtra("gyro_prediction_ms",getPreferences(MODE_PRIVATE).getInt("gyro_prediction_ms",0));
        if(!GyroPredictor.validHorizon(gyroHorizonMs)) gyroHorizonMs=0;
        followEnabled=getIntent().getBooleanExtra("follow_enabled",getPreferences(MODE_PRIVATE).getBoolean("follow_enabled",true));
        imuRecordingEnabled=getIntent().getBooleanExtra("imu_recording_enabled",false);
        predictionLeadMs=getIntent().getFloatExtra("prediction_lead_ms",15);
        if(!Float.isFinite(predictionLeadMs)||predictionLeadMs<5||predictionLeadMs>20) predictionLeadMs=15;
        int stageSeconds=getIntent().getIntExtra("benchmark_stage_seconds",300);
        if(stageSeconds>=5 && stageSeconds<=300) stageDurationMs=stageSeconds*1000L;
        permissions=registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),grants -> {
            requestingPermissions=false;
            if(hasMediaPermissions()) { pendingPowerStart=true; if(resumed) beginPower(); }
            else { pendingPowerStart=false; menuStatus.setText("续航测试需要相机和麦克风权限，请授权后重试"); }
        });
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        demo=new DemoView(this,horizontalFov,followTau,followSpeed,this::click,this::doubleClick,this::longPress);
        root.addView(demo,new FrameLayout.LayoutParams(-1,-1));
        powerView=new PowerTestView(this,this::doubleClick);
        root.addView(powerView,new FrameLayout.LayoutParams(-1,-1)); powerView.setVisibility(View.GONE);
        buildMenu(root); setContentView(root); windowSetup();
        getWindow().addOnFrameMetricsAvailableListener(frameListener,new Handler(Looper.getMainLooper()));
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true) {
            // On newer firmware touchpad double-tap reaches the framework back callback.
            // This dedicated glasses menu intentionally uses it to enter the selected item.
            @Override public void handleOnBackPressed() { routeBack(false); }
        });
    }

    private void buildMenu(FrameLayout root) {
        float scale=Math.min(getResources().getDisplayMetrics().widthPixels/480f,getResources().getDisplayMetrics().heightPixels/640f);
        menu=new LinearLayout(this); menu.setOrientation(LinearLayout.VERTICAL); menu.setGravity(Gravity.CENTER);
        menu.setPadding(0,Math.round(80*scale),0,Math.round(80*scale));
        menu.addView(label("选择测试",24*scale));
        for(int i=0;i<options.length;i++) {
            final int index=i;
            Button button=new Button(this); options[i]=button;
            button.setText(i==0?"3DoF 测试":i==1?"续航测试 · 25分钟":i==2?periodicLabel()
                    :i==3?"浮窗跟随 · "+(followEnabled?"开":"关"):i==4?"IMU 录制 · "+(imuRecordingEnabled?"开":"关")
                    :"陀螺外推 · "+gyroHorizonMs+" ms");
            if(i==1 && stageDurationMs!=300_000) button.setText("续航缩时验收 · 每项"+(stageDurationMs/1000)+"秒");
            button.setTextColor(Color.GREEN); button.setAllCaps(false); button.setPadding(0,0,0,0);
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX,22*scale); button.setDefaultFocusHighlightEnabled(false);
            button.setOnClickListener(v -> { selected=index; enterSelected(); });
            button.setOnKeyListener((v,key,event) -> {
                if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_SPACE
                        ||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN) {
                    if(event.getAction()==KeyEvent.ACTION_DOWN) return onKeyDown(key,event);
                    if(event.getAction()==KeyEvent.ACTION_UP && (key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)) return true;
                    if(event.getAction()==KeyEvent.ACTION_UP) return onKeyUp(key,event);
                }
                return false;
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Math.round(370*scale),Math.round(44*scale));
            lp.topMargin=Math.round(8*scale); menu.addView(button,lp);
        }
        TextView guide=label("单击切换 · 双击选中 · 长按退出",16*scale);
        LinearLayout.LayoutParams guideLp=new LinearLayout.LayoutParams(-1,Math.round(36*scale));
        guideLp.topMargin=Math.round(8*scale); menu.addView(guide,guideLp);
        menuStatus=label("IMU录制需开启 · 双击返回停止并导出",14*scale);
        menuStatus.setMaxLines(2);
        menuStatus.setPadding(Math.round(25*scale),0,Math.round(25*scale),0); menu.addView(menuStatus);
        root.addView(menu,new FrameLayout.LayoutParams(-1,-1)); highlightSelection();
    }
    private String periodicLabel() {
        return "周期预测 · "+(!predictionEnabled?"关":periodicProfile==PitchPredictor.Profile.STANDARD?"原门槛":"宽松");
    }
    private TextView label(String text,float size) {
        TextView view=new TextView(this); view.setText(text); view.setTextColor(Color.GREEN);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size); view.setGravity(Gravity.CENTER); return view;
    }
    private void highlightSelection() {
        for(int i=0;i<options.length;i++) {
            GradientDrawable border=new GradientDrawable(); border.setColor(Color.BLACK); border.setCornerRadius(10);
            border.setStroke(i==selected?3:1,i==selected?Color.GREEN:0xff008800); options[i].setBackground(border);
        }
    }
    private void windowSetup() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c=getWindow().getInsetsController();
        if(c!=null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; windowSetup(); powerView.foreground(true);
        IntentFilter f=new IntentFilter(); f.addAction(CLICK); f.addAction(DOUBLE_CLICK); f.addAction(LONG_PRESS);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY); ContextCompat.registerReceiver(this,keys,f,ContextCompat.RECEIVER_EXPORTED); registered=true;
        if(mode==Mode.DOF) beginDof();
        else if(pendingPowerStart && !requestingPermissions && hasMediaPermissions()) beginPower();
    }
    @Override protected void onPause() {
        resumed=false;
        if(registered) { unregisterReceiver(keys); registered=false; }
        if(mode==Mode.DOF) { stopDof("app_background"); demo.suspend(); }
        if(mode==Mode.POWER && benchmark!=null && benchmark.isRunning()) benchmark.abort("app_background");
        powerView.foreground(false); super.onPause();
    }
    @Override protected void onDestroy() {
        stopDof("activity_destroyed");
        if(benchmark!=null && benchmark.isRunning()) benchmark.abort("activity_destroyed");
        getWindow().removeOnFrameMetricsAvailableListener(frameListener); super.onDestroy();
    }

    private void enterSelected() {
        if(selected==0) beginDof();
        else if(selected==1) requestPower();
        else if(selected==2) {
            if(!predictionEnabled) { predictionEnabled=true; periodicProfile=PitchPredictor.Profile.STANDARD; }
            else if(periodicProfile==PitchPredictor.Profile.STANDARD) periodicProfile=PitchPredictor.Profile.RELAXED;
            else predictionEnabled=false;
            getPreferences(MODE_PRIVATE).edit().putBoolean("prediction_enabled",predictionEnabled)
                    .putString("periodic_profile",periodicProfile==PitchPredictor.Profile.STANDARD?"standard":"relaxed").apply();
            options[2].setText(periodicLabel());
            menuStatus.setText(predictionEnabled?"周期模型仅在满足门槛时介入 · 可搭配陀螺外推":"周期预测已关 · 外推仍按所选毫秒运行");
        } else if(selected==3) {
            followEnabled=!followEnabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("follow_enabled",followEnabled).apply();
            options[3].setText("浮窗跟随 · "+(followEnabled?"开":"关"));
            menuStatus.setText(followEnabled?"偏移超20%才跟随 · 超50%立即回中":"锚点固定 · 仅长按回中 · 选择3DoF开始");
        } else if(selected==4) {
            imuRecordingEnabled=!imuRecordingEnabled;
            options[4].setText("IMU 录制 · "+(imuRecordingEnabled?"开":"关"));
            menuStatus.setText(imuRecordingEnabled?"进入3DoF开始录制 · 双击返回停止并导出":"IMU录制已关闭 · 后续3DoF不保存原始数据");
        } else {
            gyroHorizonMs=GyroPredictor.nextHorizon(gyroHorizonMs);
            getPreferences(MODE_PRIVATE).edit().putInt("gyro_prediction_ms",gyroHorizonMs).apply();
            options[5].setText("陀螺外推 · "+gyroHorizonMs+" ms");
            menuStatus.setText(gyroHorizonMs==0?"陀螺外推已关 · 周期预测独立选择":"陀螺外推"+gyroHorizonMs+"ms · 选择3DoF开始对比");
        }
    }
    private void beginDof() {
        if(!resumed) return;
        stopDof("restart"); mode=Mode.DOF; menu.setVisibility(View.GONE); powerView.setVisibility(View.GONE); demo.setVisibility(View.VISIBLE);
        MotionDiagnostics diagnostics=new MotionDiagnostics();
        demo.setFollowEnabled(followEnabled); demo.setPredictionOptions(predictionEnabled,predictionLeadMs,gyroHorizonMs);
        loggingError="";
        try {
            recorder=new PowerRecorder(this,"3dof_diagnostics");
            recorder.metadata("axes",demoAxes?"demo":"android"); recorder.metadata("horizontal_fov_deg",horizontalFov);
            recorder.metadata("follow_tau_seconds",followTau); recorder.metadata("follow_speed_deg_s",followSpeed);
            recorder.metadata("follow_enabled",followEnabled); recorder.metadata("imu_recording_enabled",imuRecordingEnabled);
            recorder.metadata("follow_start_content_fraction",com.rokid.stabledemo.core.LevelFollow.FOLLOW_START_FRACTION);
            recorder.metadata("prediction_enabled",predictionEnabled); recorder.metadata("prediction_display_lead_ms",predictionLeadMs);
            recorder.metadata("periodic_profile",periodicProfile==PitchPredictor.Profile.STANDARD?"standard":"relaxed");
            recorder.metadata("periodic_min_quality",periodicProfile.minQuality); recorder.metadata("periodic_min_cycles",periodicProfile.minCycles);
            recorder.metadata("periodic_required_stable_fits",periodicProfile.requiredStableFits);
            recorder.metadata("periodic_frequency_tolerance_hz",periodicProfile.frequencyToleranceHz);
            recorder.metadata("gyro_prediction_ms",gyroHorizonMs);
            recorder.metadata("prediction_policy","Pitch-only: gyro uses a fixed 0/15/20ms horizon from the source pose timestamp, not draw time; sample/gyro age >25ms, pose/gyro separation >20ms, speed >180deg/s or rate change >3000deg/s^2 rejected. Periodic model 1..8Hz uses the selected quality/cycle/stability profile and sample age + display lead capped at 20ms. When both are active, confidence blends their corrections instead of summing full forecasts. Total added pitch capped at 0.75deg. Follow uses measured pose. No optical latency calibration.");
            recorder.metadata("content_top_screen_px",80+DemoView.CONTENT_TOP);
            recorder.metadata("content_bottom_screen_px",80+DemoView.CONTENT_BOTTOM);
            recorder.metadata("content_center_screen_y_px",80+(DemoView.CONTENT_TOP+DemoView.CONTENT_BOTTOM)/2);
            recorder.metadata("auto_recenter_policy",followEnabled?"Before slow follow, snap yaw/pitch to the content center when the projected panel center exits the central black content region (screen X 0..480, Y 160..392): X offset >240 pixels OR Y offset >116 pixels. Parameter bars are excluded. Roll compensation is preserved.":"Disabled: no slow follow or viewport recentering; anchor changes only on manual recenter or tracking reference initialization/reset.");
            recorder.metadata("level_reference","Gravity-aligned from the fused pose (Android/Fusion ENU world +Z up); startup head tilt is retained for compensation. Optical/IMU mounting alignment is not calibrated.");
            recorder.metadata("follow_policy",followEnabled?"Hold the anchor while projected center offsets are within 20% of content width AND height; start slow yaw/pitch follow when either axis exceeds 20%, stop again on re-entry. Exceeding 50% still immediately recenters yaw/pitch. Limits use measured pose before prediction. Roll stays compensated relative to gravity.":"Fixed yaw/pitch anchor even outside viewport or when toggling stabilization. Manual recenter retains roll compensation. Background resume or a sensor reference reset initializes a new anchor.");
            recorder.metadata("motion_residual","Retrospective IMU alignment relative to each submitted frame's moving level anchor; intentional follow is excluded. Software model only, not optical measurement.");
            recorder.startStage("3dof","3DoF维稳与诊断"); motionLog=new MotionLog(recorder.directory(),diagnostics);
        } catch(IOException e) { loggingError="耗电/诊断文件创建失败"; if(recorder!=null) recorder.note("motion_log_error",String.valueOf(e)); }
        if(imuRecordingEnabled) {
            if(recorder!=null) try {
                imuLog=new ImuLog(this,recorder.directory());
                recorder.note("imu_recording_started","Raw accelerometer/gyroscope and fused poses; stop on back/background. Files: imu_raw.csv, imu_poses.csv, imu_metadata.json");
            } catch(IOException e) { loggingError="IMU录制创建失败"; recorder.note("imu_log_error",e.toString()); }
            else loggingError="IMU录制失败 · 无可用会话目录";
        }
        demo.setPowerStatus(() -> {
            if(!loggingError.isEmpty()) return loggingError;
            if(imuLog!=null) return imuLog.status();
            if(motionLog!=null && !motionLog.error().isEmpty()) return motionLog.error();
            return "IMU录制关 · "+(recorder!=null?recorder.displayStatus():"耗电记录不可用");
        });
        tracker=new TrackingController(this,demoAxes,forceFusion,diagnostics,predictionEnabled,imuLog,periodicProfile,gyroHorizonMs>0);
        tracker.start(); demo.begin(tracker,diagnostics); demo.requestFocus();
    }
    private void stopDof(String reason) {
        if(tracker!=null) { tracker.stop(); tracker=null; }
        MotionLog closingLog=motionLog; PowerRecorder closingRecorder=recorder;
        ImuLog closingImu=imuLog;
        motionLog=null; recorder=null; imuLog=null;
        Runnable finish=() -> {
            if(closingRecorder!=null) {
                if(closingLog!=null && !closingLog.error().isEmpty()) closingRecorder.note("motion_log_error",closingLog.error());
                if(closingImu!=null) {
                    closingRecorder.note("imu_recording_stopped",reason);
                    if(!closingImu.error().isEmpty()) closingRecorder.note("imu_log_error",closingImu.error());
                }
                closingRecorder.finish(reason);
                showSavedSession(closingRecorder,closingImu);
            }
        };
        // Stop accepting sensor samples immediately, then drain both writers before export.
        Runnable closeMotion=() -> { if(closingLog!=null) closingLog.close(finish); else finish.run(); };
        if(closingImu!=null) closingImu.close(closeMotion); else closeMotion.run();
    }
    private void showSavedSession(PowerRecorder saved,ImuLog savedImu) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if(isDestroyed() || mode!=Mode.MENU || recorder!=null) return;
                if(!saved.isFinished()) { menuStatus.setText("录制已停止 · 正在保存和导出"); menuStatus.postDelayed(this,200); }
                else menuStatus.setText(savedImu!=null && !savedImu.error().isEmpty()?"IMU录制出错 · 已保留本地结果":saved.displayStatus());
            }
        });
    }
    private boolean hasMediaPermissions() {
        return ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestPower() {
        if(benchmark!=null && !benchmark.isFinished()) { menuStatus.setText("上一轮正在释放设备并保存，请稍后重试"); return; }
        if(hasMediaPermissions()) beginPower();
        else { pendingPowerStart=true; requestingPermissions=true; permissions.launch(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO}); }
    }
    private void beginPower() {
        if(!resumed) { pendingPowerStart=true; return; }
        pendingPowerStart=false;
        if(benchmark!=null && !benchmark.isFinished()) return;
        stopDof("switch_to_power_test"); demo.end(); demo.setVisibility(View.GONE); menu.setVisibility(View.GONE); mode=Mode.POWER;
        int generation=++benchmarkGeneration;
        benchmark=new PowerBenchmark(this,stageDurationMs,() -> { if(generation==benchmarkGeneration) powerView.invalidate(); });
        powerView.attach(benchmark,stageDurationMs); powerView.setVisibility(View.VISIBLE); powerView.foreground(true); powerView.requestFocus(); benchmark.start();
    }
    private void returnToMenu(String reason) {
        pendingPowerStart=false; stopDof(reason); demo.end(); demo.setVisibility(View.GONE);
        if(benchmark!=null && benchmark.isRunning()) { benchmark.abort(reason); menuStatus.setText("测试已中止，正在保存记录"); }
        mode=Mode.MENU; powerView.setVisibility(View.GONE); menu.setVisibility(View.VISIBLE); highlightSelection();
    }
    private void click() {
        long now=SystemClock.elapsedRealtime(); if(now-lastClickMs<140) return; lastClickMs=now;
        if(mode==Mode.MENU) { selected=(selected+1)%options.length; highlightSelection(); }
        else if(mode==Mode.DOF) { if(demo.unavailable()) beginDof(); else { demo.toggle(); if(recorder!=null) recorder.note("stabilization",demo.isStabilized()?"enabled":"disabled"); } }
    }
    private void doubleClick() { routeBack(false); }
    private void routeBack(boolean systemBack) {
        long now=SystemClock.elapsedRealtime(); if(now-lastBackMs<250) return; lastBackMs=now;
        if(mode==Mode.MENU) { if(systemBack) finish(); else enterSelected(); }
        else returnToMenu("user_back");
    }
    private void longPress() {
        if(mode==Mode.MENU) { finish(); return; }
        if(mode!=Mode.DOF) return;
        long now=SystemClock.elapsedRealtime(); if(now-lastCenterMs<200) return; lastCenterMs=now;
        if(tracker!=null && tracker.latest().ready && com.rokid.stabledemo.core.PoseState.isFresh(tracker.latest().timestampNs,SystemClock.elapsedRealtimeNanos())) {
            demo.centered(); if(recorder!=null) recorder.note("recenter","Level anchor recentered in yaw/pitch; roll compensation preserved");
        } else beginDof();
    }
    // Rokid firmware encodes touchpad double-tap as BACK, including entering the menu item.
    // Predictive/system back is separately registered with OnBackPressedDispatcher above.
    @SuppressLint("GestureBackNavigation")
    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_SPACE) {
            if(event.getRepeatCount()==0) longKey=false;
            if(event.isLongPress()&&!longKey) { longKey=true; longPress(); } return true;
        }
        if(key==KeyEvent.KEYCODE_PROG_BLUE) { if(event.getRepeatCount()==0) longPress(); return true; }
        if(key==KeyEvent.KEYCODE_BACK) return true;
        if(mode==Mode.MENU&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)) {
            selected=(selected+(key==KeyEvent.KEYCODE_DPAD_UP?options.length-1:1))%options.length; highlightSelection(); return true;
        }
        return super.onKeyDown(key,event);
    }
    @SuppressLint("GestureBackNavigation")
    @Override public boolean onKeyUp(int key,KeyEvent event) {
        if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_SPACE) { if(!longKey) click(); return true; }
        if(key==KeyEvent.KEYCODE_PROG_BLUE) return true;
        if(key==KeyEvent.KEYCODE_BACK) { doubleClick(); return true; }
        return super.onKeyUp(key,event);
    }
    DemoView demoView() { return demo; }
    PowerBenchmark powerBenchmark() { return benchmark; }
}
