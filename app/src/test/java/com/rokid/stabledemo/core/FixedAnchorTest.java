package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixedAnchorTest {
    private static final Projection VIEW=new Projection(480,480,30,80,312);

    @Test public void disabledFollowKeepsAnchorDuringSmallLargeAndRearFacingTurns() {
        LevelFollow follow=new LevelFollow(1.5,12);
        Quaternion initial=yaw(7).multiply(Quaternion.axisAngle(1,0,0,.1));
        follow.recenter(initial,1_000_000_000L);
        Quaternion anchor=follow.update(initial,1_000_000_000L,VIEW,false).anchor;
        for(int i=1;i<=600;i++) {
            Quaternion head=yaw(i<200?10:i<400?70:180);
            LevelFollow.Result frame=follow.update(head,1_000_000_000L+i*16_000_000L,VIEW,false);
            assertFalse("Fixed mode must never recenter at the viewport boundary",frame.snapped);
            assertSameRotation(anchor,frame.anchor);
            assertSameRotation(head.multiply(frame.compensatedHead.inverse()),anchor);
        }
    }

    @Test public void manualRecenterChangesFrozenAnchorAndPreservesRoll() {
        LevelFollow follow=new LevelFollow(1.5,12);
        Quaternion head=yaw(35).multiply(Quaternion.axisAngle(0,0,1,.3));
        follow.update(head,1_000_000_000L,VIEW,false);
        follow.recenter(head,2_000_000_000L);
        LevelFollow.Result frame=follow.update(head,3_000_000_000L,VIEW,false);
        assertArrayEquals(new double[]{240,196},VIEW.point(240,196,frame.compensatedHead),1e-8);
        assertEquals(.3,2*Math.atan2(frame.compensatedHead.z,frame.compensatedHead.w),1e-8);
        assertSameRotation(frame.anchor,follow.update(yaw(-80),4_000_000_000L,VIEW,false).anchor);
    }

    @Test public void reenablingFollowRestoresBoundaryRecenter() {
        LevelFollow follow=new LevelFollow(1.5,12);
        follow.update(Quaternion.IDENTITY,1_000_000_000L,VIEW,false);
        assertFalse(follow.update(yaw(50),2_000_000_000L,VIEW,false).snapped);
        assertTrue(follow.update(yaw(50),2_016_000_000L,VIEW,true).snapped);
    }
    private static Quaternion yaw(double degrees) { return Quaternion.axisAngle(0,1,0,Math.toRadians(degrees)); }
    private static void assertSameRotation(Quaternion a,Quaternion b) {
        assertEquals(1,Math.abs(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w),1e-10);
    }
}
