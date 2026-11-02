package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FollowDeadZoneTest {
    private static final long START=1_000_000_000L;
    private static final Projection CONTENT=new Projection(480,480,30,80,312);
    private static final double FOCAL=480/(2*Math.tan(Math.toRadians(30)/2));

    @Test public void offsetsAtOrWithinTwentyPercentKeepAnchorFixedIndefinitely() {
        for(Quaternion head:new Quaternion[]{yawPixels(95),yawPixels(-95),pitchPixels(46),pitchPixels(-46),
                yawPixels(96),yawPixels(-96),pitchPixels(46.4),pitchPixels(-46.4)}) {
            LevelFollow follow=started();
            for(int i=1;i<=600;i++) {
                LevelFollow.Result result=follow.update(head,START+i*16_000_000L,CONTENT);
                assertFalse(result.snapped); assertIdentity(result.anchor);
            }
        }
    }

    @Test public void eitherAxisAndEitherSignStartsFollowingOnlyAboveTheThreshold() {
        for(Quaternion head:new Quaternion[]{yawPixels(96.01),yawPixels(-96.01),pitchPixels(46.41),pitchPixels(-46.41)}) {
            LevelFollow.Result result=started().update(head,START+16_000_000L,CONTENT);
            assertFalse(result.snapped);
            assertTrue("Exceeding 20% must move the anchor",rotationMagnitude(result.anchor)>1e-5);
        }
    }

    @Test public void followingStopsOnReenteringTheDeadZoneAndResumesOnAnotherExcursion() {
        LevelFollow follow=started(); Quaternion head=yawPixels(160);
        LevelFollow.Result result=null;
        for(int i=1;i<=500;i++) result=follow.update(head,START+i*16_000_000L,CONTENT);
        double[] center=CONTENT.point(240,196,result.compensatedHead);
        assertTrue(center[0]-240<=96 && center[0]-240>94);
        Quaternion stopped=result.anchor;
        for(int i=501;i<=600;i++) {
            result=follow.update(head,START+i*16_000_000L,CONTENT);
            assertSameRotation(stopped,result.anchor);
        }
        result=follow.update(yawPixels(210),START+601*16_000_000L,CONTENT);
        assertFalse(result.snapped);
        assertTrue(rotationMagnitude(stopped.inverse().multiply(result.anchor))>1e-5);
    }

    @Test public void subthresholdDiagonalUsesScreenAxisBoundsRatherThanARadius() {
        Quaternion head=yawPixels(85).multiply(pitchPixels(40));
        double[] center=CONTENT.point(240,196,head);
        assertTrue(Math.hypot((center[0]-240)/96,(center[1]-196)/46.4)>1);
        assertFalse(CONTENT.centerOffsetExceeds(head,.2));
        assertIdentity(started().update(head,START+16_000_000L,CONTENT).anchor);
        Quaternion rolled=head.multiply(Quaternion.axisAngle(0,0,1,Math.PI/2));
        assertTrue("Roll must rotate the displacement into actual screen axes",CONTENT.centerOffsetExceeds(rolled,.2));
        assertTrue(rotationMagnitude(started().update(rolled,START+16_000_000L,CONTENT).anchor)>1e-5);
    }

    @Test public void thresholdTracksActualFovAndContentHeight() {
        Quaternion head=yawPixels(100);
        assertTrue(CONTENT.centerOffsetExceeds(head,.2));
        assertFalse(new Projection(480,480,60,80,312).centerOffsetExceeds(head,.2));
        Quaternion vertical=pitchPixels(47);
        assertTrue(CONTENT.centerOffsetExceeds(vertical,.2));
        assertFalse(new Projection(480,480,30).centerOffsetExceeds(vertical,.2));
    }

    @Test public void timeSpentInsideTheDeadZoneCannotAccumulateIntoAJump() {
        LevelFollow follow=started();
        for(int i=1;i<=500;i++) follow.update(yawPixels(80),START+i*16_000_000L,CONTENT);
        LevelFollow.Result result=follow.update(yawPixels(120),START+501*16_000_000L,CONTENT);
        assertTrue("A long hold must still produce an ordinary bounded first follow step",rotationMagnitude(result.anchor)<Math.toRadians(.2));
        assertTrue(rotationMagnitude(result.anchor)>0);
    }

    private static LevelFollow started() {
        LevelFollow follow=new LevelFollow(1.5,12); follow.update(Quaternion.IDENTITY,START,CONTENT); return follow;
    }
    private static Quaternion yawPixels(double pixels) { return Quaternion.axisAngle(0,1,0,Math.atan(pixels/FOCAL)); }
    private static Quaternion pitchPixels(double pixels) { return Quaternion.axisAngle(1,0,0,Math.atan(pixels/FOCAL)); }
    private static double rotationMagnitude(Quaternion q) { return 2*Math.acos(Math.min(1,Math.abs(q.w))); }
    private static void assertIdentity(Quaternion q) { assertEquals(1,Math.abs(q.w),1e-12); }
    private static void assertSameRotation(Quaternion a,Quaternion b) {
        assertEquals(1,Math.abs(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w),1e-12);
    }
}
