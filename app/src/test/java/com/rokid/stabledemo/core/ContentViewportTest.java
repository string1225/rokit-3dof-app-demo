package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContentViewportTest {
    private static final long START=1_000_000_000L;
    private static final Projection CONTENT=new Projection(480,480,30,80,312);

    @Test public void parameterBarsDoNotCountTowardVerticalTravel() {
        assertArrayEquals(new double[]{240,196},CONTENT.point(240,196,Quaternion.IDENTITY),1e-8);
        for(double degrees:new double[]{-8,8}) {
            LevelFollow.Result result=started().update(pitch(degrees),START+16_000_000L,CONTENT);
            assertTrue(result.snapped); assertCentered(result);
            assertFalse(new LevelFollow(1.5,12).update(pitch(degrees),START,new Projection(480,480,30)).snapped);
        }
        for(double degrees:new double[]{-6,6})
            assertFalse(started().update(pitch(degrees),START+16_000_000L,CONTENT).snapped);
    }

    @Test public void narrowingContentHeightKeepsTheHorizontalScaleAndLimit() {
        Projection full=new Projection(480,480,30);
        assertEquals(full.point(240,240,yaw(10))[0],CONTENT.point(240,196,yaw(10))[0],1e-8);
        assertFalse(started().update(yaw(14),START+16_000_000L,CONTENT).snapped);
        assertTrue(started().update(yaw(16),START+16_000_000L,CONTENT).snapped);
    }

    @Test public void exactContentBoundaryStaysSlowButCrossingItSnaps() {
        double focal=480/(2*Math.tan(Math.toRadians(30)/2));
        double boundary=Math.toDegrees(Math.atan(116/focal));
        for(double sign:new double[]{-1,1}) {
            assertFalse(started().update(pitch(sign*boundary),START+16_000_000L,CONTENT).snapped);
            LevelFollow.Result result=started().update(pitch(sign*(boundary+.01)),START+16_000_000L,CONTENT);
            assertTrue(result.snapped); assertCentered(result);
        }
    }

    @Test public void recenterTargetsTheContentMidpointWhilePreservingRoll() {
        Quaternion head=yaw(20).multiply(pitch(8)).multiply(Quaternion.axisAngle(0,0,1,.4));
        LevelFollow.Result result=started().update(head,START+16_000_000L,CONTENT);
        assertTrue(result.snapped); assertCentered(result);
        assertEquals(0,head.multiply(result.compensatedHead.inverse()).rotate(new double[]{1,0,0})[1],1e-8);
        assertTrue(CONTENT.point(300,196,result.compensatedHead)[1]>216);
    }

    @Test public void invalidContentBoundsAreRejected() {
        for(double[] bounds:new double[][]{{-1,312},{80,481},{312,80},{80,80},{Double.NaN,312},{80,Double.POSITIVE_INFINITY}})
            assertThrows(IllegalArgumentException.class,() -> new Projection(480,480,30,bounds[0],bounds[1]));
    }

    private static LevelFollow started() {
        LevelFollow follow=new LevelFollow(1.5,12); follow.update(Quaternion.IDENTITY,START,CONTENT); return follow;
    }
    private static void assertCentered(LevelFollow.Result result) {
        assertArrayEquals(new double[]{240,196},CONTENT.point(240,196,result.compensatedHead),1e-7);
    }
    private static Quaternion yaw(double degrees) { return Quaternion.axisAngle(0,1,0,Math.toRadians(degrees)); }
    private static Quaternion pitch(double degrees) { return Quaternion.axisAngle(1,0,0,Math.toRadians(degrees)); }
}
