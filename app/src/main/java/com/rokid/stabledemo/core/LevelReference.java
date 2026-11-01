package com.rokid.stabledemo.core;

/** Fixed gravity-aligned view frame; Android rotation vectors and Fusion ENU use world +Z up. */
public final class LevelReference {
    private final Quaternion levelFromViewWorld;
    private final boolean demoAxes;

    public LevelReference(Quaternion startup,boolean demoAxes) {
        this.demoAxes=demoAxes;
        Quaternion view=startup.toView(demoAxes);
        double[] worldUp=demoAxes?Quaternion.demoVector(0,0,1):new double[]{0,0,1};
        double[] up=view.inverse().rotate(worldUp);
        // Shortest rotation from the startup-local up vector to view +Y. Do not zero roll.
        double sine=Math.hypot(up[0],up[2]);
        Quaternion tilt=sine>1e-10
                ?Quaternion.axisAngle(-up[2],0,up[0],Math.atan2(sine,up[1]))
                :up[1]>=0?Quaternion.IDENTITY:Quaternion.axisAngle(1,0,0,Math.PI);
        levelFromViewWorld=tilt.multiply(view.inverse());
    }

    public Quaternion relative(Quaternion absolute) {
        return levelFromViewWorld.multiply(absolute.toView(demoAxes));
    }
}
