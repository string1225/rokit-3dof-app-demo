package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoreTest {
    @Test public void inverseCancelsArbitraryOrientation() {
        Quaternion q = Quaternion.axisAngle(1, 2, 3, 1.1);
        double[] p = q.inverse().rotate(q.rotate(new double[]{2, -3, 4}));
        assertArrayEquals(new double[]{2, -3, 4}, p, 1e-8);
    }
    @Test public void rightHandedDemoBasisMapsAllThreeAxes() {
        assertArrayEquals(new double[]{1, 3, -2}, Quaternion.demoVector(1, 2, 3), 1e-8);
        Quaternion q = Quaternion.axisAngle(0, 0, 1, .2).toView(true);
        assertArrayEquals(Quaternion.axisAngle(0, 1, 0, .2).rotate(new double[]{0, 0, -1}),
                q.rotate(new double[]{0, 0, -1}), 1e-8);
    }
    @Test public void yawCompensationMovesOppositeCamera() {
        Projection p = new Projection(480, 480, 30);
        double[] result = p.point(240, 240, Quaternion.axisAngle(0, 1, 0, Math.toRadians(10)));
        assertNotNull(result);
        assertTrue(result[0] > 240);
        assertEquals(240, result[1], 1e-6);
    }
    @Test public void pitchCompensationMovesDownForPositivePitch() {
        double[] result = new Projection(480, 480, 30).point(240, 240,
                Quaternion.axisAngle(1, 0, 0, Math.toRadians(8)));
        assertNotNull(result);
        assertTrue(result[1] > 240);
    }
    @Test public void rollUsesFullInverseRotation() {
        double[] result = new Projection(480, 480, 30).point(300, 240,
                Quaternion.axisAngle(0, 0, 1, Math.PI / 2));
        assertArrayEquals(new double[]{240, 300}, result, 1e-6);
    }
    @Test public void startingReferenceDoesNotChangeRelativePose() {
        Quaternion reference = Quaternion.axisAngle(1, 2, 0, .8);
        Quaternion delta = Quaternion.axisAngle(0, 0, 1, .3);
        Quaternion relative = reference.inverse().multiply(reference.multiply(delta));
        assertArrayEquals(delta.rotate(new double[]{0, 0, -1}),
                relative.rotate(new double[]{0, 0, -1}), 1e-8);
    }
    @Test public void rearPlaneIsCulledRatherThanPinnedToEdge() {
        assertNull(new Projection(480, 480, 30).point(240, 240,
                Quaternion.axisAngle(0, 1, 0, Math.PI)));
    }
    @Test public void offscreenProjectionIsNotClamped() {
        assertTrue(new Projection(480, 480, 30).point(240, 240,
                Quaternion.axisAngle(0, 1, 0, Math.toRadians(40)))[0] > 480);
    }
    @Test public void invalidQuaternionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Quaternion(Double.NaN, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Quaternion(0, 0, 0, 0));
    }
    @Test public void unicodeTypingAndHoldLoop() {
        Playback p = new Playback("中😀文", 90, 2400);
        assertEquals("", p.at(0).text);
        assertEquals("中", p.at(90).text);
        assertEquals("中😀", p.at(180).text);
        assertEquals("中😀文", p.at(270).text);
        assertEquals("中😀文", p.at(2669).text);
        assertEquals("", p.at(2670).text);
        assertEquals(2, p.at(2670).round);
        assertEquals("", p.at(-100).text);
    }
    @Test public void poseFreshnessRejectsGapsAndFutureTimestamps() {
        assertTrue(PoseState.isFresh(1_000_000_000L, 1_050_000_000L));
        assertFalse(PoseState.isFresh(0, 1_050_000_000L));
        assertFalse(PoseState.isFresh(1_000_000_000L, 2_000_000_000L));
        assertFalse(PoseState.isFresh(2_000_000_000L, 1_000_000_000L));
    }
    @Test public void rawSensorsAllowSmallCrossSensorTimestampSkew() {
        assertTrue(ImuTiming.canPair(1_003_000_000L,1_000_000_000L));
        assertTrue(ImuTiming.canPair(997_000_000L,1_000_000_000L));
        assertFalse(ImuTiming.canPair(1_100_000_000L,1_000_000_000L));
        assertFalse(ImuTiming.canPair(0,1_000_000_000L));
    }
    @Test public void integrationGapUsesLastIntegratedSample() {
        assertFalse(ImuTiming.hasGap(1_000_000_000L,1_005_000_000L));
        assertTrue(ImuTiming.hasGap(1_000_000_000L,1_200_000_000L));
        assertTrue(ImuTiming.hasGap(1_000_000_000L,999_000_000L));
    }
}
