package com.rokid.stabledemo.power;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhaseScheduleTest {
    @Test public void defaultHasExactlyFiveSequentialFiveMinuteStages() {
        PhaseSchedule schedule = new PhaseSchedule(300_000);
        assertEquals(5, schedule.count());
        assertEquals(1_500_000L, schedule.totalDurationMs());
        String[] expected = {"display", "microphone", "speaker", "video", "photos"};
        for (int i = 0; i < 5; i++) {
            assertEquals(expected[i], schedule.id(i));
            assertEquals(i * 300_000L, schedule.startOffsetMs(i));
            assertEquals(300_000L, schedule.durationMs());
        }
    }

    @Test public void integrationOverrideIsReportedWithoutChangingPhotoCadence() {
        PhaseSchedule schedule = new PhaseSchedule(12_000);
        assertEquals(60_000L, schedule.totalDurationMs());
        assertEquals(12_000L, schedule.durationMs());
        assertEquals(10_000L, PhaseSchedule.PHOTO_INTERVAL_MS);
    }

    @Test public void rejectsInvalidOrOverflowingDuration() {
        assertThrows(IllegalArgumentException.class, () -> new PhaseSchedule(0));
        assertThrows(IllegalArgumentException.class, () -> new PhaseSchedule(-1));
        assertThrows(IllegalArgumentException.class, () -> new PhaseSchedule(Long.MAX_VALUE));
    }
}
