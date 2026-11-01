package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AxisProfileTest {
    @Test public void unspecifiedProfileUsesObservedAndroidAxes() {
        assertFalse(AxisProfile.usesLegacyDemo(null));
        assertFalse(AxisProfile.usesLegacyDemo("android"));
        assertTrue(AxisProfile.usesLegacyDemo("demo"));
    }
    @Test public void physicalYawMovesPlaneHorizontallyWithoutRoll() {
        Quaternion yaw=Quaternion.axisAngle(0,1,0,.1).toView(AxisProfile.usesLegacyDemo(null));
        double[] center=new Projection(480,480,30).point(240,240,yaw);
        assertTrue(center[0]>240);
        assertEquals(240,center[1],1e-8);
        assertEquals(0,yaw.z,1e-8);
    }
    @Test public void physicalRollRotatesWithoutTranslatingCenter() {
        Quaternion roll=Quaternion.axisAngle(0,0,1,.1).toView(AxisProfile.usesLegacyDemo(null));
        Projection projection=new Projection(480,480,30);
        assertArrayEquals(new double[]{240,240},projection.point(240,240,roll),1e-8);
        assertTrue(projection.point(300,240,roll)[1]>240);
    }
}
