package com.rokid.stabledemo.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Comparator;

/** Timestamp-aligned software estimates. This cannot measure optical/display tracking error. */
public final class MotionDiagnostics {
    private static final long WINDOW_NS=4_000_000_000L;
    private final ArrayDeque<Pose> poses=new ArrayDeque<>();
    private final PriorityQueue<Frame> pending=new PriorityQueue<>(Comparator.comparingLong(frame -> frame.time));
    private final ArrayDeque<Long> frameTimes=new ArrayDeque<>();
    private final ArrayDeque<Record> pairs=new ArrayDeque<>();
    private final ArrayDeque<Record> records=new ArrayDeque<>();
    private Snapshot cached;
    private long cachedAt,lastFrameNs;
    private double sampleAge=Double.NaN,cpuMs=Double.NaN,appMs=Double.NaN;
    private double estimatedMs=Double.NaN,uncertaintyMs=Double.NaN,renderHz=Double.NaN;
    private PitchPredictor.Prediction prediction=PitchPredictor.Prediction.NONE;

    public synchronized void reset() {
        poses.clear(); pending.clear(); pairs.clear(); frameTimes.clear(); cached=null; cachedAt=0;
        lastFrameNs=0; sampleAge=cpuMs=appMs=estimatedMs=uncertaintyMs=renderHz=Double.NaN;
        prediction=PitchPredictor.Prediction.NONE;
    }

    public synchronized void onPose(Quaternion orientation,long timestampNs,long callbackNs) {
        if(timestampNs<=0 || callbackNs<timestampNs) return;
        Pose previous=poses.peekLast();
        if(previous!=null && timestampNs<=previous.time) return;
        if(previous!=null && timestampNs-previous.time>250_000_000L) { reset(); previous=null; }
        Pose current=new Pose(orientation,timestampNs);
        poses.addLast(current);
        while(poses.size()>1024 || (!poses.isEmpty() && timestampNs-poses.peekFirst().time>WINDOW_NS+100_000_000L)) poses.removeFirst();
        while(!pending.isEmpty() && pending.peek().time<=timestampNs) {
            Frame f=pending.remove();
            if(previous==null || f.time<previous.time) continue;
            double t=(double)(f.time-previous.time)/(timestampNs-previous.time);
            Quaternion actual=previous.q.interpolate(orientation,t);
            double residual=f.anchor.inverse().multiply(actual).multiply(f.applied.inverse()).pitchDegrees();
            double baseline=f.anchor.inverse().multiply(actual).multiply(f.baseline.inverse()).pitchDegrees();
            Record record=new Record(f.time,actual.pitchDegrees(),residual,baseline,f.applied.pitchDegrees(),f.anchor,
                    f.age,f.cpu,f.app,f.estimate,f.prediction);
            pairs.addLast(record); records.addLast(record);
            while(records.size()>2048) records.removeFirst();
        }
        while(!pairs.isEmpty() && timestampNs-pairs.peekFirst().presentationNs>WINDOW_NS) pairs.removeFirst();
    }

    public synchronized void frameSubmitted(Quaternion applied,long frameEndNs,long expectedPresentationNs,
            long sourceSampleNs,double drawCpuMs,double frameTotalMs,double refreshMs) {
        frameSubmitted(applied,Quaternion.IDENTITY,frameEndNs,expectedPresentationNs,sourceSampleNs,drawCpuMs,frameTotalMs,refreshMs);
    }

    public synchronized void frameSubmitted(Quaternion applied,Quaternion anchor,long frameEndNs,long expectedPresentationNs,
            long sourceSampleNs,double drawCpuMs,double frameTotalMs,double refreshMs) {
        frameSubmitted(applied,anchor,applied,PitchPredictor.Prediction.NONE,frameEndNs,expectedPresentationNs,
                sourceSampleNs,drawCpuMs,frameTotalMs,refreshMs);
    }

