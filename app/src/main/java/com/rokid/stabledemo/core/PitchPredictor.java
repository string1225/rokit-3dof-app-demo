package com.rokid.stabledemo.core;

import java.util.ArrayDeque;

/** Single-frequency pitch increment prediction. Fit on the sensor thread; State is immutable. */
public final class PitchPredictor {
    public enum Profile {
        STANDARD(.94,3,3,.12), RELAXED(.88,2,2,.18);
        public final double minQuality,minCycles,frequencyToleranceHz;
        public final int requiredStableFits;
        Profile(double quality,double cycles,int fits,double tolerance) {
            minQuality=quality; minCycles=cycles; requiredStableFits=fits; frequencyToleranceHz=tolerance;
        }
    }
    private static final long WINDOW_NS=3_500_000_000L,FIT_NS=100_000_000L;
    private final Profile profile;
    private final ArrayDeque<Sample> samples=new ArrayDeque<>();
    private long lastNs,lastStoredNs,lastFitNs;
    private Quaternion previous;
    private double previousPitch,strength,candidateHz=Double.NaN,targetStrength;
    private int stableFits;
    private Model model;
    private State state=State.EMPTY;
    private double fitQuality=Double.NaN,fitCycles=Double.NaN;
    private String gate="warming_up";

    public PitchPredictor() { this(Profile.STANDARD); }
    public PitchPredictor(Profile profile) { this.profile=profile==null?Profile.STANDARD:profile; }

    public void reset() {
        samples.clear(); lastNs=lastStoredNs=lastFitNs=0; previous=null;
        strength=targetStrength=0; candidateHz=Double.NaN; stableFits=0; model=null; state=State.EMPTY;
        fitQuality=fitCycles=Double.NaN; gate="warming_up";
    }

    public State onPose(Quaternion head,long timestampNs) {
        if(timestampNs<=0 || timestampNs<=lastNs) return state;
        double pitch=head.pitchDegrees();
        double dt=lastNs>0?(timestampNs-lastNs)*1e-9:0;
        if(dt>.08) { reset(); dt=0; }
        if(previous!=null && dt>0) {
            double dot=Math.abs(head.x*previous.x+head.y*previous.y+head.z*previous.z+head.w*previous.w);
            double rate=Math.toDegrees(2*Math.acos(Math.min(1,dot)))/dt;
            double pitchRate=Math.abs(pitch-previousPitch)/dt;
            boolean turn=rate>Math.max(35,pitchRate*1.3+15);
            boolean jump=model!=null && Math.abs(pitch-model.value(timestampNs))>Math.max(.3,model.amplitude*.6);
            if(turn || jump) { reset(); gate=turn?"turn":"model_mismatch"; dt=0; }
        }
        previous=head; previousPitch=pitch; lastNs=timestampNs;
        if(lastStoredNs==0 || timestampNs-lastStoredNs>=8_000_000L) {
            samples.addLast(new Sample(timestampNs,pitch)); lastStoredNs=timestampNs;
            while(samples.size()>400 || timestampNs-samples.peekFirst().time>WINDOW_NS) samples.removeFirst();
        }
        if(timestampNs-lastFitNs>=FIT_NS && samples.size()>=50
                && timestampNs-samples.peekFirst().time>=1_000_000_000L) {
            lastFitNs=timestampNs;
            Model fit=fit(timestampNs);
            fitQuality=fit==null?Double.NaN:fit.quality; fitCycles=fit==null?Double.NaN:fit.span*fit.hz;
            gate=fit==null?"insufficient_motion":fit.quality<=profile.minQuality?"quality"
                    :fit.amplitude<.04 || fit.amplitude>5?"amplitude":Math.abs(fit.trend)>=3?"trend"
                    :fitCycles<profile.minCycles?"cycles":"frequency_stability";
            boolean good=fit!=null && "frequency_stability".equals(gate);
            if(good) {
                stableFits=Double.isFinite(candidateHz)&&Math.abs(candidateHz-fit.hz)<profile.frequencyToleranceHz?stableFits+1:1;
                candidateHz=fit.hz;
                if(stableFits>=profile.requiredStableFits) {
                    model=fit; targetStrength=clamp((fit.quality-profile.minQuality)/(1-profile.minQuality),0,1); gate="active";
                }
                else targetStrength=0;
            } else { stableFits=0; candidateHz=Double.NaN; targetStrength=0; }
        }
        if(model==null || timestampNs-model.fitAt>250_000_000L) targetStrength=0;
        if(dt>0) {
            double tau=targetStrength>strength?.35:.06;
            strength+=(targetStrength-strength)*(-Math.expm1(-dt/tau));
        }
        state=new State(model,timestampNs,strength,fitQuality,fitCycles,stableFits,gate); return state;
    }

