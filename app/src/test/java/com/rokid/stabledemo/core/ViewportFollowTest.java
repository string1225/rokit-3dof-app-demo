package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ViewportFollowTest {
    private static final long START=1_000_000_000L;
    private static final Projection VIEW=new Projection(480,480,30);

    @Test public void eitherAxisAndEitherSignSnapInOneFrame() {
        for(Quaternion head:new Quaternion[]{yaw(16),yaw(-16),pitch(16),pitch(-16)}) {
            LevelFollow follow=started();
            LevelFollow.Result result=follow.update(head,START+16_000_000L,VIEW);
            assertTrue(result.snapped);
            assertCentered(result);
        }
    }

    @Test public void offsetsAtOrBelowHalfTheViewportKeepSlowFollow() {
        for(Quaternion head:new Quaternion[]{yaw(14),yaw(-14),pitch(14),pitch(-14),
                yaw(15),yaw(-15),pitch(15),pitch(-15)}) {
            LevelFollow.Result result=started().update(head,START+16_000_000L,VIEW);
            double[] center=VIEW.point(240,240,result.compensatedHead);
            assertFalse("The boundary itself is not beyond 50%",result.snapped);
            assertTrue("In-range compensation must remain, followed by gradual recentering",
                    Math.max(Math.abs(center[0]-240),Math.abs(center[1]-240))>210);
        }
    }

    @Test public void thresholdIsCheckedBeforeTheSlowAnchorCanPullItBackInside() {
        LevelFollow.Result result=started().update(yaw(15.01),START+16_000_000L,VIEW);
        assertTrue("A just-crossed boundary must snap immediately",result.snapped);
        assertCentered(result);
    }

    @Test public void diagonalMotionInsideBothAxisBoundsDoesNotUseARadialThreshold() {
        LevelFollow.Result result=started().update(yaw(11.5).multiply(pitch(11.5)),START+16_000_000L,VIEW);
        assertFalse(result.snapped);
        double[] center=VIEW.point(240,240,result.compensatedHead);
        assertTrue(Math.hypot(center[0]-240,center[1]-240)>240);
        assertTrue(Math.abs(center[0]-240)<240 && Math.abs(center[1]-240)<240);
    }

    @Test public void headRollRotatesTheOffsetIntoTheActualScreenAxes() {
        Quaternion head=yaw(11.5).multiply(pitch(11.5))
                .multiply(Quaternion.axisAngle(0,0,1,Math.PI/4));
        LevelFollow.Result result=started().update(head,START+16_000_000L,VIEW);
        assertTrue("A screen-axis excursion must snap even if each Euler angle is small",result.snapped);
        assertCentered(result);
    }

    @Test public void snappingBothPositionAxesPreservesRollAndDoesNotRepeatWhileStill() {
        Quaternion head=yaw(25).multiply(pitch(-7)).multiply(Quaternion.axisAngle(0,0,1,Math.toRadians(25)));
        LevelFollow follow=started();
        LevelFollow.Result first=follow.update(head,START+16_000_000L,VIEW);
        assertTrue(first.snapped); assertCentered(first);
        assertTrue("Roll compensation must survive the jump",VIEW.point(300,240,first.compensatedHead)[1]>260);
        assertEquals(0,head.multiply(first.compensatedHead.inverse()).rotate(new double[]{1,0,0})[1],1e-8);
        LevelFollow.Result next=follow.update(head,START+32_000_000L,VIEW);
        assertFalse(next.snapped); assertCentered(next);
    }

    @Test public void activeFovAndViewportHeightDetermineTheThreshold() {
        LevelFollow.Result wide=started().update(yaw(20),START+16_000_000L,new Projection(480,480,60));
        assertFalse(wide.snapped);
        assertTrue(started().update(yaw(20),START+16_000_000L,VIEW).snapped);
        assertTrue(started().update(pitch(13),START+16_000_000L,new Projection(480,320,30)).snapped);
        assertFalse(started().update(pitch(13),START+16_000_000L,VIEW).snapped);
    }

    @Test public void rearFacingPlaneAndLongFrameGapCannotStrandThePanelOutside() {
        LevelFollow.Result rear=started().update(yaw(180),START+16_000_000L,VIEW);
        assertTrue(rear.snapped); assertCentered(rear);
        LevelFollow.Result gap=started().update(yaw(20),START+5_000_000_000L,VIEW);
        assertTrue(gap.snapped); assertCentered(gap);
    }

    @Test public void laterExcursionsAreMeasuredFromTheNewAnchor() {
        LevelFollow follow=started();
        assertTrue(follow.update(yaw(20),START+16_000_000L,VIEW).snapped);
        assertTrue(follow.update(yaw(40),START+32_000_000L,VIEW).snapped);
        LevelFollow.Result still=follow.update(yaw(40),START+48_000_000L,VIEW);
        assertFalse(still.snapped); assertCentered(still);
    }

    private static LevelFollow started() {
        LevelFollow follow=new LevelFollow(1.5,12); follow.update(Quaternion.IDENTITY,START,VIEW); return follow;
    }
    private static void assertCentered(LevelFollow.Result result) {
        assertArrayEquals(new double[]{240,240},VIEW.point(240,240,result.compensatedHead),1e-7);
    }
    private static Quaternion yaw(double degrees) { return Quaternion.axisAngle(0,1,0,Math.toRadians(degrees)); }
    private static Quaternion pitch(double degrees) { return Quaternion.axisAngle(1,0,0,Math.toRadians(degrees)); }
}
