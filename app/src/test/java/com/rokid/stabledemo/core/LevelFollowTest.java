package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LevelFollowTest {
    private static final long START=1_000_000_000L;
    private static final Projection PROJECTION=new Projection(480,480,30);

    @Test public void turnIsCompensatedImmediatelyAndReturnIsGradual() {
        LevelFollow follow=new LevelFollow(1.5,12);
        follow.update(Quaternion.IDENTITY,START);
        Quaternion head=yaw(10);
        LevelFollow.Result first=follow.update(head,START+16_000_000L);
        double offset=PROJECTION.point(240,240,first.compensatedHead)[0]-240;
        assertTrue("The fast path must still compensate the head turn",offset>140);
        assertTrue("Slow anchor must not teleport to head direction",Math.abs(first.anchor.y)<.003);
        double previous=offset;
        for(int i=2;i<=400;i++) {
            LevelFollow.Result result=follow.update(head,START+i*16_000_000L);
            double now=PROJECTION.point(240,240,result.compensatedHead)[0]-240;
            assertTrue("Return to center must be monotonic",now<=previous+1e-8);
            assertTrue(now>=-1e-8); previous=now;
        }
        assertTrue("After settling the plane should be near eye center",previous<3);
    }
    @Test public void headRollNeverBecomesPartOfSlowFollow() {
        LevelFollow follow=new LevelFollow(1.5,12);
        follow.update(Quaternion.IDENTITY,START);
        Quaternion roll=Quaternion.axisAngle(0,0,1,Math.toRadians(25));
        LevelFollow.Result result=null;
        for(int i=1;i<=2000;i++) result=follow.update(roll,START+i*20_000_000L);
        assertNotNull(result);
        assertArrayEquals(new double[]{240,240},PROJECTION.point(240,240,result.compensatedHead),1e-7);
        assertTrue("Roll compensation must remain active after long hold",PROJECTION.point(300,240,result.compensatedHead)[1]>260);
        assertArrayEquals(new double[]{1,0,0},result.anchor.rotate(new double[]{1,0,0}),1e-8);
    }
    @Test public void combinedYawPitchAndRollReturnToCenterWhileKeepingWorldHorizontal() {
        LevelFollow follow=new LevelFollow(1.5,12);
        follow.update(Quaternion.IDENTITY,START);
        Quaternion head=yaw(20).multiply(pitch(12)).multiply(Quaternion.axisAngle(0,0,1,Math.toRadians(18)));
        LevelFollow.Result result=null;
        for(int i=1;i<=750;i++) {
            result=follow.update(head,START+i*20_000_000L);
            assertEquals("Anchor must keep a horizontal right vector",0,result.anchor.rotate(new double[]{1,0,0})[1],1e-8);
        }
        assertArrayEquals(new double[]{240,240},PROJECTION.point(240,240,result.compensatedHead),.1);
        Quaternion shownInWorld=head.multiply(result.compensatedHead.inverse());
        assertEquals(0,shownInWorld.rotate(new double[]{1,0,0})[1],1e-8);
    }
    @Test public void frameRateDoesNotChangeFollowTimeConstant() {
        Quaternion a=settleAtRate(30),b=settleAtRate(120);
        assertArrayEquals(a.rotate(new double[]{0,0,-1}),b.rotate(new double[]{0,0,-1}),1e-6);
    }
    @Test public void headingWrapTakesTheShortRoute() {
        LevelFollow follow=new LevelFollow(1.5,12);
        follow.recenter(yaw(179),START);
        LevelFollow.Result result=follow.update(yaw(-179),START+20_000_000L);
        double[] center=PROJECTION.point(240,240,result.compensatedHead);
        assertNotNull(center); assertTrue(Math.abs(center[0]-240)<40);
        double[] right=result.anchor.rotate(new double[]{1,0,0});
        assertTrue(right[0]<-.99);
    }
    @Test public void manualRecenterKeepsRollCompensation() {
        Quaternion head=yaw(15).multiply(pitch(-8)).multiply(Quaternion.axisAngle(0,0,1,.4));
        LevelFollow follow=new LevelFollow(1.5,12); follow.recenter(head,START);
        LevelFollow.Result result=follow.update(head,START+20_000_000L);
        assertArrayEquals(new double[]{240,240},PROJECTION.point(240,240,result.compensatedHead),1e-7);
        assertTrue(PROJECTION.point(300,240,result.compensatedHead)[1]>260);
    }
    @Test public void longFrameGapDoesNotCauseAnchorSnap() {
        LevelFollow follow=new LevelFollow(1.5,12); follow.update(Quaternion.IDENTITY,START);
        LevelFollow.Result result=follow.update(yaw(30),START+5_000_000_000L);
        assertArrayEquals(new double[]{1,0,0},result.anchor.rotate(new double[]{1,0,0}),1e-8);
    }
    @Test public void slewLimitBoundsDiagonalReturnSpeed() {
        LevelFollow follow=new LevelFollow(1.5,12); follow.update(Quaternion.IDENTITY,START);
        LevelFollow.Result result=follow.update(yaw(60).multiply(pitch(40)),START+20_000_000L);
        double cosine=Math.abs(result.anchor.w);
        assertTrue(Math.toDegrees(2*Math.acos(Math.min(1,cosine)))<=.241);
    }
    @Test public void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new LevelFollow(Double.NaN,12));
        assertThrows(IllegalArgumentException.class,() -> new LevelFollow(1.5,0));
    }
    private static Quaternion settleAtRate(int fps) {
        LevelFollow follow=new LevelFollow(1.5,12); follow.update(Quaternion.IDENTITY,START);
        LevelFollow.Result result=null;
        for(int i=1;i<=fps*3;i++) result=follow.update(yaw(10),START+(long)(i*1e9/fps));
        return result.anchor;
    }
    private static Quaternion yaw(double degrees) { return Quaternion.axisAngle(0,1,0,Math.toRadians(degrees)); }
    private static Quaternion pitch(double degrees) { return Quaternion.axisAngle(1,0,0,Math.toRadians(degrees)); }
}
