package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PitchPredictorTest {
    private static final long START=10_000_000_000L;
    @Test public void stablePeriodicSignalsReduceErrorAtTheSameFutureTime() {
        for(double hz:new double[]{1.5,3,5,7}) {
            PitchPredictor predictor=new PitchPredictor(); double predictedError=0,baselineError=0;
            for(int i=0;i<=1600;i++) {
                double t=i*.005; long ns=time(t);
                Quaternion measured=pitch(signal(hz,t));
                PitchPredictor.State state=predictor.onPose(measured,ns);
                if(t>=6) {
                    PitchPredictor.Prediction prediction=state.predict(measured,ns,20,true);
                    double actual=signal(hz,t+.02);
                    predictedError+=sq(prediction.pose.pitchDegrees()-actual);
                    baselineError+=sq(measured.pitchDegrees()-actual);
                    assertTrue(prediction.horizonMs<=20);
                }
            }
            assertTrue("Stable "+hz+"Hz rotation should improve future alignment",predictedError<baselineError*.25);
        }
    }
    @Test public void noiseAndOffsetDoNotRequireReplacingTheMeasuredPose() {
        PitchPredictor p=new PitchPredictor(); PitchPredictor.State state=null; Quaternion head=null;
        for(int i=0;i<=1600;i++) {
            double t=i*.005; head=pitch(12+.2*t+signal(2.35,t)+.01*Math.sin(2*Math.PI*17*t));
            state=p.onPose(head,time(t));
        }
        PitchPredictor.Prediction prediction=state.predict(head,time(8),15,true);
        assertTrue(prediction.strength>.5); assertEquals(2.35,prediction.frequencyHz,.06);
        double expected=12+1.6+signal(2.35,8.015)+.01*Math.sin(2*Math.PI*17*8);
        assertEquals(expected,prediction.pose.pitchDegrees(),.05);
    }
    @Test public void quietTrendAndBroadbandMotionDoNotInventPeriodicLock() {
        for(int kind=0;kind<3;kind++) {
            PitchPredictor p=new PitchPredictor(); PitchPredictor.State state=null; Quaternion head=null;
            for(int i=0;i<=800;i++) {
                double t=i*.01;
                double angle=kind==0?0:kind==1?t*2:Math.sin(2*Math.PI*2.1*t)+Math.sin(2*Math.PI*4.7*t);
                head=pitch(angle); state=p.onPose(head,time(t));
            }
            PitchPredictor.Prediction result=state.predict(head,time(8),20,true);
            assertEquals(0,result.strength,.01); assertEquals(0,result.correctionDeg,1e-8);
        }
    }
    @Test public void stopAndSuddenTurnDisableTheLearnedMotionQuickly() {
        PitchPredictor p=trained(); PitchPredictor.State state=null; Quaternion head=null;
        for(int i=1;i<=80;i++) {
            double t=8+i*.005; head=pitch(0); state=p.onPose(head,time(t));
        }
        assertTrue(state.predict(head,time(8.4),20,true).strength<.05);
        p=trained(); head=Quaternion.axisAngle(0,1,0,1).multiply(pitch(signal(3,8.005)));
        state=p.onPose(head,time(8.005));
        PitchPredictor.Prediction turned=state.predict(head,time(8.005),20,true);
        assertTrue("A sudden turn must not retain a periodic forecast",turned.strength<.05);
    }
    @Test public void staleGapAndDisabledModeReturnTheLatestRealPose() {
        PitchPredictor p=trained(); Quaternion head=pitch(signal(3,8));
        PitchPredictor.State state=p.onPose(head,time(8.005));
        assertEquals(0,state.predict(head,time(8.005)+30_000_000L,15,true).correctionDeg,0);
        assertEquals(0,state.predict(head,time(8.005),15,false).correctionDeg,0);
        state=p.onPose(head,time(9));
        assertEquals(0,state.predict(head,time(9),15,true).strength,0);
        p.reset(); state=p.onPose(head,time(9.01));
        assertEquals(0,state.predict(head,time(9.01),15,true).strength,0);
    }
    @Test public void horizonAndCorrectionAreBoundedAndOtherAxesArePreserved() {
        PitchPredictor p=new PitchPredictor(); PitchPredictor.State state=null; Quaternion head=null;
        for(int i=0;i<=1600;i++) {
            double t=i*.005;
            head=Quaternion.axisAngle(0,1,0,.4).multiply(pitch(signal(5,t)*3))
                    .multiply(Quaternion.axisAngle(0,0,1,.3));
            state=p.onPose(head,time(t));
        }
        PitchPredictor.Prediction result=state.predict(head,time(8)+10_000_000L,100,true);
        assertTrue(result.strength>.5); assertEquals(20,result.horizonMs,1e-8);
        assertTrue(Math.abs(result.correctionDeg)<=.750001);
        double[] before=head.rotate(new double[]{0,0,-1}),after=result.pose.rotate(new double[]{0,0,-1});
        assertEquals(Math.atan2(before[0],before[2]),Math.atan2(after[0],after[2]),1e-7);
        Quaternion delta=result.pose.multiply(head.inverse());
        assertEquals(0,delta.y,1e-8);
    }
    @Test public void irregularSamplingAndOutOfOrderInputDoNotCorruptTheModel() {
        PitchPredictor p=new PitchPredictor(); double t=0; int i=0;
        PitchPredictor.State state=null; Quaternion head=null;
        while(t<8) {
            t+=new double[]{.009,.006,.012}[i++%3]; head=pitch(signal(2.7,t)); state=p.onPose(head,time(t));
        }
        PitchPredictor.Prediction result=state.predict(head,time(t),15,true);
        assertTrue(result.strength>.5); assertEquals(2.7,result.frequencyHz,.05);
        assertEquals(signal(2.7,t+.015),result.pose.pitchDegrees(),.04);
        PitchPredictor.State unchanged=p.onPose(pitch(70),time(t)-1_000_000_000L);
        assertEquals(result.correctionDeg,unchanged.predict(head,time(t),15,true).correctionDeg,0);
    }
    private static PitchPredictor trained() {
        PitchPredictor p=new PitchPredictor();
        for(int i=0;i<=1600;i++) p.onPose(pitch(signal(3,i*.005)),time(i*.005));
        return p;
    }
    private static long time(double seconds) { return START+Math.round(seconds*1e9); }
    private static Quaternion pitch(double degrees) { return Quaternion.axisAngle(1,0,0,Math.toRadians(degrees)); }
    private static double signal(double hz,double t) { return Math.sin(2*Math.PI*hz*t); }
    private static double sq(double x) { return x*x; }
}