    public synchronized void frameSubmitted(Quaternion applied,Quaternion anchor,Quaternion baselineApplied,
            PitchPredictor.Prediction prediction,long frameEndNs,long expectedPresentationNs,
            long sourceSampleNs,double drawCpuMs,double frameTotalMs,double refreshMs) {
        if(sourceSampleNs<=0 || frameEndNs<sourceSampleNs || expectedPresentationNs<frameEndNs
                || expectedPresentationNs-frameEndNs>500_000_000L) return;
        double age=(frameEndNs-sourceSampleNs)*1e-6;
        double estimate=(expectedPresentationNs-sourceSampleNs)*1e-6;
        sampleAge=average(sampleAge,age); cpuMs=average(cpuMs,drawCpuMs);
        appMs=average(appMs,frameTotalMs); estimatedMs=average(estimatedMs,estimate);
        uncertaintyMs=refreshMs;
        this.prediction=prediction;
        if(frameEndNs>lastFrameNs) {
            frameTimes.addLast(frameEndNs);
            while(!frameTimes.isEmpty() && frameEndNs-frameTimes.peekFirst()>WINDOW_NS) frameTimes.removeFirst();
            if(frameTimes.size()>1) renderHz=(frameTimes.size()-1)*1e9/(frameEndNs-frameTimes.peekFirst());
        }
        lastFrameNs=frameEndNs;
        pending.add(new Frame(applied,anchor,baselineApplied,prediction,expectedPresentationNs,age,drawCpuMs,frameTotalMs,estimate));
        while(pending.size()>180) pending.remove();
    }

    public synchronized List<Record> drainRecords() {
        List<Record> result=new ArrayList<>(records); records.clear(); return result;
    }

    public synchronized Snapshot snapshot(long nowNs) {
        if(cached!=null && nowNs>=cachedAt && nowNs-cachedAt<250_000_000L) return cached;
        Pose[] input=poses.toArray(new Pose[0]);
        double sampleHz=Double.NaN,peak=Double.NaN,mean=Double.NaN,rms=Double.NaN;
        if(input.length>=2) sampleHz=(input.length-1)*1e9/(input[input.length-1].time-input[0].time);
        if(input.length>=32 && input[input.length-1].time-input[0].time>=2_000_000_000L
                && nowNs-input[input.length-1].time<=250_000_000L) {
            double[] spectrum=analyze(input,sampleHz);
            peak=spectrum[0]; mean=spectrum[1]; rms=spectrum[2];
        }
        Record[] curve=pairs.toArray(new Record[0]);
        double[] seconds=new double[curve.length],raw=new double[curve.length],residual=new double[curve.length],baseline=new double[curve.length];
        for(int i=0;i<curve.length;i++) {
            seconds[i]=(curve[i].presentationNs-nowNs)*1e-9;
            raw[i]=curve[i].inputPitchDeg; residual[i]=curve[i].residualPitchDeg;
            baseline[i]=curve[i].baselineResidualPitchDeg;
        }
        double residualRms=detrendedRms(seconds,residual);
        cached=new Snapshot(peak,mean,rms,residualRms,sampleHz,renderHz,sampleAge,cpuMs,appMs,
                estimatedMs,uncertaintyMs,seconds,raw,residual,baseline,detrendedRms(seconds,baseline),prediction);
        cachedAt=nowNs; return cached;
    }

    private static double[] analyze(Pose[] input,double measuredHz) {
        int n=256,index=0;
        double[] signal=new double[n];
        long begin=Math.max(input[0].time,input[input.length-1].time-WINDOW_NS),end=input[input.length-1].time;
        double dt=(end-begin)/(double)(n-1),sum=0,sumI=0,sumII=0,sumYI=0;
        for(int i=0;i<n;i++) {
            double time=begin+i*dt;
            while(index<input.length-2 && input[index+1].time<time) index++;
            double fraction=(time-input[index].time)/(input[index+1].time-input[index].time);
            signal[i]=input[index].pitch+(input[index+1].pitch-input[index].pitch)*fraction;
            sum+=signal[i]; sumI+=i; sumII+=(double)i*i; sumYI+=signal[i]*i;
        }
        double slope=(n*sumYI-sum*sumI)/(n*sumII-sumI*sumI),intercept=(sum-slope*sumI)/n;
        double squares=0;
        for(int i=0;i<n;i++) {
            signal[i]-=intercept+slope*i; squares+=signal[i]*signal[i];
            signal[i]*=.5-.5*Math.cos(2*Math.PI*i/(n-1));
        }
        double rms=Math.sqrt(squares/n);
        if(rms<.02) return new double[]{Double.NaN,Double.NaN,rms};
        double fs=1e9/dt,limit=Math.min(20,Math.min(fs*.45,measuredHz*.45));
        double peak=Double.NaN,maxPower=0,total=0,weighted=0;
        for(int k=1;k<n/2;k++) {
            double frequency=k*fs/n;
            if(frequency<.5 || frequency>limit) continue;
            double angle=2*Math.PI*k/n,c=Math.cos(angle),s=Math.sin(angle),cx=1,sx=0,re=0,im=0;
            for(double value:signal) {
                re+=value*cx; im+=value*sx;
                double next=cx*c-sx*s; sx=sx*c+cx*s; cx=next;
            }
            double power=re*re+im*im;
            total+=power; weighted+=frequency*power;
            if(power>maxPower) { maxPower=power; peak=frequency; }
        }
        return new double[]{peak,total>1e-12?weighted/total:Double.NaN,rms};
    }

