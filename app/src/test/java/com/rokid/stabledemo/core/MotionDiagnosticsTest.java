package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionDiagnosticsTest {
    private static final long START=10_000_000_000L;
    @Test public void detectsKnownOscillationWithoutConfusingSampleRate() {
        MotionDiagnostics d=signal(3,1,false);
        MotionDiagnostics.Snapshot s=d.snapshot(START+5_000_000_000L);
        assertEquals(3,s.dominantHz,.3);
        assertEquals(3,s.meanHz,.3);
        assertEquals(100,s.sampleHz,1);
        assertTrue(s.inputRmsDeg>.6 && s.inputRmsDeg<.8);
    }
    @Test public void quietAndShortSignalsDoNotInventFrequency() {
        MotionDiagnostics d=signal(2,0,false);
        assertTrue(Double.isNaN(d.snapshot(START+5_000_000_000L).dominantHz));
        MotionDiagnostics shortSignal=new MotionDiagnostics();
        shortSignal.onPose(Quaternion.IDENTITY,START,START);
        assertTrue(Double.isNaN(shortSignal.snapshot(START).meanHz));
    }
    @Test public void alignedResidualShowsLatencyInsteadOfArtificialZero() {
        MotionDiagnostics enabled=signal(2,2,true);
        MotionDiagnostics disabled=signal(2,2,false);
        MotionDiagnostics.Snapshot on=enabled.snapshot(START+5_000_000_000L);
        MotionDiagnostics.Snapshot off=disabled.snapshot(START+5_000_000_000L);
        assertTrue(on.residualRmsDeg>.05);
        assertTrue(on.residualRmsDeg<off.residualRmsDeg*.4);
        assertTrue(on.inputCurve.length>100);
        assertEquals(on.inputCurve.length,on.residualCurve.length);
    }
    @Test public void gapAndRecenterDiscardOldSpectrum() {
        MotionDiagnostics d=signal(4,2,true);
        d.onPose(Quaternion.IDENTITY,START+6_000_000_000L,START+6_000_000_000L);
        assertTrue(Double.isNaN(d.snapshot(START+6_000_000_000L).dominantHz));
        d.reset();
        assertEquals(0,d.snapshot(START+6_000_000_001L).inputCurve.length);
    }
    @Test public void negativeLatencyCannotBecomeValidTelemetry() {
        MotionDiagnostics d=new MotionDiagnostics();
        d.frameSubmitted(Quaternion.IDENTITY,START+10,START,START+20,1,2,16);
        assertTrue(Double.isNaN(d.snapshot(START+100).estimatedDisplayMs));
    }
    @Test public void shortestQuaternionInterpolationHandlesOppositeSigns() {
        Quaternion q=Quaternion.axisAngle(0,1,0,.3);
        Quaternion opposite=new Quaternion(-q.x,-q.y,-q.z,-q.w);
        assertArrayEquals(q.rotate(new double[]{0,0,-1}),q.interpolate(opposite,.5).rotate(new double[]{0,0,-1}),1e-8);
    }
    @Test public void steadyOffsetDoesNotCountAsResidualJitter() {
        MotionDiagnostics d=new MotionDiagnostics();
        Quaternion held=Quaternion.axisAngle(1,0,0,Math.toRadians(15));
        for(int i=0;i<=500;i++) {
            long t=START+i*10_000_000L;
            d.onPose(held,t,t+2_000_000L);
            if(i%2==0) d.frameSubmitted(Quaternion.IDENTITY,t+2_000_000L,t+20_000_000L,t,1,3,16);
        }
        assertEquals(0,d.snapshot(START+5_000_000_000L).residualRmsDeg,1e-6);
    }
    @Test public void irregularFrameIntervalsReportFramesPerElapsedTime() {
        MotionDiagnostics d=new MotionDiagnostics(); long time=START;
        for(int i=0;i<100;i++) {
            time+=(i%2==0?10:30)*1_000_000L;
            d.frameSubmitted(Quaternion.IDENTITY,time,time+16_000_000L,time-2_000_000L,1,3,16);
        }
        assertEquals(50,d.snapshot(time).renderHz,1);
    }
    @Test public void presentationEstimatesCanArriveOutOfOrder() {
        MotionDiagnostics d=new MotionDiagnostics(); d.onPose(Quaternion.IDENTITY,START,START);
        d.frameSubmitted(Quaternion.IDENTITY,START+10_000_000L,START+90_000_000L,START,1,3,16);
        d.frameSubmitted(Quaternion.IDENTITY,START+20_000_000L,START+50_000_000L,START,1,3,16);
        for(int i=1;i<=12;i++) d.onPose(Quaternion.IDENTITY,START+i*10_000_000L,START+i*10_000_000L);
        assertEquals(2,d.drainRecords().size());
    }
    private static MotionDiagnostics signal(double frequency,double amplitude,boolean compensate) {
        MotionDiagnostics d=new MotionDiagnostics();
        for(int i=0;i<=500;i++) {
            long t=START+i*10_000_000L;
            double pitch=Math.toRadians(amplitude*Math.sin(2*Math.PI*frequency*i*.01));
            Quaternion q=Quaternion.axisAngle(1,0,0,pitch);
            d.onPose(q,t,t+2_000_000L);
            if(i%2==0) d.frameSubmitted(compensate?q:Quaternion.IDENTITY,t+2_000_000L,t+20_000_000L,t,1,3,16);
        }
        return d;
    }
}
