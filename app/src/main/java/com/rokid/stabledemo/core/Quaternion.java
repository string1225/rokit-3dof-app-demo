package com.rokid.stabledemo.core;

/** Unit Hamilton quaternion, device-to-world; no Android dependency. */
public final class Quaternion {
    public static final Quaternion IDENTITY = new Quaternion(0, 0, 0, 1);
    public final double x, y, z, w;

    public Quaternion(double x, double y, double z, double w) {
        double norm = Math.sqrt(x*x + y*y + z*z + w*w);
        if (!Double.isFinite(norm) || norm < 1e-10) throw new IllegalArgumentException("Invalid pose");
        this.x = x/norm; this.y = y/norm; this.z = z/norm; this.w = w/norm;
    }

    public static Quaternion axisAngle(double x, double y, double z, double radians) {
        double norm = Math.sqrt(x*x + y*y + z*z);
        if (norm < 1e-10) return IDENTITY;
        double s = Math.sin(radians/2) / norm;
        return new Quaternion(x*s, y*s, z*s, Math.cos(radians/2));
    }

    public Quaternion inverse() { return new Quaternion(-x, -y, -z, w); }

    public Quaternion interpolate(Quaternion other,double fraction) {
        double t=Math.max(0,Math.min(1,fraction));
        double dot=x*other.x+y*other.y+z*other.z+w*other.w;
        double sign=dot<0?-1:1; dot=Math.min(1,Math.abs(dot));
        double a=1-t,b=t;
        if(dot<.9995) {
            double angle=Math.acos(dot),denominator=Math.sin(angle);
            a=Math.sin((1-t)*angle)/denominator; b=Math.sin(t*angle)/denominator;
        }
        return new Quaternion(a*x+b*other.x*sign,a*y+b*other.y*sign,
                a*z+b*other.z*sign,a*w+b*other.w*sign);
    }

    public double pitchDegrees() {
        double[] forward=rotate(new double[]{0,0,-1});
        return Math.toDegrees(Math.atan2(forward[1],Math.hypot(forward[0],forward[2])));
    }

    public Quaternion multiply(Quaternion b) {
        return new Quaternion(w*b.x+x*b.w+y*b.z-z*b.y,
                w*b.y-x*b.z+y*b.w+z*b.x, w*b.z+x*b.y-y*b.x+z*b.w,
                w*b.w-x*b.x-y*b.y-z*b.z);
    }

    public double[] rotate(double[] p) {
        double tx = 2*(y*p[2]-z*p[1]), ty = 2*(z*p[0]-x*p[2]), tz = 2*(x*p[1]-y*p[0]);
        return new double[]{p[0]+w*tx+y*tz-z*ty, p[1]+w*ty+z*tx-x*tz, p[2]+w*tz+x*ty-y*tx};
    }

    /** Proper rotation of basis, determinant +1; never swap Euler angles. */
    public Quaternion toView(boolean demoAxes) { return demoAxes ? new Quaternion(x, z, -y, w) : this; }
    public static double[] demoVector(double x, double y, double z) { return new double[]{x,z,-y}; }
}
