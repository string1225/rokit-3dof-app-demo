package com.rokid.stabledemo;

import android.os.SystemClock;
import com.rokid.stabledemo.core.MotionDiagnostics;
import com.rokid.stabledemo.core.PitchPredictor;
import com.rokid.stabledemo.core.GyroPredictor;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class MotionLog {
    private static final String PREDICTION_COLUMNS=",periodic_horizon_ms,periodic_delta_deg,periodic_fit_quality,periodic_fit_cycles,periodic_stable_fits,periodic_status,gyro_selected_ms,gyro_horizon_ms,gyro_delta_deg,gyro_status,prediction_method,prediction_applied"
            +",gyro_model,gyro_motion,gyro_window_samples,gyro_window_ms,pitch_rate_deg_s,pitch_acceleration_deg_s2,gyro_effective_motion_ms,gyro_unclamped_deg,gyro_cap_deg,gyro_limited,source_pose_elapsed_ns,gyro_sample_elapsed_ns,gyro_received_elapsed_ns,prediction_read_elapsed_ns,prediction_target_elapsed_ns,gyro_axes,gyro_rotation_deg\n";
    private static final String FRAME_COLUMNS=",applied_qx,applied_qy,applied_qz,applied_qw,anchor_qx,anchor_qy,anchor_qz,anchor_qw,baseline_qx,baseline_qy,baseline_qz,baseline_qw,residual_rotation_deg,baseline_residual_rotation_deg\n";
    private static final String TREND_COLUMNS=",gyro_trend_samples,gyro_trend_status,gyro_segment_start_elapsed_ns,gyro_segment_reset,gyro_discarded_samples,gyro_trend_acceleration_x_deg_s2,gyro_trend_acceleration_y_deg_s2,gyro_trend_acceleration_z_deg_s2\n";
    private final MotionDiagnostics diagnostics;
    private final BufferedWriter frames,metrics;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"Motion-CSV"));
    private final ScheduledFuture<?> task;
    private final ScheduledFuture<?> analysisTask;
    private volatile String error="";
    MotionLog(File directory,MotionDiagnostics diagnostics) throws IOException {
        this.diagnostics=diagnostics;
        BufferedWriter openedFrames=null,openedMetrics=null;
        try {
            openedFrames=Files.newBufferedWriter(new File(directory,"motion_frames.csv").toPath(),StandardCharsets.UTF_8);
            openedMetrics=Files.newBufferedWriter(new File(directory,"motion_metrics.csv").toPath(),StandardCharsets.UTF_8);
            openedFrames.write("estimated_presentation_elapsed_ns,input_pitch_deg,estimated_residual_pitch_deg,applied_pitch_deg,sample_age_ms,cpu_draw_ms,app_frame_ms,estimated_sample_to_display_ms,anchor_yaw_deg,anchor_pitch_deg,baseline_estimated_residual_pitch_deg,prediction_enabled,prediction_horizon_ms,periodic_hz,prediction_strength,prediction_delta_deg"+PREDICTION_COLUMNS.stripTrailing()+FRAME_COLUMNS.stripTrailing()+TREND_COLUMNS);
            openedMetrics.write("elapsed_ns,dominant_hz,weighted_mean_hz,input_rms_deg,residual_rms_deg,imu_hz,draw_hz,sample_age_ms,cpu_draw_ms,app_frame_ms,estimated_sample_to_display_ms,uncertainty_ms,baseline_residual_rms_deg,prediction_enabled,prediction_horizon_ms,periodic_hz,prediction_strength,prediction_delta_deg"+PREDICTION_COLUMNS.stripTrailing()+TREND_COLUMNS);
        } catch(IOException e) {
            if(openedFrames!=null) try { openedFrames.close(); } catch(IOException ignored) { }
            if(openedMetrics!=null) try { openedMetrics.close(); } catch(IOException ignored) { }
            throw e;
        }
        frames=openedFrames; metrics=openedMetrics;
        analysisTask=io.scheduleAtFixedRate(() -> diagnostics.snapshot(SystemClock.elapsedRealtimeNanos()),0,250,TimeUnit.MILLISECONDS);
        task=io.scheduleAtFixedRate(this::flush,1,1,TimeUnit.SECONDS);
    }
    String error() { return error; }
    private void flush() {
        try {
            for(MotionDiagnostics.Record r:diagnostics.drainRecords()) frames.write(r.presentationNs+","+values(
                    r.inputPitchDeg,r.residualPitchDeg,r.appliedPitchDeg,r.sampleAgeMs,r.cpuMs,r.appMs,r.estimatedDisplayMs,r.anchorYawDeg,r.anchorPitchDeg,
                    r.baselineResidualPitchDeg,r.prediction.enabled?1:0,r.prediction.horizonMs,r.prediction.frequencyHz,r.prediction.strength,r.prediction.correctionDeg)+predictionFields(r.prediction)
                    +","+values(r.applied.x,r.applied.y,r.applied.z,r.applied.w,r.anchor.x,r.anchor.y,r.anchor.z,r.anchor.w,
                            r.baseline.x,r.baseline.y,r.baseline.z,r.baseline.w,r.residualRotationDeg,r.baselineResidualRotationDeg)+trendFields(r.prediction.gyro)+"\n");
            long now=SystemClock.elapsedRealtimeNanos(); MotionDiagnostics.Snapshot s=diagnostics.snapshot(now);
            metrics.write(now+","+values(s.dominantHz,s.meanHz,s.inputRmsDeg,s.residualRmsDeg,s.sampleHz,s.renderHz,
                    s.sampleAgeMs,s.cpuDrawMs,s.appFrameMs,s.estimatedDisplayMs,s.uncertaintyMs,s.baselineResidualRmsDeg,
                    s.prediction.enabled?1:0,s.prediction.horizonMs,s.prediction.frequencyHz,s.prediction.strength,s.prediction.correctionDeg)+predictionFields(s.prediction)+trendFields(s.prediction.gyro)+"\n");
            frames.flush(); metrics.flush();
        } catch(IOException e) { error="诊断CSV写入失败"; }
    }
    private static String predictionFields(PitchPredictor.Prediction p) {
        GyroPredictor.Result g=p.gyro;
        return ","+values(p.periodicHorizonMs,p.periodicCorrectionDeg,p.fitQuality,p.fitCycles,p.stableFits)+","+p.periodicStatus
                +","+values(p.gyroRequestedMs,p.gyroHorizonMs,p.gyroCorrectionDeg)+","+p.gyroStatus+","+p.method()+","+(p.horizonMs>0?1:0)
                +","+g.model+","+g.motion+","+values(g.samples,g.windowMs,g.rateDegS,g.accelerationDegS2,g.effectiveMotionMs,
                        g.unclampedDeg,g.maxCorrectionDeg,g.limited?1:0)
                +","+g.poseNs+","+g.sampleNs+","+g.receivedNs+","+g.readNs+","+g.targetNs+","+(g.fullAxes?"xyz":"pitch")+","+values(g.rotationDeg);
    }
    private static String trendFields(GyroPredictor.Result g) {
        return ","+g.trendSamples+","+g.trendStatus+","+g.segmentStartNs+","+g.segmentReset+","+g.discardedSamples
                +","+values(g.trendAccelerationX,g.trendAccelerationY,g.trendAccelerationZ);
    }
    void close(Runnable afterClosed) {
        task.cancel(false);
        analysisTask.cancel(false);
        io.execute(() -> {
            flush();
            try { frames.close(); } catch(IOException e) { error="诊断CSV关闭失败"; }
            try { metrics.close(); } catch(IOException e) { error="诊断CSV关闭失败"; }
            if(afterClosed!=null) afterClosed.run();
        });
        io.shutdown();
    }
    private static String values(double... values) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<values.length;i++) {
            if(i>0) out.append(',');
            if(Double.isFinite(values[i])) out.append(String.format(Locale.ROOT,"%.6f",values[i]));
        }
        return out.toString();
    }
}