    private static double average(double previous,double value) {
        if(!Double.isFinite(value) || value<0) return previous;
        return Double.isFinite(previous)?previous*.9+value*.1:value;
    }
    private static double detrendedRms(double[] time,double[] value) {
        if(value.length<2) return Double.NaN;
        double sumT=0,sumTT=0,sumY=0,sumTY=0;
        for(int i=0;i<value.length;i++) { sumT+=time[i]; sumTT+=time[i]*time[i]; sumY+=value[i]; sumTY+=time[i]*value[i]; }
        double denominator=value.length*sumTT-sumT*sumT;
        double slope=denominator>1e-12?(value.length*sumTY-sumT*sumY)/denominator:0;
        double offset=(sumY-slope*sumT)/value.length,squares=0;
        for(int i=0;i<value.length;i++) { double error=value[i]-offset-slope*time[i]; squares+=error*error; }
        return Math.sqrt(squares/value.length);
    }

    public static final class Snapshot {
        public final double dominantHz,meanHz,inputRmsDeg,residualRmsDeg,sampleHz,renderHz;
        public final double sampleAgeMs,cpuDrawMs,appFrameMs,estimatedDisplayMs,uncertaintyMs;
        public final double[] seconds,inputCurve,residualCurve;
        public final double[] baselineResidualCurve;
        public final double baselineResidualRmsDeg;
        public final PitchPredictor.Prediction prediction;
        Snapshot(double peak,double mean,double rms,double residualRms,double sampleHz,double renderHz,
                double age,double cpu,double app,double estimate,double uncertainty,double[] seconds,double[] input,double[] residual,
                double[] baseline,double baselineRms,PitchPredictor.Prediction prediction) {
            dominantHz=peak; meanHz=mean; inputRmsDeg=rms; residualRmsDeg=residualRms;
            this.sampleHz=sampleHz; this.renderHz=renderHz; sampleAgeMs=age; cpuDrawMs=cpu;
            appFrameMs=app; estimatedDisplayMs=estimate; uncertaintyMs=uncertainty;
            this.seconds=seconds; inputCurve=input; residualCurve=residual;
            baselineResidualCurve=baseline; baselineResidualRmsDeg=baselineRms; this.prediction=prediction;
        }
    }
    public static final class Record {
        public final long presentationNs;
        public final double inputPitchDeg,residualPitchDeg,appliedPitchDeg,sampleAgeMs,cpuMs,appMs,estimatedDisplayMs;
        public final double anchorYawDeg,anchorPitchDeg;
        public final double baselineResidualPitchDeg;
        public final PitchPredictor.Prediction prediction;
        Record(long time,double input,double residual,double baseline,double applied,Quaternion anchor,double age,double cpu,double app,double estimate,
                PitchPredictor.Prediction prediction) {
            presentationNs=time; inputPitchDeg=input; residualPitchDeg=residual; appliedPitchDeg=applied;
            sampleAgeMs=age; cpuMs=cpu; appMs=app; estimatedDisplayMs=estimate;
            baselineResidualPitchDeg=baseline; this.prediction=prediction;
            anchorPitchDeg=anchor.pitchDegrees(); double[] forward=anchor.rotate(new double[]{0,0,-1});
            anchorYawDeg=Math.toDegrees(Math.atan2(-forward[0],-forward[2]));
        }
    }
    private static final class Pose {
        final Quaternion q; final long time; final double pitch;
        Pose(Quaternion q,long time) { this.q=q; this.time=time; pitch=q.pitchDegrees(); }
    }
    private static final class Frame {
        final Quaternion applied,anchor,baseline; final long time; final double age,cpu,app,estimate;
        final PitchPredictor.Prediction prediction;
        Frame(Quaternion q,Quaternion anchor,Quaternion baseline,PitchPredictor.Prediction prediction,
                long time,double age,double cpu,double app,double estimate) {
            applied=q; this.anchor=anchor; this.time=time; this.age=age; this.cpu=cpu; this.app=app; this.estimate=estimate;
            this.baseline=baseline; this.prediction=prediction;
        }
    }
}
