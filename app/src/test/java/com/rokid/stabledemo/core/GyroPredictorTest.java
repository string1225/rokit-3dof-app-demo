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
        assertEquals(0,state.predict(pitch(0),NS,NS+2_000_000L,17,true).horizonMs,0);
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

    private static GyroPredictor.State rate(double x,double y,double z) {
        GyroPredictor p=new GyroPredictor();
        p.onGyro(Math.toRadians(x),Math.toRadians(y),Math.toRadians(z),NS-5_000_000L,NS-4_000_000L);
        return p.onGyro(Math.toRadians(x),Math.toRadians(y),Math.toRadians(z),NS,NS+1_000_000L);
    }
    private static Quaternion pitch(double degrees) { return Quaternion.axisAngle(1,0,0,Math.toRadians(degrees)); }
}
