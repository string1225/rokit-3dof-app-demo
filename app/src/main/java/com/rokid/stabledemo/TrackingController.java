package com.rokid.stabledemo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import com.rokid.stabledemo.core.PoseState;
import com.rokid.stabledemo.core.ImuTiming;
import com.rokid.stabledemo.core.MotionDiagnostics;
import com.rokid.stabledemo.core.Quaternion;

/** Sensor callbacks, native state and reference updates all run on one dedicated thread. */
final class TrackingController implements SensorEventListener {
    static final class Snapshot {
        final Quaternion relative;
        final long timestampNs;
        final boolean ready;
        final String status;
        final float hz;
        final long referenceEpoch;
        Snapshot(Quaternion relative, long timestampNs, boolean ready, String status, float hz) {
            this(relative,timestampNs,ready,status,hz,0);
        }
        Snapshot(Quaternion relative,long timestampNs,boolean ready,String status,float hz,long referenceEpoch) {
            this.relative=relative; this.timestampNs=timestampNs; this.ready=ready; this.status=status; this.hz=hz;
            this.referenceEpoch=referenceEpoch;
        }
    }
    private final SensorManager manager;
    private final boolean demoAxes;
    private final boolean forceFusion;
    private final MotionDiagnostics diagnostics;
    private volatile Snapshot snapshot = new Snapshot(Quaternion.IDENTITY, 0, false, "等待 IMU", 0);
    private HandlerThread thread;
    private Handler handler;
    private boolean active;
    private int candidate;
    private int sourceType;
    private long generation, nativeHandle, lastGyroNs, lastIntegratedNs, lastAccelNs, lastPoseNs;
    private float ax, ay, az, hz;
    private final float[] nativeResult = new float[5];
    private Quaternion reference;
    private long referenceEpoch;
    private String sourceName;
    private int gyroPeriodUs;

