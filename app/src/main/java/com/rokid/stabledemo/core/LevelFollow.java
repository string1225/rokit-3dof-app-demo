package com.rokid.stabledemo.core;

/** Fast head compensation with slow yaw/pitch following and optional viewport-boundary recentering. */
public final class LevelFollow {
    public static final double DEFAULT_TAU_SECONDS=1.5;
    public static final double DEFAULT_SPEED_DEG_S=12;
    public static final double FOLLOW_START_FRACTION=.2;
    private final double tauSeconds,maxSpeedRad;
    private double yaw,pitch;
    private long lastNs;

    public LevelFollow(double tauSeconds,double maxSpeedDegS) {
        if(!Double.isFinite(tauSeconds)||tauSeconds<=0||!Double.isFinite(maxSpeedDegS)||maxSpeedDegS<=0)
            throw new IllegalArgumentException("Invalid follow settings");
        this.tauSeconds=tauSeconds; maxSpeedRad=Math.toRadians(maxSpeedDegS);
    }
    public void reset() { yaw=pitch=0; lastNs=0; }

    public Result update(Quaternion head,long nowNs) {
        return update(head,nowNs,null);
    }

    /** Check the screen-space limit before slow following can conceal a just-crossed boundary. */
    public Result update(Quaternion head,long nowNs,Projection viewport) {
        return update(head,nowNs,viewport,true);
    }

    /** A disabled follower keeps its anchor, including when the plane leaves the viewport. */
    public Result update(Quaternion head,long nowNs,Projection viewport,boolean enabled) {
        Quaternion compensated=anchor().inverse().multiply(head);
        boolean snapped=enabled && viewport!=null && viewport.centerOutsideViewport(compensated);
        if(snapped) recenter(head,nowNs);
        boolean shouldFollow=enabled && (viewport==null || viewport.centerOffsetExceeds(compensated,FOLLOW_START_FRACTION));
        double[] target=direction(head);
        if(shouldFollow && lastNs>0 && nowNs>lastNs) {
            double elapsed=(nowNs-lastNs)*1e-9;
            // Do not turn a render stall or background gap into an immediate anchor jump.
            if(elapsed<=.25) {
                double dt=Math.min(elapsed,.1),alpha=-Math.expm1(-dt/tauSeconds);
                double dy=wrap(target[0]-yaw)*alpha,dp=(target[1]-pitch)*alpha;
                double length=Math.hypot(dy,dp),limit=maxSpeedRad*dt;
                if(length>limit) { dy*=limit/length; dp*=limit/length; }
                yaw=wrap(yaw+dy); pitch+=dp;
            }
        }
        if(nowNs>lastNs) lastNs=nowNs;
        Quaternion anchor=anchor();
        // Projection uses inverse(compensatedHead) = inverse(head) * anchor.
        return new Result(anchor,anchor.inverse().multiply(head),snapped);
    }

    private Quaternion anchor() {
        return Quaternion.axisAngle(0,1,0,yaw).multiply(Quaternion.axisAngle(1,0,0,pitch));
    }

    /** Position-only recenter: never absorb the head's roll into the anchor. */
    public void recenter(Quaternion head,long nowNs) {
        double[] target=direction(head); yaw=target[0]; pitch=target[1]; lastNs=nowNs;
    }
    private double[] direction(Quaternion head) {
        double[] forward=head.rotate(new double[]{0,0,-1});
        double horizontal=Math.hypot(forward[0],forward[2]);
        double heading=horizontal>1e-6?Math.atan2(-forward[0],-forward[2]):yaw;
        return new double[]{heading,Math.atan2(forward[1],horizontal)};
    }
    private static double wrap(double radians) { return Math.atan2(Math.sin(radians),Math.cos(radians)); }

    public static final class Result {
        public final Quaternion anchor,compensatedHead;
        public final boolean snapped;
        Result(Quaternion anchor,Quaternion compensatedHead,boolean snapped) {
            this.anchor=anchor; this.compensatedHead=compensatedHead; this.snapped=snapped;
        }
    }
}