    private Model fit(long nowNs) {
        Sample[] history=samples.toArray(new Sample[0]);
        long start=history[0].time,end=history[history.length-1].time;
        double span=(end-start)*1e-9;
        if(span<=0 || (history.length-1)/span<35) return null;
        int n=128,index=0; double[] values=new double[n];
        double sum=0,sumT=0,sumTT=0,sumTY=0;
        for(int i=0;i<n;i++) {
            double time=start+(end-start)*(double)i/(n-1);
            while(index<history.length-2 && history[index+1].time<time) index++;
            double fraction=(time-history[index].time)/(history[index+1].time-history[index].time);
            values[i]=history[index].pitch+(history[index+1].pitch-history[index].pitch)*fraction;
            double t=-span+span*i/(n-1);
            sum+=values[i]; sumT+=t; sumTT+=t*t; sumTY+=t*values[i];
        }
        double slope=(n*sumTY-sumT*sum)/(n*sumTT-sumT*sumT),offset=(sum-slope*sumT)/n,variance=0;
        for(int i=0;i<n;i++) { double e=values[i]-offset-slope*(-span+span*i/(n-1)); variance+=e*e; }
        if(Math.sqrt(variance/n)<.03) return null;
        Model best=null;
        for(int k=10;k<=80;k++) {
            Model candidate=fitFrequency(values,span,k*.1,end,nowNs,variance);
            if(candidate!=null && (best==null || candidate.error<best.error)) best=candidate;
        }
        if(best==null) return null;
        int center=(int)Math.round(best.hz*100);
        for(int k=Math.max(100,center-9);k<=Math.min(800,center+9);k++) {
            Model candidate=fitFrequency(values,span,k*.01,end,nowNs,variance);
            if(candidate!=null && candidate.error<best.error) best=candidate;
        }
        return best;
    }

    private static Model fitFrequency(double[] values,double span,double hz,long origin,long fitAt,double variance) {
        int n=values.length; double[][] normal=new double[4][4]; double[] rhs=new double[4],x={1,0,0,0};
        double step=2*Math.PI*hz*span/(n-1),cs=Math.cos(step),ss=Math.sin(step);
        double sine=Math.sin(-2*Math.PI*hz*span),cosine=Math.cos(-2*Math.PI*hz*span);
        for(int i=0;i<n;i++) {
            x[1]=-span+span*i/(n-1); x[2]=sine; x[3]=cosine;
            for(int j=0;j<4;j++) { rhs[j]+=x[j]*values[i]; for(int k=0;k<=j;k++) normal[j][k]+=x[j]*x[k]; }
            double next=sine*cs+cosine*ss; cosine=cosine*cs-sine*ss; sine=next;
        }
        for(int j=0;j<4;j++) for(int k=0;k<j;k++) normal[k][j]=normal[j][k];
        double[] coefficient=solve(normal,rhs); if(coefficient==null) return null;
        double error=0;
        sine=Math.sin(-2*Math.PI*hz*span); cosine=Math.cos(-2*Math.PI*hz*span);
        for(int i=0;i<n;i++) {
            double fitted=coefficient[0]+coefficient[1]*(-span+span*i/(n-1))+coefficient[2]*sine+coefficient[3]*cosine;
            double e=values[i]-fitted; error+=e*e;
            double next=sine*cs+cosine*ss; cosine=cosine*cs-sine*ss; sine=next;
        }
        return new Model(origin,fitAt,hz,coefficient,span,error,1-error/variance);
    }

    private static double[] solve(double[][] a,double[] b) {
        for(int i=0;i<4;i++) {
            int pivot=i; for(int j=i+1;j<4;j++) if(Math.abs(a[j][i])>Math.abs(a[pivot][i])) pivot=j;
            if(Math.abs(a[pivot][i])<1e-10) return null;
            double[] row=a[i]; a[i]=a[pivot]; a[pivot]=row; double value=b[i]; b[i]=b[pivot]; b[pivot]=value;
            double divisor=a[i][i]; for(int k=i;k<4;k++) a[i][k]/=divisor; b[i]/=divisor;
            for(int j=0;j<4;j++) if(j!=i) {
                double factor=a[j][i]; for(int k=i;k<4;k++) a[j][k]-=factor*a[i][k]; b[j]-=factor*b[i];
            }
        }
        return b;
    }
    private static double clamp(double x,double low,double high) { return Math.max(low,Math.min(high,x)); }

