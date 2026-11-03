package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class GyroPredictorTest {
    private static final long NS=10_000_000_000L;

    @Test public void zeroFifteenAndTwentyUseFixedSourcePoseHorizons() {
        GyroPredictor.State state=rate(20,0,0);
        Quaternion head=pitch(7);
        for(int ms:new int[]{0,15,20}) {
            GyroPredictor.Result result=state.predict(head,NS,NS+7_000_000L,ms,true);
            PitchPredictor.Prediction applied=PitchPredictor.Prediction.unavailable(head,false).withGyro(head,result);
            assertEquals(ms,result.horizonMs,0);
            assertEquals(7+20*ms*.001,applied.pose.pitchDegrees(),1e-8);
        }
    }

    @Test public void constantRatePredictionImprovesTheSameFuturePose() {
        for(int ms:new int[]{15,20}) {
            double ordinary=0,predicted=0;
            for(int i=0;i<80;i++) {
                double angle=-4+i*.1;
                Quaternion head=pitch(angle);
                GyroPredictor.Result result=rate(18,0,0).predict(head,NS,NS+2_000_000L,ms,true);
                Quaternion future=pitch(angle+18*ms*.001);
                Quaternion estimate=PitchPredictor.Prediction.unavailable(head,false).withGyro(head,result).pose;
                ordinary+=Math.pow(head.pitchDegrees()-future.pitchDegrees(),2);
                predicted+=Math.pow(estimate.pitchDegrees()-future.pitchDegrees(),2);
            }
            assertTrue(predicted<ordinary*.001);
        }
    }

    @Test public void rawBodyRatesRespectHeadYawAndRollWhileCorrectionOnlyChangesPitch() {
        Quaternion head=Quaternion.axisAngle(0,1,0,.5).multiply(pitch(12)).multiply(Quaternion.axisAngle(0,0,1,.4));
        double x=8,y=-3,z=2;
        GyroPredictor.Result result=rate(x,y,z).predict(head,NS,NS+2_000_000L,20,true);
        double norm=Math.sqrt(x*x+y*y+z*z);
        double derivative=(head.multiply(Quaternion.axisAngle(x,y,z,Math.toRadians(norm)*.00001)).pitchDegrees()-head.pitchDegrees())/.00001;
        assertEquals(derivative*.02,result.correctionDeg,.00001);
        Quaternion predicted=PitchPredictor.Prediction.unavailable(head,false).withGyro(head,result).pose;
        double[] before=head.rotate(new double[]{0,0,-1}),after=predicted.rotate(new double[]{0,0,-1});
        assertEquals(Math.atan2(before[0],before[2]),Math.atan2(after[0],after[2]),1e-8);
        assertEquals(0,predicted.multiply(head.inverse()).y,1e-8);
    }

    @Test public void staleFutureAndPausedInputsCannotPredict() {
        GyroPredictor.State state=rate(20,0,0);
        assertEquals("stale_sample",state.predict(pitch(0),NS,NS+26_000_000L,20,true).status);
        assertEquals("future_sample",state.predict(pitch(0),NS,NS,20,true).status);
        assertEquals("stale_sample",state.predict(pitch(0),NS-21_000_000L,NS+2_000_000L,20,true).status);
        assertEquals("paused",state.predict(pitch(0),NS,NS+2_000_000L,20,false).status);
        assertEquals(0,state.predict(pitch(0),NS,NS+2_000_000L,101,true).horizonMs,0);
        assertEquals("vertical_view",state.predict(pitch(89),NS,NS+2_000_000L,20,true).status);
    }

    @Test public void rapidChangesGapsAndInvalidInputRejectThenRecover() {
        GyroPredictor predictor=new GyroPredictor();
        predictor.onGyro(0,0,0,NS-5_000_000L,NS-4_000_000L);
        GyroPredictor.State spike=predictor.onGyro(Math.toRadians(80),0,0,NS,NS+1_000_000L);
        assertEquals("rapid_change",spike.predict(pitch(0),NS,NS+2_000_000L,20,true).status);
        GyroPredictor.State recovered=predictor.onGyro(Math.toRadians(80),0,0,NS+5_000_000L,NS+6_000_000L);
        assertEquals("active",recovered.predict(pitch(0),NS+5_000_000L,NS+7_000_000L,20,true).status);
        assertEquals(.75,recovered.predict(pitch(0),NS+5_000_000L,NS+7_000_000L,20,true).correctionDeg,1e-8);
        GyroPredictor.State gap=predictor.onGyro(0,0,0,NS+100_000_000L,NS+101_000_000L);
        assertEquals("warming_up",gap.predict(pitch(0),NS+100_000_000L,NS+102_000_000L,20,true).status);
        GyroPredictor.State invalid=predictor.onGyro(Double.NaN,0,0,NS+105_000_000L,NS+106_000_000L);
        assertEquals("no_gyro",invalid.predict(pitch(0),NS+105_000_000L,NS+107_000_000L,20,true).status);
        assertEquals("fast_turn",rate(181,0,0).predict(pitch(0),NS,NS+2_000_000L,20,true).status);
    }

    @Test public void outOfOrderCallbacksDoNotOverwriteTheLatestRate() {
        GyroPredictor p=new GyroPredictor();
        p.onGyro(.1,0,0,NS-5_000_000L,NS-4_000_000L);
        p.onGyro(.1,0,0,NS,NS+1_000_000L);
        GyroPredictor.State state=p.onGyro(1,0,0,NS-1,NS+2_000_000L);
        assertEquals(Math.toDegrees(.1)*.02,state.predict(pitch(0),NS,NS+3_000_000L,20,true).correctionDeg,1e-8);
    }

    @Test public void twoPredictorsBlendInsteadOfDoublingTheSameMotion() {
        PitchPredictor p=new PitchPredictor(); PitchPredictor.State state=null;
        Quaternion head=null;
        for(int i=0;i<=1600;i++) {
            head=pitch(Math.sin(2*Math.PI*3*i*.005)); state=p.onPose(head,NS+i*5_000_000L);
        }
        PitchPredictor.Prediction periodic=state.predict(head,NS+8_000_000_000L,20,true);
        GyroPredictor gyro=new GyroPredictor();
        long t=NS+8_000_000_000L;
        gyro.onGyro(Math.toRadians(6*Math.PI),0,0,t-5_000_000L,t-4_000_000L);
        GyroPredictor.Result result=gyro.onGyro(Math.toRadians(6*Math.PI),0,0,t,t+1_000_000L)
                .predict(head,t,t+2_000_000L,20,true);
        PitchPredictor.Prediction both=periodic.withGyro(head,result);
        assertEquals("blend",both.method());
        assertTrue(both.strength>.9);
        assertTrue(Math.abs(both.correctionDeg)<.4);
        assertEquals(both.periodicCorrectionDeg+both.gyroCorrectionDeg,both.correctionDeg,1e-8);
    }

    @Test public void continuousHorizonsAndLargerCapReallyReachTheAppliedPose() {
        for(int ms:new int[]{0,1,10,15,17,20,45,50,100}) {
            Quaternion head=pitch(4);
            GyroPredictor.Result result=rate(30,0,0).predict(head,NS,NS+2_000_000L,ms,true,GyroPredictor.Model.LATEST,6);
            assertEquals(4+.03*ms,PitchPredictor.Prediction.unavailable(head,false).withGyro(head,result).pose.pitchDegrees(),1e-8);
        }
        GyroPredictor.Result limited=rate(30,0,0).predict(pitch(0),NS,NS+2_000_000L,100,true,GyroPredictor.Model.LATEST,.75);
        assertTrue(limited.limited); assertEquals(3,limited.unclampedDeg,1e-8); assertEquals(.75,limited.correctionDeg,1e-8);
    }

    @Test public void fiveSampleBrakingImprovesAKnownStopWithoutPredictingAReversal() {
        GyroPredictor.State state=window(30,25,20,15,10);
        GyroPredictor.Result latest=state.predict(pitch(0),NS,NS+2_000_000L,100,true,GyroPredictor.Model.LATEST,3);
        GyroPredictor.Result braking=state.predict(pitch(0),NS,NS+2_000_000L,100,true,GyroPredictor.Model.WINDOW5,3);
        // 10deg/s decelerating at 1000deg/s² stops after 10ms, travelling 0.05deg.
        assertEquals("braking",braking.motion); assertEquals(10,braking.effectiveMotionMs,1e-6);
        assertEquals(.05,braking.correctionDeg,1e-8);
        assertTrue(Math.abs(braking.correctionDeg-.05)<Math.abs(latest.correctionDeg-.05));
        assertEquals(5,braking.samples); assertEquals(20,braking.windowMs,1e-8);
    }

    @Test public void stopAndRealReversalRespondToTheLatestRateWithoutAHistoryAverageDelay() {
        GyroPredictor.Result stopped=window(20,15,10,5,0).predict(pitch(0),NS,NS+2_000_000L,100,true,GyroPredictor.Model.WINDOW5,6);
        assertEquals("stop_guard",stopped.motion); assertEquals(0,stopped.correctionDeg,0);
        GyroPredictor.Result reversed=window(4,2,0,-2,-4).predict(pitch(0),NS,NS+2_000_000L,50,true,GyroPredictor.Model.WINDOW5,6);
        assertTrue("Actual reversal must respond immediately rather than carry the old direction",reversed.correctionDeg<0);
    }

    @Test public void noisySlopeFallsBackAndAFiveSampleStateIsImmutable() {
        GyroPredictor.Result noisy=window(10,14,9,14,10).predict(pitch(0),NS,NS+2_000_000L,50,true,GyroPredictor.Model.WINDOW5,3);
        assertEquals("noisy_window",noisy.motion); assertEquals(.5,noisy.correctionDeg,1e-8);
        GyroPredictor p=new GyroPredictor(); GyroPredictor.State saved=null;
        for(int i=0;i<5;i++) saved=p.onGyro(Math.toRadians(10),0,0,NS-20_000_000L+i*5_000_000L,NS-19_000_000L+i*5_000_000L);
        p.onGyro(Math.toRadians(11),0,0,NS+5_000_000L,NS+6_000_000L);
        assertEquals(.2,saved.predict(pitch(0),NS,NS+2_000_000L,20,true,GyroPredictor.Model.WINDOW5,3).correctionDeg,1e-8);
    }

    private static GyroPredictor.State window(double... degrees) {
        GyroPredictor p=new GyroPredictor(); GyroPredictor.State state=null;
        for(int i=0;i<degrees.length;i++) {
            long t=NS-(degrees.length-1-i)*5_000_000L;
            state=p.onGyro(Math.toRadians(degrees[i]),0,0,t,t+1_000_000L);
        }
        return state;
    }

    @Test public void fullQuaternionPredictsAllThreeAxesToReadTimePlusSelectedLead() {
        Quaternion head=Quaternion.axisAngle(1,2,3,.4);
        GyroPredictor.State state=rate(12,-18,9);
        GyroPredictor.Result result=state.predictDisplay(head,NS,NS+4_000_000L,20,true,GyroPredictor.Model.LATEST,6);
        Quaternion expected=head.multiply(Quaternion.axisAngle(12,-18,9,Math.toRadians(Math.sqrt(12*12+18*18+9*9))*.024));
        Quaternion actual=PitchPredictor.Prediction.unavailable(head,false).withGyro(head,result).pose;
        assertEquals(24,result.horizonMs,1e-8); assertEquals(NS+24_000_000L,result.targetNs);
        assertEquals(1,Math.abs(expected.inverse().multiply(actual).w),1e-10);
        for(double[] xyz:new double[][]{{20,0,0},{0,20,0},{0,0,20}}) {
            GyroPredictor.Result axis=rate(xyz[0],xyz[1],xyz[2]).predictDisplay(Quaternion.IDENTITY,NS,NS+2_000_000L,20,true,GyroPredictor.Model.LATEST,6);
            assertTrue(axis.fullAxes); assertTrue(axis.rotationDeg>.4);
            assertEquals(1,Math.abs(axis.pose.inverse().multiply(PitchPredictor.Prediction.unavailable(Quaternion.IDENTITY,false)
                    .withGyro(Quaternion.IDENTITY,axis).pose).w),1e-10);
        }
    }

    @Test public void fullRotationCapIsSharedByAxesAndZeroRemainsAnUnpredictedControl() {
        GyroPredictor.State state=rate(50,50,50);
        GyroPredictor.Result result=state.predictDisplay(pitch(3),NS,NS+2_000_000L,100,true,GyroPredictor.Model.LATEST,3);
        assertTrue(result.limited); assertEquals(3,result.rotationDeg,1e-8);
        assertEquals(3,Math.toDegrees(2*Math.acos(Math.abs(pitch(3).inverse().multiply(result.pose).w))),1e-7);
        GyroPredictor.Result off=state.predictDisplay(pitch(3),NS,NS+2_000_000L,0,true,GyroPredictor.Model.LATEST,6);
        assertEquals(0,off.horizonMs,0); assertEquals(0,off.targetNs); assertEquals(3,off.pose.pitchDegrees(),1e-8);
        assertEquals("stale_sample",state.predictDisplay(pitch(0),NS,NS+26_000_000L,100,true,GyroPredictor.Model.LATEST,6).status);
    }

    @Test public void threeFiveAndTenSampleWindowsUseCausalTimestampedBrakingHistories() {
        for(GyroPredictor.Model model:new GyroPredictor.Model[]{GyroPredictor.Model.WINDOW3,GyroPredictor.Model.WINDOW5,GyroPredictor.Model.WINDOW10}) {
            double[] speeds=new double[model.window];
            for(int i=0;i<speeds.length;i++) speeds[i]=10+(speeds.length-1-i)*5;
            GyroPredictor.Result result=window(speeds).predictDisplay(Quaternion.IDENTITY,NS,NS+2_000_000L,50,true,model,6);
            assertEquals(model.window,result.samples); assertEquals((model.window-1)*5,result.windowMs,1e-8);
            assertEquals("braking",result.motion); assertEquals(.05,result.rotationDeg,1e-8);
            assertEquals(NS+52_000_000L,result.targetNs);
        }
    }

    private static GyroPredictor.State rate(double x,double y,double z) {
        GyroPredictor p=new GyroPredictor();
        p.onGyro(Math.toRadians(x),Math.toRadians(y),Math.toRadians(z),NS-5_000_000L,NS-4_000_000L);
        return p.onGyro(Math.toRadians(x),Math.toRadians(y),Math.toRadians(z),NS,NS+1_000_000L);
    }
    private static Quaternion pitch(double degrees) { return Quaternion.axisAngle(1,0,0,Math.toRadians(degrees)); }
}
