package com.rokid.stabledemo.power;

import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryEnergyTest {
    @Test public void integratesDeviceDischargeUsingTrapezoidalPower() {
        BatteryEnergy energy = new BatteryEnergy();
        energy.sample(0, -100_000, 4000, true);
        energy.sample(10_000, -300_000, 4000, true);
        assertEquals(8.0, energy.joules(), 1e-9);
        assertEquals(10_000L, energy.coveredMillis());
    }

    @Test public void chargingAndUnsupportedValuesBreakContinuity() {
        BatteryEnergy energy = new BatteryEnergy();
        energy.sample(0, -100_000, 4000, true);
        energy.sample(5000, -100_000, 4000, false);
        energy.sample(10_000, -100_000, 4000, true);
        energy.sample(15_000, Integer.MIN_VALUE, 4000, true);
        energy.sample(20_000, -100_000, 4000, true);
        energy.sample(25_000, -100_000, Integer.MIN_VALUE, true);
        energy.sample(30_000, -100_000, 4000, true);
        assertEquals(0, energy.coveredMillis());
        assertEquals(0.0, energy.joules(), 0.0);
    }

    @Test public void positiveAndZeroCurrentAreNotAssumedToMeanDischarging() {
        BatteryEnergy energy = new BatteryEnergy();
        energy.sample(0, 100_000, 4000, true);
        energy.sample(5000, 0, 4000, true);
        energy.sample(10_000, -100_000, 4000, true);
        assertEquals(0, energy.coveredMillis());
    }

    @Test public void gapsAndNonMonotonicTimeDoNotFabricateEnergy() {
        BatteryEnergy energy = new BatteryEnergy();
        energy.sample(0, -100_000, 4000, true);
        energy.sample(60_000, -100_000, 4000, true);
        energy.sample(59_000, -100_000, 4000, true);
        assertEquals(0, energy.coveredMillis());
        energy.sample(64_000, -100_000, 4000, true);
        assertEquals(0, energy.coveredMillis());
        energy.sample(69_000, -100_000, 4000, true);
        assertEquals(2.0, energy.joules(), 1e-9);
    }

    @Test public void boundaryPreventsAttributingOtherWorkloadsToStage() {
        BatteryEnergy energy = new BatteryEnergy();
        energy.sample(0, -100_000, 4000, true);
        energy.breakContinuity();
        energy.sample(5000, -100_000, 4000, true);
        assertEquals(0, energy.coveredMillis());
    }
}
