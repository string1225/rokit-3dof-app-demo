package com.rokid.stabledemo.core;

/** The glasses tested by the user expose Android screen axes: pitch X, yaw Y, roll Z. */
public final class AxisProfile {
    private AxisProfile() {}
    public static boolean usesLegacyDemo(String name) { return "demo".equals(name); }
}
