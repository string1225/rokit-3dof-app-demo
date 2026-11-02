package com.rokid.stabledemo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import com.rokid.stabledemo.core.Quaternion;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in sensor capture. Sensor threads only copy samples into a bounded queue; CSV runs on IO. */
final class ImuLog implements SensorEventListener {
    private final SensorManager manager;
    private final File directory;
    private final BufferedWriter raw,poses;
    private final ArrayBlockingQueue<Sample> queue=new ArrayBlockingQueue<>(8192);
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"IMU-CSV"));
    private final HandlerThread sensors=new HandlerThread("IMU-capture");
    private final ScheduledFuture<?> task;
    private final JSONObject metadata=new JSONObject();
    private boolean accepting=true,closing;
    private volatile String error="";
    private volatile long dropped;
    private volatile long rawWritten,posesWritten;
    private long stoppedElapsedNs;
    private int registeredSensors;

    ImuLog(Context context,File directory) throws IOException {
        this.directory=directory;
        manager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        BufferedWriter openedRaw=null,openedPoses=null;
        try {
            openedRaw=Files.newBufferedWriter(new File(directory,"imu_raw.csv").toPath(),StandardCharsets.UTF_8);
            openedPoses=Files.newBufferedWriter(new File(directory,"imu_poses.csv").toPath(),StandardCharsets.UTF_8);
            openedRaw.write("sensor_timestamp_ns,received_elapsed_ns,sensor_type,accuracy,x,y,z\n");
            openedPoses.write("sensor_timestamp_ns,received_elapsed_ns,source_sensor_type,reference_epoch,initializing,absolute_qx,absolute_qy,absolute_qz,absolute_qw,relative_qx,relative_qy,relative_qz,relative_qw\n");
            openedRaw.flush(); openedPoses.flush();
            put("schema_version",1);
            put("started_utc_ms",System.currentTimeMillis());
            put("started_elapsed_ns",SystemClock.elapsedRealtimeNanos());
            put("requested_period_us",5000);
            put("timestamp_clock","Android elapsedRealtimeNanos / SensorEvent.timestamp: nanoseconds since boot including sleep. UTC is a wall-clock correspondence only.");
            put("raw_coordinates","Android device sensor axes, before app axis mapping or filtering. TYPE_ACCELEROMETER=1 in m/s^2 including gravity; TYPE_GYROSCOPE=4 in rad/s. Android sensor calibration is retained.");
            put("pose_coordinates","Unit Hamilton quaternions x,y,z,w. Absolute: fused device-to-ENU-world before app mapping. Relative: gravity-aligned render reference after configured axes mapping, before follow and prediction; blank while initializing. reference_epoch changes on reference reset; never concatenate epochs as one continuous reference.");
            put("source_types","15=GAME_ROTATION_VECTOR, 11=ROTATION_VECTOR, 4=project Fusion gyro+accelerometer. See metadata.json for follow/prediction/axes/FOV settings and stages.jsonl for manual recenter/stabilization events.");
            put("queue_capacity_samples",8192);
            put("overflow_policy","Drop newest sample when the bounded queue is full; dropped_samples counts raw and pose samples together. Never blocks tracking or drawing.");
            sensors.start(); Handler sensorHandler=new Handler(sensors.getLooper());
            JSONArray descriptors=new JSONArray();
            for(int type:new int[]{Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_GAME_ROTATION_VECTOR,Sensor.TYPE_ROTATION_VECTOR}) {
                Sensor sensor=manager.getDefaultSensor(type);
                JSONObject descriptor=new JSONObject(); descriptor.put("type",type);
                descriptor.put("available",sensor!=null);
                if(sensor!=null) {
                    descriptor.put("name",sensor.getName()); descriptor.put("vendor",sensor.getVendor());
                    descriptor.put("version",sensor.getVersion()); descriptor.put("resolution",sensor.getResolution());
                    descriptor.put("maximum_range",sensor.getMaximumRange()); descriptor.put("min_delay_us",sensor.getMinDelay());
                }
                if(type==Sensor.TYPE_ACCELEROMETER || type==Sensor.TYPE_GYROSCOPE) {
                    boolean registered=false;
                    if(sensor!=null) try { registered=manager.registerListener(this,sensor,5000,0,sensorHandler); }
                    catch(RuntimeException e) { descriptor.put("registration_error",e.toString()); }
                    descriptor.put("raw_capture_registered",registered);
                    if(registered) registeredSensors++;
                }
                descriptors.put(descriptor);
            }
            put("sensors",descriptors); writeMetadata(false);
        } catch(IOException | JSONException | RuntimeException e) {
            accepting=false; manager.unregisterListener(this); sensors.quitSafely(); io.shutdown();
            if(openedRaw!=null) try { openedRaw.close(); } catch(IOException ignored) { }
            if(openedPoses!=null) try { openedPoses.close(); } catch(IOException ignored) { }
            throw new IOException("Cannot initialize IMU recording",e);
        }
        raw=openedRaw; poses=openedPoses;
        task=io.scheduleAtFixedRate(this::flush,250,250,TimeUnit.MILLISECONDS);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if(event.values.length<3) return;
        offer(new Sample(false,event.timestamp,SystemClock.elapsedRealtimeNanos(),event.sensor.getType(),event.accuracy,0,
                new double[]{event.values[0],event.values[1],event.values[2]}));
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }

    void pose(Quaternion absolute,Quaternion relative,long timestampNs,int source,long epoch,boolean initializing) {
        offer(new Sample(true,timestampNs,SystemClock.elapsedRealtimeNanos(),source,initializing?1:0,epoch,
                new double[]{absolute.x,absolute.y,absolute.z,absolute.w,
                        relative==null?Double.NaN:relative.x,relative==null?Double.NaN:relative.y,
                        relative==null?Double.NaN:relative.z,relative==null?Double.NaN:relative.w}));
    }
    private synchronized void offer(Sample sample) {
        if(accepting && error.isEmpty() && !queue.offer(sample)) dropped++;
    }
    String error() { return error; }
    String status() {
        if(!error.isEmpty()) return "IMU录制失败 · 本地文件已保留";
        return (registeredSensors==2?"IMU录制中":"IMU录制中 · 原始六轴不全")+" · "+rawWritten+"条"+(dropped>0?" · 丢"+dropped:"");
    }

    private void flush() {
        try {
            Sample sample;
            while((sample=queue.poll())!=null) {
                BufferedWriter out=sample.pose?poses:raw;
                StringBuilder row=new StringBuilder().append(sample.timestampNs).append(',').append(sample.receivedNs)
                        .append(',').append(sample.type).append(',');
                if(sample.pose) row.append(sample.epoch).append(',');
                row.append(sample.flag);
                for(double value:sample.values) { row.append(','); if(Double.isFinite(value)) row.append(value); }
                out.write(row.append('\n').toString());
                if(sample.pose) posesWritten++; else rawWritten++;
            }
            raw.flush(); poses.flush();
        } catch(IOException e) { error=e.toString(); }
    }

    /** Gate late callbacks before draining; export must only begin after this callback. */
    void close(Runnable afterClosed) {
        synchronized(this) { if(closing) return; closing=true; accepting=false; stoppedElapsedNs=SystemClock.elapsedRealtimeNanos(); }
        manager.unregisterListener(this); sensors.quitSafely(); task.cancel(false);
        io.execute(() -> {
            flush();
            try { raw.close(); } catch(IOException e) { error=e.toString(); }
            try { poses.close(); } catch(IOException e) { error=e.toString(); }
            try { writeMetadata(true); } catch(IOException e) { error=e.toString(); }
            if(afterClosed!=null) afterClosed.run();
        });
        io.shutdown();
    }
    private void writeMetadata(boolean complete) throws IOException {
        put("complete",complete && error.isEmpty()); put("raw_samples_written",rawWritten); put("pose_samples_written",posesWritten);
        put("dropped_samples",dropped); put("write_error",error);
        if(complete) put("stopped_elapsed_ns",stoppedElapsedNs);
        Files.write(new File(directory,"imu_metadata.json").toPath(),metadata.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void put(String key,Object value) {
        try { metadata.put(key,value); } catch(JSONException e) { throw new IllegalArgumentException(e); }
    }
    private static final class Sample {
        final boolean pose;
        final long timestampNs,receivedNs,epoch;
        final int type,flag;
        final double[] values;
        Sample(boolean pose,long timestampNs,long receivedNs,int type,int flag,long epoch,double[] values) {
            this.pose=pose; this.timestampNs=timestampNs; this.receivedNs=receivedNs;
            this.type=type; this.flag=flag; this.epoch=epoch; this.values=values;
        }
    }
}
