package com.rokid.stabledemo.core;

/** Causal, pitch-only constant-rate extrapolation. Raw rates use the pose's local device basis. */
public final class GyroPredictor {
    public static final double MAX_CORRECTION_DEG=.75;
    private long lastNs;
    private double lastX,lastY,lastZ;
    private State state=State.EMPTY;

    public void reset() { lastNs=0; state=State.EMPTY; }

    public State onGyro(double x,double y,double z,long sampleNs,long receivedNs) {
        if(sampleNs<=0 || sampleNs<=lastNs || receivedNs<sampleNs) return state;
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) { reset(); return state; }
        double speed=Math.sqrt(x*x+y*y+z*z),dt=lastNs==0?0:(sampleNs-lastNs)*1e-9;
        String status="active";
        if(dt<=0 || dt>.08) status="warming_up";
        else if(speed>Math.toRadians(180)) status="fast_turn";
        else {
            double dx=x-lastX,dy=y-lastY,dz=z-lastZ;
            if(Math.sqrt(dx*dx+dy*dy+dz*dz)/dt>Math.toRadians(3000)) status="rapid_change";
        }
        lastNs=sampleNs; lastX=x; lastY=y; lastZ=z;
        state=new State(x,y,z,sampleNs,receivedNs,status); return state;
    }

    public static boolean validHorizon(int ms) { return ms==0 || ms==15 || ms==20; }
    public static int nextHorizon(int ms) { return ms==0?15:ms==15?20:0; }

    public static final class State {
        public static final State EMPTY=new State(0,0,0,0,0,"no_gyro");
        private final double x,y,z;
        private final long sampleNs,receivedNs;
        private final String status;
        private State(double x,double y,double z,long ns,long received,String status) {
            this.x=x; this.y=y; this.z=z; sampleNs=ns; receivedNs=received; this.status=status;
        }
        public Result predict(Quaternion measured,long poseNs,long renderNs,int horizonMs,boolean enabled) {
            int requested=validHorizon(horizonMs)?horizonMs:0;
            if(requested==0) return new Result(requested,0,0,"disabled");
            if(!enabled) return new Result(requested,0,0,"paused");
            if(!"active".equals(status)) return new Result(requested,0,0,status);
            if(receivedNs>renderNs || poseNs>renderNs) return new Result(requested,0,0,"future_sample");
            if(poseNs<=0 || renderNs-poseNs>25_000_000L || renderNs-sampleNs>25_000_000L
                    || Math.abs(sampleNs-poseNs)>20_000_000L) return new Result(requested,0,0,"stale_sample");
            double[] forward=measured.rotate(new double[]{0,0,-1});
            double horizontal=Math.hypot(forward[0],forward[2]);
            if(horizontal<.1) return new Result(requested,0,0,"vertical_view");
            // d(q * forward)/dt = q * (omega cross forward); pitch rate is dy / horizontal.
            double pitchRate=measured.rotate(new double[]{-y,x,0})[1]/horizontal;
            double correction=Math.max(-MAX_CORRECTION_DEG,Math.min(MAX_CORRECTION_DEG,
                    Math.toDegrees(pitchRate)*requested*.001));
            return new Result(requested,requested,correction,"active");
        }
    }
    public static final class Result {
        public final int requestedMs;
        public final double horizonMs,correctionDeg;
        public final String status;
        private Result(int requested,double horizon,double correction,String status) {
            requestedMs=requested; horizonMs=horizon; correctionDeg=correction; this.status=status;
        }
    }
}