    TrackingController(Context context, boolean demoAxes, boolean forceFusion) {
        this(context,demoAxes,forceFusion,null);
    }
    TrackingController(Context context, boolean demoAxes, boolean forceFusion,MotionDiagnostics diagnostics) {
        manager=(SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.demoAxes=demoAxes; this.forceFusion=forceFusion;
        this.diagnostics=diagnostics;
    }
    Snapshot latest() { return snapshot; }

    void start() {
        if (thread != null) return;
        thread=new HandlerThread("Rokid-IMU"); thread.start();
        handler=new Handler(thread.getLooper());
        handler.post(() -> { active=true; candidate=forceFusion ? 2 : 0; selectSource(); });
    }

    void stop() {
        if (thread == null) return;
        HandlerThread ending=thread;
        handler.post(() -> {
            active=false; generation++;
            manager.unregisterListener(this);
            releaseNative(); reference=null;
            snapshot=new Snapshot(Quaternion.IDENTITY,0,false,"已停止",0);
            ending.quitSafely();
        });
        thread=null; handler=null;
    }

    void recenter() {
        Handler current=handler;
        if (current != null) current.post(() -> { reference=null; if(diagnostics!=null) diagnostics.reset(); });
    }

    private void selectSource() {
        if (!active) return;
        manager.unregisterListener(this); releaseNative();
        generation++; reference=null;
        if(diagnostics!=null) diagnostics.reset();
        lastGyroNs=lastIntegratedNs=lastAccelNs=lastPoseNs=0; hz=0;
        while (candidate < 3) {
            sourceType=candidate==0 ? Sensor.TYPE_GAME_ROTATION_VECTOR :
                    candidate==1 ? Sensor.TYPE_ROTATION_VECTOR : Sensor.TYPE_GYROSCOPE;
            Sensor sensor=manager.getDefaultSensor(sourceType);
            boolean registered=false;
            if (sensor != null) {
                try {
                    if (candidate < 2) {
                        registered=manager.registerListener(this,sensor,5000,0,handler);
                        sourceName=candidate==0 ? "系统融合" : "旋转矢量";
                    } else {
                        Sensor accel=manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                        gyroPeriodUs=Math.max(5000,sensor.getMinDelay());
                        if (accel != null) {
                            nativeHandle=FusionBridge.create(1_000_000f/gyroPeriodUs,sensor.getMaximumRange());
                            registered=nativeHandle != 0 && manager.registerListener(this,accel,5000,0,handler)
                                    && manager.registerListener(this,sensor,gyroPeriodUs,0,handler);
                            sourceName="Fusion 六轴";
                        }
                    }
                } catch (RuntimeException | LinkageError e) { Log.e("Rokid3DoF","Sensor source failed",e); }
            }
            if (registered) {
                snapshot=new Snapshot(Quaternion.IDENTITY,0,false,"等待"+sourceName,0);
                long selectedGeneration=generation;
                long selectedAt=SystemClock.elapsedRealtime();
                Handler current=handler;
                if (current != null) current.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!active || selectedGeneration != generation) return;
                        boolean noFirstPose=lastPoseNs==0 && SystemClock.elapsedRealtime()-selectedAt > 4500;
                        boolean stalled=lastPoseNs>0 && SystemClock.elapsedRealtimeNanos()-lastPoseNs > 1_000_000_000L;
                        if (noFirstPose || stalled) { candidate++; selectSource(); }
                        else current.postDelayed(this,500);
                    }
                },500);
                return;
            }
            manager.unregisterListener(this); releaseNative(); candidate++;
        }
        snapshot=new Snapshot(Quaternion.IDENTITY,0,false,"IMU 不可用 · 单击重试",0);
    }

    private void releaseNative() {
        if (nativeHandle != 0) { FusionBridge.destroy(nativeHandle); nativeHandle=0; }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!active) return;
        try {
            int type=event.sensor.getType();
            if (sourceType != Sensor.TYPE_GYROSCOPE) {
                if (type != sourceType || event.values.length < 3 || event.timestamp <= lastPoseNs) return;
                float[] q=new float[4];
                SensorManager.getQuaternionFromVector(q,event.values);
                publish(new Quaternion(q[1],q[2],q[3],q[0]),event.timestamp,false);
            } else if (type == Sensor.TYPE_ACCELEROMETER) {
                if (event.timestamp <= lastAccelNs) return;
                ax=event.values[0]; ay=event.values[1]; az=event.values[2]; lastAccelNs=event.timestamp;
            } else if (type == Sensor.TYPE_GYROSCOPE && nativeHandle != 0) {
                if (event.timestamp <= lastGyroNs) return;
                lastGyroNs=event.timestamp;
                if (!ImuTiming.canPair(lastAccelNs,event.timestamp)) return;
                float norm=ax*ax+ay*ay+az*az;
                if (!Float.isFinite(norm) || norm < 1f) return;
                if (!Float.isFinite(event.values[0]) || !Float.isFinite(event.values[1]) || !Float.isFinite(event.values[2])) return;
                if (ImuTiming.hasGap(lastIntegratedNs,event.timestamp)) { selectSource(); return; }
                long previous=lastIntegratedNs;
                lastIntegratedNs=event.timestamp;
                if (previous==0) return;
                float dt=(event.timestamp-previous)*1e-9f;
                FusionBridge.update(nativeHandle,event.values[0],event.values[1],event.values[2],ax,ay,az,dt,nativeResult);
                publish(new Quaternion(nativeResult[0],nativeResult[1],nativeResult[2],nativeResult[3]),
                        event.timestamp,nativeResult[4] != 0);
            }
        } catch (IllegalArgumentException e) { Log.w("Rokid3DoF","Invalid sensor pose discarded",e); }
    }

    private void publish(Quaternion absolute, long timestampNs, boolean initializing) {
        if (!PoseState.isFresh(timestampNs,SystemClock.elapsedRealtimeNanos())) return;
        if (lastPoseNs > 0) {
            float measured=1e9f/(timestampNs-lastPoseNs);
            hz=hz==0 ? measured : hz*.95f+measured*.05f;
        }
        lastPoseNs=timestampNs;
        if (initializing) {
            reference=null;
            snapshot=new Snapshot(Quaternion.IDENTITY,timestampNs,false,"请静止 · IMU 初始化",hz);
        } else {
            if (reference == null) { reference=absolute; referenceEpoch++; if(diagnostics!=null) diagnostics.reset(); }
            Quaternion relative=reference.inverse().multiply(absolute).toView(demoAxes);
            if(diagnostics!=null) diagnostics.onPose(relative,timestampNs,SystemClock.elapsedRealtimeNanos());
            snapshot=new Snapshot(relative,timestampNs,true,sourceName,hz,referenceEpoch);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
