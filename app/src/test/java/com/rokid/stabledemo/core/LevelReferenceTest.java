package com.rokid.stabledemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LevelReferenceTest {
    private static final double[] UP={0,0,1},RIGHT={1,0,0};
    private static final Quaternion UPRIGHT=Quaternion.axisAngle(1,0,0,Math.PI/2);

    @Test public void startupTiltIsRetainedAsCompensationInsteadOfBecomingLevel() {
        for(double roll:new double[]{-3,-.5,.5,3}) {
            Quaternion absolute=UPRIGHT.multiply(Quaternion.axisAngle(0,0,1,Math.toRadians(roll)));
            Quaternion relative=new LevelReference(absolute,false).relative(absolute);
            assertArrayEquals(Quaternion.axisAngle(0,0,1,Math.toRadians(roll)).rotate(RIGHT),
                    relative.rotate(RIGHT),1e-8);
        }
    }

    @Test public void combinedMotionKeepsThePanelPhysicallyHorizontal() {
        Quaternion startup=pose(15,-10,-2);
        LevelReference reference=new LevelReference(startup,false);
        LevelFollow follow=new LevelFollow(1.5,12);
        long now=1_000_000_000L;
        follow.recenter(reference.relative(startup),now);
        for(int i=0;i<500;i++) {
            Quaternion absolute=pose(15+i*.04,-10+8*Math.sin(i*.02),-2+6*Math.sin(i*.03));
            LevelFollow.Result result=follow.update(reference.relative(absolute),now+i*20_000_000L);
            Quaternion panelInWorld=absolute.multiply(result.compensatedHead.inverse());
            assertEquals("The panel's right vector must be perpendicular to world gravity",
                    0,panelInWorld.rotate(RIGHT)[2],1e-8);
        }
    }

    @Test public void recenterAtTiltedStartupCentersPositionWithoutAbsorbingRoll() {
        Quaternion absolute=pose(23,-12,-2);
        Quaternion relative=new LevelReference(absolute,false).relative(absolute);
        LevelFollow follow=new LevelFollow(1.5,12);
        follow.recenter(relative,1_000_000_000L);
        LevelFollow.Result result=follow.update(relative,1_016_000_000L);
        assertArrayEquals(new double[]{240,240},
                new Projection(480,480,30).point(240,240,result.compensatedHead),1e-7);
        assertEquals(0,absolute.multiply(result.compensatedHead.inverse()).rotate(RIGHT)[2],1e-8);
    }

    @Test public void globalHeadingDoesNotChangeCompensationOrRequireMagneticNorth() {
        Quaternion startup=pose(-15,8,3),later=pose(12,-6,-4);
        Quaternion globalYaw=Quaternion.axisAngle(0,0,1,1.7);
        Quaternion a=new LevelReference(startup,false).relative(later);
        Quaternion b=new LevelReference(globalYaw.multiply(startup),false).relative(globalYaw.multiply(later));
        assertArrayEquals(a.rotate(RIGHT),b.rotate(RIGHT),1e-8);
        assertArrayEquals(a.rotate(new double[]{0,0,-1}),b.rotate(new double[]{0,0,-1}),1e-8);
    }

    @Test public void legacyDemoBasisAlsoKeepsGravityVertical() {
        Quaternion startup=Quaternion.axisAngle(1,2,3,.6);
        Quaternion later=startup.multiply(Quaternion.axisAngle(2,1,-3,.4));
        LevelReference reference=new LevelReference(startup,true);
        Quaternion relative=reference.relative(later);
        double[] localUp=later.inverse().rotate(UP);
        assertArrayEquals(new double[]{0,1,0},relative.rotate(
                Quaternion.demoVector(localUp[0],localUp[1],localUp[2])),1e-8);
    }

    @Test public void verticalAndInvertedPosesRemainFiniteAndGravityAligned() {
        for(Quaternion absolute:new Quaternion[]{Quaternion.IDENTITY,UPRIGHT,
                Quaternion.axisAngle(1,0,0,-Math.PI/2),Quaternion.axisAngle(1,0,0,Math.PI),
                Quaternion.axisAngle(1,0,0,-Math.PI/2+1e-7)}) {
            Quaternion relative=new LevelReference(absolute,false).relative(absolute);
            assertArrayEquals(new double[]{0,1,0},relative.rotate(absolute.inverse().rotate(UP)),1e-8);
        }
    }

    private static Quaternion pose(double yaw,double pitch,double roll) {
        return Quaternion.axisAngle(0,0,1,.7).multiply(UPRIGHT)
                .multiply(Quaternion.axisAngle(0,1,0,Math.toRadians(yaw)))
                .multiply(Quaternion.axisAngle(1,0,0,Math.toRadians(pitch)))
                .multiply(Quaternion.axisAngle(0,0,1,Math.toRadians(roll)));
    }
}
