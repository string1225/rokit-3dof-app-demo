package com.rokid.stabledemo.core;

/** Pinhole projection of the reference screen plane, with inverse head rotation. */
public final class Projection {
    private final double cx, cy, focal;
    public Projection(double width, double height, double horizontalFovDeg) {
        if (width <= 0 || height <= 0 || !Double.isFinite(horizontalFovDeg)
                || horizontalFovDeg < 5 || horizontalFovDeg > 120) throw new IllegalArgumentException("Invalid FOV");
        cx = width/2; cy = height/2;
        focal = width/(2*Math.tan(Math.toRadians(horizontalFovDeg)/2));
    }
    public double[] point(double x, double y, Quaternion relativeHead) {
        double[] p = relativeHead.inverse().rotate(new double[]{(x-cx)/focal, (cy-y)/focal, -1});
        if (p[2] > -.05) return null;
        return new double[]{cx+focal*p[0]/-p[2], cy-focal*p[1]/-p[2]};
    }
}
