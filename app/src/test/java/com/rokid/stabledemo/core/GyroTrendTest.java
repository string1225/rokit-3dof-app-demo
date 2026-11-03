package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class GyroTrendTest {
    private static final long NS=10_000_000_000L;

    @Test public void monotonicMotionConfirmsThreeThenFiveWithoutWaitingForTen() {
        GyroPredictor.Result three=result(window(10,11,12));
        assertEquals(3,three.trendSamples); assertEquals("confirmed3",three.trendStatus);
        assertEquals(200,three.trendAccelerationX,1e-8);
        GyroPredictor.Result ten=result(window(10,11,12,13,14,15,16,17,18,19));
        assertEquals(10,ten.samples); assertEquals(5,ten.trendSamples); assertEquals("confirmed5",ten.trendStatus);
        assertEquals(45,ten.windowMs,1e-8);
        assertTrue("Start from the latest 19deg/s, not the historical mean",ten.rotationDeg>19*.052);
    }

    @Test public void constantAndAlternatingRatesDoNotInventAcceleration() {
        for(double[] data:new double[][]{{10,10,10,10,10,10,10,10,10,10},{10.2,9.8,10.3,9.9,10.2},{10,10.3,10}}) {
            GyroPredictor.Result prediction=result(window(data));
            assertEquals(0,prediction.trendSamples); assertEquals(0,prediction.trendAccelerationX,0);
            assertEquals(data[data.length-1]*.052,prediction.rotationDeg,1e-8);
        }
    }

    @Test public void recentThreeCanOverrideAnUnconfirmedFivePointHistory() {
        GyroPredictor.Result r=result(window(10,9.7,10.1,9.8,10.1,10.4));
        assertEquals(3,r.trendSamples); assertEquals(60,r.trendAccelerationX,1e-8);
        assertEquals("confirmed3",r.trendStatus);
    }

    @Test public void aSubSafetyThresholdStepDropsAllEarlierSamplesImmediately() {
        GyroPredictor.Result step=result(window(10,10,10,10,10,10,10,10,10,10,20));
        assertEquals("active",step.status); assertEquals("innovation",step.segmentReset);
        assertEquals(1,step.samples); assertEquals(10,step.discardedSamples); assertEquals(NS,step.segmentStartNs);
        assertEquals(0,step.trendSamples); assertEquals(20*.052,step.rotationDeg,1e-8);
    }

    @Test public void postStepTrendUsesOnlyTheNewSegmentAndRecoversInThreeSamples() {
        GyroPredictor.Result r=result(window(10,10,10,10,10,10,10,10,20,21,22));
        assertEquals(3,r.samples); assertEquals(3,r.trendSamples);
        assertEquals(NS-10_000_000L,r.segmentStartNs); assertEquals(200,r.trendAccelerationX,1e-8);
        assertEquals("innovation",r.segmentReset); assertEquals(8,r.discardedSamples);
        GyroPredictor.Result faster=result(window(10,10,10,10,20,25,30));
        assertEquals(3,faster.trendSamples); assertEquals(1000,faster.trendAccelerationX,1e-8);
    }

    @Test public void accelerationReversalDiscardsTheOldIncreasingTrend() {
        GyroPredictor.Result first=result(window(10,10.5,11,11.5,12,11.5));
        assertEquals("trend_reversal",first.segmentReset); assertEquals(1,first.samples);
        GyroPredictor.Result braking=result(window(10,10.5,11,11.5,12,11.5,11,10.5));
        assertEquals(3,braking.samples); assertEquals(3,braking.trendSamples);
        assertEquals(-100,braking.trendAccelerationX,1e-8); assertEquals("braking",braking.motion);
    }

    @Test public void aSingleOutlierCannotKeepDraggingTheFollowingSegment() {
        GyroPredictor.Result r=result(window(10,10,10,10,18,10,10,10));
        assertEquals(3,r.samples); assertEquals(0,r.trendSamples);
        assertEquals(NS-10_000_000L,r.segmentStartNs); assertEquals(10*.052,r.rotationDeg,1e-8);
    }

    @Test public void confirmedBrakingStopsIntegrationWithoutInventingAReversal() {
        GyroPredictor.Result r=result(window(30,25,20,15,10));
        assertEquals(5,r.trendSamples); assertEquals("braking",r.motion);
        assertEquals(10,r.effectiveMotionMs,1e-8); assertEquals(.05,r.rotationDeg,1e-8);
    }

    @Test public void anObservedStopIsAppliedEvenBeforeANewTrendHasWarmedUp() {
        GyroPredictor.Result stopped=result(window(10,10,10,0));
        assertEquals("innovation",stopped.segmentReset); assertEquals("stop_guard",stopped.motion);
        assertEquals(0,stopped.rotationDeg,0); assertEquals(0,stopped.effectiveMotionMs,0);
    }

    @Test public void anActualRateReversalUsesTheNewDirectionImmediately() {
        GyroPredictor.Result r=result(window(1,1,1,-1));
        assertEquals(1,r.samples); assertEquals(0,r.trendSamples);
        assertTrue(r.pose.pitchDegrees()<0);
        GyroPredictor.Result smooth=result(window(4,2,0,-2,-4));
        assertTrue(smooth.pose.pitchDegrees()<0);
    }

    @Test public void actualTimestampsControlSlopesForIrregularSampling() {
        GyroPredictor predictor=new GyroPredictor(); GyroPredictor.State state=null;
        for(int ms:new int[]{-27,-19,-12,-5,0}) {
            long t=NS+ms*1_000_000L;
            state=predictor.onGyro(Math.toRadians(12+100*ms*.001),0,0,t,t+1_000_000L);
        }
        GyroPredictor.Result r=result(state);
        assertEquals(5,r.trendSamples); assertEquals(100,r.trendAccelerationX,1e-8);
        assertEquals(27,r.windowMs,1e-8);
    }

    @Test public void trendDetectionAndPropagationCoverYawPitchAndRoll() {
        for(int axis=0;axis<3;axis++) {
            GyroPredictor p=new GyroPredictor(); GyroPredictor.State state=null;
            for(int i=0;i<10;i++) {
                double[] xyz=new double[3]; xyz[axis]=Math.toRadians(10+i);
                long t=NS-(9-i)*5_000_000L; state=p.onGyro(xyz[0],xyz[1],xyz[2],t,t+1_000_000L);
            }
            GyroPredictor.Result r=result(state);
            double[] a={r.trendAccelerationX,r.trendAccelerationY,r.trendAccelerationZ};
            assertEquals(5,r.trendSamples); assertEquals(200,a[axis],1e-8);
            Quaternion expected=Quaternion.axisAngle(axis==0?1:0,axis==1?1:0,axis==2?1:0,Math.toRadians(r.rotationDeg));
            assertEquals(1,Math.abs(expected.inverse().multiply(r.pose).w),1e-10);
        }
    }

    @Test public void compoundAxesShareTheCapAndRetainSignedAcceleration() {
        GyroPredictor p=new GyroPredictor(); GyroPredictor.State state=null;
        for(int i=0;i<10;i++) {
            long t=NS-(9-i)*5_000_000L;
            state=p.onGyro(Math.toRadians(10+i),Math.toRadians(-10-i),Math.toRadians(5+i*.5),t,t+1_000_000L);
        }
        GyroPredictor.Result r=state.predictDisplay(Quaternion.IDENTITY,NS,NS+2_000_000L,100,true,GyroPredictor.Model.TREND10,.75);
        assertEquals(5,r.trendSamples); assertEquals(200,r.trendAccelerationX,1e-8);
        assertEquals(-200,r.trendAccelerationY,1e-8); assertEquals(100,r.trendAccelerationZ,1e-8);
        assertTrue(r.limited); assertEquals(.75,r.rotationDeg,1e-8);
    }

    @Test public void publishedStateIsImmutableAndADataGapClearsTheSegment() {
        GyroPredictor p=new GyroPredictor(); GyroPredictor.State state=null;
        for(int i=0;i<5;i++) { long t=NS-(4-i)*5_000_000L; state=p.onGyro(Math.toRadians(10+i),0,0,t,t+1_000_000L); }
        GyroPredictor.Result before=result(state);
        GyroPredictor.State gap=p.onGyro(0,0,0,NS+100_000_000L,NS+101_000_000L);
        assertEquals(before.rotationDeg,result(state).rotationDeg,0);
        GyroPredictor.Result rejected=gap.predictDisplay(Quaternion.IDENTITY,NS+100_000_000L,NS+102_000_000L,50,true,GyroPredictor.Model.TREND10,6);
        assertEquals("warming_up",rejected.status); assertEquals(0,rejected.rotationDeg,0);
        assertEquals(NS+100_000_000L,rejected.segmentStartNs); assertEquals(1,rejected.samples);
    }

    @Test public void zeroAndStaleControlsRemainUnpredictedInTrendMode() {
        GyroPredictor.State state=window(10,11,12,13,14);
        GyroPredictor.Result off=state.predictDisplay(Quaternion.IDENTITY,NS,NS+2_000_000L,0,true,GyroPredictor.Model.TREND10,6);
        assertEquals(0,off.rotationDeg,0); assertEquals(0,off.targetNs);
        GyroPredictor.Result stale=state.predictDisplay(Quaternion.IDENTITY,NS,NS+26_000_000L,50,true,GyroPredictor.Model.TREND10,6);
        assertEquals("stale_sample",stale.status); assertEquals(0,stale.rotationDeg,0);
        assertEquals(GyroPredictor.Model.TREND10,GyroPredictor.Model.fromId("trend10"));
    }

    private static GyroPredictor.Result result(GyroPredictor.State state) {
        return state.predictDisplay(Quaternion.IDENTITY,NS,NS+2_000_000L,50,true,GyroPredictor.Model.TREND10,6);
    }
    private static GyroPredictor.State window(double... degrees) {
        GyroPredictor p=new GyroPredictor(); GyroPredictor.State state=null;
        for(int i=0;i<degrees.length;i++) { long t=NS-(degrees.length-1-i)*5_000_000L; state=p.onGyro(Math.toRadians(degrees[i]),0,0,t,t+1_000_000L); }
        return state;
    }
}