    public static final class State {
        public static final State EMPTY=new State(null,0,0,Double.NaN,Double.NaN,0,"warming_up");
        private final Model model; private final long sampleNs; private final double strength;
        private final double quality,cycles;
        private final int stableFits;
        private final String gate;
        private State(Model model,long sampleNs,double strength,double quality,double cycles,int fits,String gate) {
            this.model=model; this.sampleNs=sampleNs; this.strength=strength;
            this.quality=quality; this.cycles=cycles; stableFits=fits; this.gate=gate;
        }
        public Prediction predict(Quaternion measured,long renderNs,double displayLeadMs,boolean enabled) {
            long age=renderNs-sampleNs;
            String reason=!enabled?"disabled":age<0 || age>25_000_000L?"stale_sample"
                    :!Double.isFinite(displayLeadMs)?"invalid_lead":model==null?gate:strength<.001?"ramping_up"
                    :sampleNs-model.fitAt>250_000_000L?"expired_model":"active";
            if(!"active".equals(reason)) return unavailable(measured,enabled,reason);
            double horizon=clamp(age*1e-6+Math.max(0,displayLeadMs),0,20);
            long target=sampleNs+Math.round(horizon*1e6);
            double correction=clamp((model.wave(target)-model.wave(sampleNs))*strength,-.75,.75);
            double[] forward=measured.rotate(new double[]{0,0,-1});
            double horizontal=Math.hypot(forward[0],forward[2]);
            if(horizontal<.1) return unavailable(measured,enabled,"vertical_view");
            Quaternion delta=Quaternion.axisAngle(-forward[2]/horizontal,0,forward[0]/horizontal,Math.toRadians(correction));
            return new Prediction(delta.multiply(measured),enabled,horizon,model.hz,strength,correction,
                    horizon,correction,quality,cycles,stableFits,"active",0,0,0,"disabled");
        }
        private Prediction unavailable(Quaternion measured,boolean enabled,String reason) {
            return new Prediction(measured,enabled,0,Double.NaN,0,0,0,0,quality,cycles,stableFits,reason,0,0,0,"disabled");
        }
    }
    public static final class Prediction {
        public static final Prediction NONE=unavailable(Quaternion.IDENTITY,false);
        public final Quaternion pose;
        public final boolean enabled;
        public final double horizonMs,frequencyHz,strength,correctionDeg;
        public final double periodicHorizonMs,periodicCorrectionDeg,fitQuality,fitCycles;
        public final int stableFits,gyroRequestedMs;
        public final double gyroHorizonMs,gyroCorrectionDeg;
        public final String periodicStatus,gyroStatus;
        private Prediction(Quaternion pose,boolean enabled,double horizon,double hz,double strength,double correction,
                double periodicHorizon,double periodicCorrection,double quality,double cycles,int fits,String periodicStatus,
                int gyroRequested,double gyroHorizon,double gyroCorrection,String gyroStatus) {
            this.pose=pose; this.enabled=enabled; horizonMs=horizon; frequencyHz=hz; this.strength=strength; correctionDeg=correction;
            periodicHorizonMs=periodicHorizon; periodicCorrectionDeg=periodicCorrection; fitQuality=quality; fitCycles=cycles;
            stableFits=fits; this.periodicStatus=periodicStatus; gyroRequestedMs=gyroRequested;
            gyroHorizonMs=gyroHorizon; gyroCorrectionDeg=gyroCorrection; this.gyroStatus=gyroStatus;
        }
        public static Prediction unavailable(Quaternion measured,boolean enabled) {
            return new Prediction(measured,enabled,0,Double.NaN,0,0,0,0,Double.NaN,Double.NaN,0,
                    enabled?"warming_up":"disabled",0,0,0,"disabled");
        }
        /** Blend two estimates of the same pitch motion; never add two full forecasts. */
        public Prediction withGyro(Quaternion measured,GyroPredictor.Result gyro) {
            double periodic=periodicCorrectionDeg,contribution=0;
            if(gyro.horizonMs>0) {
                periodic=strength>0?clamp(periodicCorrectionDeg/strength,-.75,.75)*strength:0;
                contribution=gyro.correctionDeg*(1-strength);
            }
            double correction=clamp(periodic+contribution,-.75,.75);
            Quaternion predicted=measured;
            if(correction!=0) {
                double[] forward=measured.rotate(new double[]{0,0,-1});
                double horizontal=Math.hypot(forward[0],forward[2]);
                predicted=Quaternion.axisAngle(-forward[2]/horizontal,0,forward[0]/horizontal,
                        Math.toRadians(correction)).multiply(measured);
            }
            return new Prediction(predicted,enabled,Math.max(periodicHorizonMs,gyro.horizonMs),frequencyHz,strength,correction,
                    periodicHorizonMs,periodic,fitQuality,fitCycles,stableFits,periodicStatus,
                    gyro.requestedMs,gyro.horizonMs,contribution,gyro.status);
        }
        public String method() {
            return gyroHorizonMs>0?(periodicHorizonMs>0?"blend":"gyro"):(periodicHorizonMs>0?"periodic":"none");
        }
    }
    private static final class Sample {
        final long time; final double pitch;
        Sample(long time,double pitch) { this.time=time; this.pitch=pitch; }
    }
    private static final class Model {
        final long origin,fitAt; final double hz,bias,trend,sine,cosine,span,error,quality,amplitude;
        Model(long origin,long fitAt,double hz,double[] c,double span,double error,double quality) {
            this.origin=origin; this.fitAt=fitAt; this.hz=hz; bias=c[0]; trend=c[1]; sine=c[2]; cosine=c[3];
            this.span=span; this.error=error; this.quality=quality; amplitude=Math.hypot(sine,cosine);
        }
        double wave(long ns) { double phase=2*Math.PI*hz*(ns-origin)*1e-9; return sine*Math.sin(phase)+cosine*Math.cos(phase); }
        double value(long ns) { return bias+trend*(ns-origin)*1e-9+wave(ns); }
    }
}
