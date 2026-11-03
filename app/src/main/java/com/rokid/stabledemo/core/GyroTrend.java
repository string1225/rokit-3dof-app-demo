package com.rokid.stabledemo.core;

import java.util.Arrays;

/** Causal change-point detection and recent-trend confirmation, on the sensor thread only. */
final class GyroTrend {
    private static final double STEP_NOISE=Math.toRadians(.1),MIN_ACCELERATION=Math.toRadians(30);
    private final long[] times=new long[10];
    private final double[][] rates=new double[10][3];
    private final double[] breakStep=new double[3];
    private int count,discarded;
    private long segmentNs;
    private String resetReason="startup";

    void reset(String reason) {
        discarded=count; count=0; segmentNs=0; resetReason=reason; Arrays.fill(breakStep,0);
    }

    Snapshot add(double x,double y,double z,long ns) {
        double[] current={x,y,z};
        if(count==1 && discarded>0) {
            double[] delta=subtract(current,rates[0]);
            if(length(delta)>Math.toRadians(2) && dot(delta,breakStep)<-.5*length(delta)*length(breakStep))
                cut("transient_return",delta);
        }
        if(count>=2) {
            Line previous=line(Math.min(3,count));
            double dt=(ns-times[count-1])*1e-9;
            double[] last=rates[count-1],delta=subtract(current,last);
            double[] innovation={delta[0]-previous.a[0]*dt,delta[1]-previous.a[1]*dt,delta[2]-previous.a[2]*dt};
            // The ten-point buffer supplies a robust change/noise baseline, not an averaged velocity.
            double threshold=Math.max(Math.toRadians(2),4*medianInnovation()+3*previous.error);
            if(length(innovation)>threshold) cut("innovation",delta);
            else if(length(current)>Math.toRadians(.5) && length(last)>Math.toRadians(.5) && dot(current,last)<0)
                cut("rate_reversal",delta);
            else if(count>=3 && confirmed(previous) && length(delta)>2*STEP_NOISE
                    && dot(delta,previous.a)<-.5*length(delta)*length(previous.a)) cut("trend_reversal",delta);
        }
        if(count==0) segmentNs=ns;
        if(count==10) {
            System.arraycopy(times,1,times,0,9);
            for(int i=0;i<9;i++) System.arraycopy(rates[i+1],0,rates[i],0,3);
            count=9;
        }
        times[count]=ns; System.arraycopy(current,0,rates[count],0,3); count++;
        double span=(ns-times[0])*1e-6;
        if(count<3) return new Snapshot(count,span,0,new double[3],0,"warming",segmentNs,resetReason,discarded);
        Line recent=line(3),selected=recent;
        int points=confirmed(recent)?3:0;
        if(points>0 && count>=5) {
            Line five=line(5);
            double ratio=length(five.a)/length(recent.a);
            if(confirmed(five) && ratio>=.5 && ratio<=2 && dot(recent.a,five.a)>=.85*length(recent.a)*length(five.a)) {
                selected=five; points=5;
            }
        }
        String status=points>0?"confirmed"+points:length(recent.a)<MIN_ACCELERATION?"constant_rate":"unconfirmed";
        return new Snapshot(count,span,points,points>0?selected.a:new double[3],selected.error,status,segmentNs,resetReason,discarded);
    }

    private void cut(String reason,double[] step) { reset(reason); System.arraycopy(step,0,breakStep,0,3); }

    private boolean confirmed(Line fit) {
        double acceleration=length(fit.a);
        if(fit.samples<3 || acceleration<MIN_ACCELERATION) return false;
        double span=(times[count-1]-times[count-fit.samples])*1e-9;
        if(fit.error>Math.max(Math.toRadians(.35),.1*acceleration*span)) return false;
        int aligned=0;
        for(int i=count-fit.samples+1;i<count;i++) {
            double along=dot(subtract(rates[i],rates[i-1]),fit.a)/acceleration;
            if(along < -STEP_NOISE) return false;
            if(along>STEP_NOISE) aligned++;
        }
        return aligned>=fit.samples-2 && (fit.samples!=3 || aligned==2);
    }

    private double medianInnovation() {
        if(count<3) return 0;
        double[] errors=new double[count-2];
        for(int i=2;i<count;i++) {
            double ratio=(double)(times[i]-times[i-1])/(times[i-1]-times[i-2]);
            double[] error=new double[3];
            for(int axis=0;axis<3;axis++) error[axis]=rates[i][axis]-rates[i-1][axis]-(rates[i-1][axis]-rates[i-2][axis])*ratio;
            errors[i-2]=length(error);
        }
        Arrays.sort(errors);
        return (errors[(errors.length-1)/2]+errors[errors.length/2])*.5;
    }

    private Line line(int n) {
        int begin=count-n; long latest=times[count-1];
        double meanT=0,denominator=0,error=0;
        double[] mean=new double[3],slope=new double[3];
        for(int i=begin;i<count;i++) {
            meanT+=(times[i]-latest)*1e-9/n;
            for(int axis=0;axis<3;axis++) mean[axis]+=rates[i][axis]/n;
        }
        for(int i=begin;i<count;i++) {
            double t=(times[i]-latest)*1e-9-meanT; denominator+=t*t;
            for(int axis=0;axis<3;axis++) slope[axis]+=t*(rates[i][axis]-mean[axis]);
        }
        for(int axis=0;axis<3;axis++) slope[axis]/=denominator;
        for(int i=begin;i<count;i++) for(int axis=0;axis<3;axis++) {
            double residual=rates[i][axis]-mean[axis]-slope[axis]*((times[i]-latest)*1e-9-meanT);
            error+=residual*residual/n;
        }
        return new Line(n,slope,Math.sqrt(error));
    }
    private static double[] subtract(double[] a,double[] b) { return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double length(double[] a) { return Math.sqrt(dot(a,a)); }
    private static final class Line {
        final int samples;
        final double[] a;
        final double error;
        Line(int n,double[] a,double error) { samples=n; this.a=a; this.error=error; }
    }
    static final class Snapshot {
        static final Snapshot EMPTY=new Snapshot(0,0,0,new double[3],0,"warming",0,"startup",0);
        final int samples,trendSamples,discarded;
        final double span,ax,ay,az,error;
        final long segmentNs;
        final String status,resetReason;
        Snapshot(int samples,double span,int trendSamples,double[] a,double error,String status,long segmentNs,String reason,int discarded) {
            this.samples=samples; this.span=span; this.trendSamples=trendSamples; ax=a[0]; ay=a[1]; az=a[2]; this.error=error;
            this.status=status; this.segmentNs=segmentNs; resetReason=reason; this.discarded=discarded;
        }
    }
}
