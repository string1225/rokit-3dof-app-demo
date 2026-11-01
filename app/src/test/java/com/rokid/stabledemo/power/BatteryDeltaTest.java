package com.rokid.stabledemo.power;

import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryDeltaTest {
    @Test public void reportsSignedObservedDropsAndDischargeQualification() {
        BatteryDelta delta = new BatteryDelta();
        delta.sample(0, 60.0, 2_000_000, false, false, true);
        delta.sample(5000, 59.5, 1_995_000, false, false, true);
        assertEquals(Double.valueOf(60.0), delta.firstLevel());
        assertEquals(Double.valueOf(59.5), delta.lastLevel());
        assertEquals(Double.valueOf(0.5), delta.levelDrop());
        assertEquals(Double.valueOf(5.0), delta.chargeDropMah());
        assertEquals(Boolean.TRUE, delta.allSamplesDischarging());
        assertEquals(Boolean.FALSE, delta.chargingObserved());
        assertEquals(Double.valueOf(5.0), delta.dischargingChargeDropMah());
    }

    @Test public void chargingIncreaseRemainsNegativeAndIsNeverConsumption() {
        BatteryDelta delta = new BatteryDelta();
        delta.sample(0, 60.0, 2_000_000, true, true, false);
        delta.sample(5000, 61.0, 2_010_000, true, true, false);
        assertEquals(Double.valueOf(-1.0), delta.levelDrop());
        assertEquals(Double.valueOf(-10.0), delta.chargeDropMah());
        assertEquals(Boolean.TRUE, delta.chargingObserved());
        assertEquals(Boolean.TRUE, delta.pluggedObserved());
        assertNull(delta.dischargingLevelDrop());
        assertNull(delta.dischargingChargeDropMah());
    }

    @Test public void missingReadingsAndUnknownChargingStateStayNull() {
        BatteryDelta delta = new BatteryDelta();
        delta.sample(0, null, null, null, null, null);
        delta.sample(5000, null, null, null, null, null);
        assertNull(delta.firstLevel());
        assertNull(delta.lastCharge());
        assertNull(delta.levelDrop());
        assertNull(delta.chargeDropMah());
        assertNull(delta.chargingObserved());
        assertNull(delta.pluggedObserved());
        assertEquals(Boolean.FALSE, delta.allSamplesDischarging());
    }

    @Test public void onlyOneObservedValueDoesNotInventZeroConsumption() {
        BatteryDelta delta = new BatteryDelta();
        delta.sample(0, 60.0, 2_000_000, false, false, true);
        delta.sample(5000, null, null, false, false, true);
        assertEquals(Double.valueOf(60.0), delta.firstLevel());
        assertEquals(Double.valueOf(60.0), delta.lastLevel());
        assertEquals(Long.valueOf(0), delta.lastLevelElapsed());
        assertNull(delta.levelDrop());
        assertNull(delta.chargeDropMah());
    }

    @Test public void oneChargingOrUnknownSampleDisqualifiesConsumptionForWholeWindow() {
        BatteryDelta delta = new BatteryDelta();
        delta.sample(0, 60.0, 2_000_000, false, false, true);
        delta.sample(2500, null, null, null, null, null);
        delta.sample(5000, 59.0, 1_995_000, false, false, true);
        assertEquals(Double.valueOf(1.0), delta.levelDrop());
        assertEquals(Boolean.FALSE, delta.allSamplesDischarging());
        assertNull(delta.dischargingChargeDropMah());
    }

    @Test public void independentStageTrackersCannotJoinDifferentWorkloadEndpoints() {
        BatteryDelta first = new BatteryDelta();
        BatteryDelta second = new BatteryDelta();
        first.sample(0, 60.0, 2_000_000, false, false, true);
        first.sample(5000, 59.0, 1_995_000, false, false, true);
        second.sample(6000, 58.0, 1_980_000, false, false, true);
        second.sample(11_000, 57.5, 1_978_000, false, false, true);
        assertEquals(Double.valueOf(1.0), first.levelDrop());
        assertEquals(Double.valueOf(0.5), second.levelDrop());
        assertEquals(Double.valueOf(2.0), second.chargeDropMah());
    }
}
