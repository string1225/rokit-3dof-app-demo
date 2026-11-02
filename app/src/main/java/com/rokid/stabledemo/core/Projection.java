package com.rokid.stabledemo.core;

/** Pinhole projection of the reference screen plane, with inverse head rotation. */
public final class Projection {
    private final double cx, cy, focal,contentTop,contentBottom;
    public Projection(double width, double height, double horizontalFovDeg) {
        this(width,height,horizontalFovDeg,0,height);
    }
    /** Render into a central content region while keeping focal scale based on the full width. */
    public Projection(double width,double height,double horizontalFovDeg,double contentTop,double contentBottom) {
        if (!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0
                ||!Double.isFinite(horizontalFovDeg)||horizontalFovDeg<5||horizontalFovDeg>120
                ||!Double.isFinite(contentTop)||!Double.isFinite(contentBottom)
                ||contentTop<0||contentBottom>height||contentBottom<=contentTop)
            throw new IllegalArgumentException("Invalid projection or content bounds");
        this.contentTop=contentTop; this.contentBottom=contentBottom;
        cx=width/2; cy=(contentTop+contentBottom)/2;
        focal = width/(2*Math.tan(Math.toRadians(horizontalFovDeg)/2));
    }
    public double[] point(double x, double y, Quaternion relativeHead) {
        double[] p = relativeHead.inverse().rotate(new double[]{(x-cx)/focal, (cy-y)/focal, -1});
        if (p[2] > -.05) return null;
        return new double[]{cx+focal*p[0]/-p[2], cy-focal*p[1]/-p[2]};
    }

    /** Center displacement exceeds 50% of content width OR height; a hidden rear plane also qualifies. */
    public boolean centerOutsideViewport(Quaternion relativeHead) {
        double[] center=point(cx,cy,relativeHead);
        // Ignore only rounding at the exact boundary, not a meaningful pixel displacement.
        return center==null || Math.abs(center[0]-cx)>cx+1e-7
                ||center[1]<contentTop-1e-7||center[1]>contentBottom+1e-7;
    }
}
