package com.rokid.stabledemo.core;

/** Causal body-rate prediction; all history fits run on the sensor thread. */
public final class GyroPredictor {
    public static final double MAX_CORRECTION_DEG=.75;
    public static final int MAX_HORIZON_MS=100;
    public enum Model {
        LATEST("latest","恒速",1), WINDOW3("window3","三采样",3),
        WINDOW5("window5","五采样",5), WINDOW10("window10","十采样",10);
        public final String id,label;
        public final int window;
        Model(String id,String label,int window) { this.id=id; this.label=label; this.window=window; }
        public static Model fromId(String id) {
            for(Model model:values()) if(model.id.equals(id)) return model;
            return LATEST;
        }
        public Model next() { return values()[(ordinal()+1)%values().length]; }
    }
    private final long[] times=new long[10];
    private final double[][] rates=new double[10][3];
    private int count;
    private long lastNs;
    private double lastX,lastY,lastZ;
    private State state=State.EMPTY;

    public void reset() { lastNs=0; count=0; state=State.EMPTY; }
    public State onGyro(double x,double y,double z,long sampleNs,long receivedNs) {
        if(sampleNs<=0 || sampleNs<=lastNs || receivedNs<sampleNs) return state;
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) { reset(); return state; }
        double dt=lastNs==0?0:(sampleNs-lastNs)*1e-9;
        String status="active";
        if(dt<=0 || dt>.08) status="warming_up";
        else if(norm(x,y,z)>Math.toRadians(180)) status="fast_turn";
        else if(norm(x-lastX,y-lastY,z-lastZ)/dt>Math.toRadians(3000)) status="rapid_change";
        if(!"active".equals(status)) count=0;
        if(count==10) {
            System.arraycopy(times,1,times,0,9);
            for(int i=0;i<9;i++) System.arraycopy(rates[i+1],0,rates[i],0,3);
            count=9;
        }
        times[count]=sampleNs; rates[count][0]=x; rates[count][1]=y; rates[count][2]=z; count++;
        Fit[] fits={fit(3,sampleNs),fit(5,sampleNs),fit(10,sampleNs)};
        lastNs=sampleNs; lastX=x; lastY=y; lastZ=z;
        state=new State(x,y,z,fits,sampleNs,receivedNs,status); return state;
    }
    private Fit fit(int window,long sampleNs) {
        int n=Math.min(count,window),begin=count-n;
        double span=(sampleNs-times[begin])*1e-6;
        if(n<window) return new Fit(n,span,0,0,0,0);
        double meanT=0,denominator=0,error=0;
        double[] mean=new double[3],slope=new double[3];
        for(int i=begin;i<count;i++) {
            meanT+=(times[i]-sampleNs)*1e-9/n;
            for(int axis=0;axis<3;axis++) mean[axis]+=rates[i][axis]/n;
        }
        for(int i=begin;i<count;i++) {
            double t=(times[i]-sampleNs)*1e-9-meanT; denominator+=t*t;
            for(int axis=0;axis<3;axis++) slope[axis]+=t*(rates[i][axis]-mean[axis]);
        }
        for(int axis=0;axis<3;axis++) slope[axis]/=denominator;
        for(int i=begin;i<count;i++) for(int axis=0;axis<3;axis++) {
            double residual=rates[i][axis]-mean[axis]-slope[axis]*((times[i]-sampleNs)*1e-9-meanT);
            error+=residual*residual/n;
        }
        return new Fit(n,span,slope[0],slope[1],slope[2],Math.sqrt(error));
    }
    public static boolean validHorizon(int ms) { return ms>=0 && ms<=MAX_HORIZON_MS; }
    public static int nextHorizon(int ms) { return ms==0?15:ms==15?20:0; }
    public static boolean validCap(double degrees) { return degrees==.75 || degrees==3 || degrees==6; }
    private static double norm(double x,double y,double z) { return Math.sqrt(x*x+y*y+z*z); }
    private static final class Fit {
        static final Fit EMPTY=new Fit(0,0,0,0,0,0);
        final int samples;
        final double span,ax,ay,az,error;
        Fit(int n,double span,double ax,double ay,double az,double error) {
            samples=n; this.span=span; this.ax=ax; this.ay=ay; this.az=az; this.error=error;
        }
    }
    public static final class State {
        public static final State EMPTY=new State(0,0,0,new Fit[]{Fit.EMPTY,Fit.EMPTY,Fit.EMPTY},0,0,"no_gyro");
        private final double x,y,z;
        private final Fit[] fits;
        private final long sampleNs,receivedNs;
        private final String status;
        private State(double x,double y,double z,Fit[] fits,long sample,long received,String status) {
            this.x=x; this.y=y; this.z=z; this.fits=fits; sampleNs=sample; receivedNs=received; this.status=status;
        }
        /** Legacy pitch-only, source-timestamp horizon, retained for paired algorithm controls. */
        public Result predict(Quaternion measured,long poseNs,long readNs,int ms,boolean enabled) {
            return predict(measured,poseNs,readNs,ms,enabled,Model.LATEST,MAX_CORRECTION_DEG);
        }
        public Result predict(Quaternion measured,long poseNs,long readNs,int ms,boolean enabled,Model model,double cap) {
            return predictInternal(measured,poseNs,readNs,ms,ms,enabled,model,cap,false);
        }
        /** Live slider: target=read time+selected lead; total propagation includes the source pose's age. */
        public Result predictDisplay(Quaternion measured,long poseNs,long readNs,int leadMs,boolean enabled,Model model,double cap) {
            double total=leadMs==0?0:Math.max(0,(readNs-poseNs)*1e-6)+leadMs;
            return predictInternal(measured,poseNs,readNs,leadMs,total,enabled,model,cap,true);
        }
        private Result predictInternal(Quaternion measured,long poseNs,long readNs,int selected,double horizon,
                boolean enabled,Model model,double cap,boolean fullAxes) {
            int requested=validHorizon(selected)?selected:0;
            if(!validCap(cap)) cap=MAX_CORRECTION_DEG;
            String reason=requested==0?"disabled":!enabled?"paused":!"active".equals(status)?status
                    :receivedNs>readNs || poseNs>readNs?"future_sample"
                    :poseNs<=0 || readNs-poseNs>25_000_000L || readNs-sampleNs>25_000_000L
                        || Math.abs(sampleNs-poseNs)>20_000_000L?"stale_sample":"active";
            Fit fit=model==Model.LATEST?Fit.EMPTY:fits[model==Model.WINDOW3?0:model==Model.WINDOW5?1:2];
            double[] forward=measured.rotate(new double[]{0,0,-1}); double horizontal=Math.hypot(forward[0],forward[2]);
            double rate=horizontal>=.1?Math.toDegrees(measured.rotate(new double[]{-y,x,0})[1]/horizontal):0;
            double acceleration=horizontal>=.1?Math.toDegrees(measured.rotate(new double[]{-fit.ay,fit.ax,0})[1]/horizontal):0;
            double correction=0,effective=0,raw=0,rotation=0;
            Quaternion predicted=measured;
            String motion=model==Model.LATEST?"constant_rate":"warming_window";
            if("active".equals(reason)) {
                if(!fullAxes && horizontal<.1) reason="vertical_view";
                else {
                    double t=horizon*.001; effective=t;
                    boolean window=model!=Model.LATEST && fit.samples==model.window;
                    double speed=fullAxes?norm(x,y,z):Math.toRadians(Math.abs(rate));
                    boolean noisy=fit.error>Math.toRadians(Math.max(1.5,.15*Math.toDegrees(norm(x,y,z))));
                    double vx=fullAxes?x:Math.toRadians(rate),vy=fullAxes?y:0,vz=fullAxes?z:0;
                    double ax=fullAxes?fit.ax:Math.toRadians(acceleration),ay=fullAxes?fit.ay:0,az=fullAxes?fit.az:0;
                    double dx=vx*t,dy=vy*t,dz=vz*t;
                    if(window && noisy) { acceleration=0; motion="noisy_window"; }
                    else if(window && speed<Math.toRadians(.5)) { dx=dy=dz=0; effective=0; motion="stop_guard"; }
                    else if(window) {
                        double along=(vx*ax+vy*ay+vz*az)/Math.max(speed,1e-12);
                        if(along<Math.toRadians(-30)) {
                            effective=Math.min(t,speed/-along);
                            double distance=speed*effective+.5*along*effective*effective;
                            dx=vx*distance/speed; dy=vy*distance/speed; dz=vz*distance/speed; motion="braking";
                        } else {
                            double tau=.020,factor=tau*(t-tau*(1-Math.exp(-t/tau)));
                            dx+=ax*factor; dy+=ay*factor; dz+=az*factor; motion="damped_acceleration";
                        }
                    }
                    raw=fullAxes?Math.toDegrees(norm(dx,dy,dz)):Math.toDegrees(dx);
                    double scale=Math.abs(raw)>cap?cap/Math.abs(raw):1;
                    if(fullAxes) {
                        predicted=measured.multiply(Quaternion.axisAngle(dx,dy,dz,norm(dx,dy,dz)*scale));
                        correction=predicted.pitchDegrees()-measured.pitchDegrees(); rotation=Math.abs(raw)*scale;
                    } else {
                        correction=raw*scale; rotation=Math.abs(correction);
                        predicted=Quaternion.axisAngle(-forward[2]/horizontal,0,forward[0]/horizontal,Math.toRadians(correction)).multiply(measured);
                    }
                }
            }
            double appliedHorizon="active".equals(reason)?horizon:0;
            long target=appliedHorizon>0?(fullAxes?readNs+requested*1_000_000L:poseNs+Math.round(horizon*1e6)):0;
            return new Result(requested,appliedHorizon,correction,reason,model.id,motion,
                    model==Model.LATEST?(sampleNs>0?1:0):fit.samples,fit.span,rate,acceleration,effective*1000,raw,cap,
                    sampleNs,receivedNs,poseNs,readNs,target,predicted,fullAxes,rotation);
        }
    }
    public static final class Result {
        public static final Result EMPTY=State.EMPTY.predict(Quaternion.IDENTITY,0,0,0,false);
        public final int requestedMs,samples;
        public final double horizonMs,correctionDeg,windowMs,rateDegS,accelerationDegS2,effectiveMotionMs,unclampedDeg,maxCorrectionDeg,rotationDeg;
        public final String status,model,motion;
        public final long sampleNs,receivedNs,poseNs,readNs,targetNs;
        public final boolean limited,fullAxes;
        public final Quaternion pose;
        private Result(int requested,double horizon,double correction,String status,String model,String motion,int samples,
                double window,double rate,double acceleration,double effective,double raw,double cap,long sample,long received,long source,long read,
                long target,Quaternion pose,boolean fullAxes,double rotation) {
            requestedMs=requested; horizonMs=horizon; correctionDeg=correction; this.status=status;
            this.model=model; this.motion=motion; this.samples=samples; windowMs=window; rateDegS=rate;
            accelerationDegS2=acceleration; effectiveMotionMs=effective; unclampedDeg=raw; maxCorrectionDeg=cap;
            sampleNs=sample; receivedNs=received; poseNs=source; readNs=read; limited=Math.abs(raw)>cap;
            targetNs=target; this.pose=pose; this.fullAxes=fullAxes; rotationDeg=rotation;
        }
    }
}
