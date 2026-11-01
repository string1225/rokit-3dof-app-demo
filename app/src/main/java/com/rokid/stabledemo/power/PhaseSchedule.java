package com.rokid.stabledemo.power;

final class PhaseSchedule {
    static final long PHOTO_INTERVAL_MS = 10_000;
    private static final String[] IDS = {"display", "microphone", "speaker", "video", "photos"};
    private static final String[] LABELS = {"Display only", "Display + microphone", "Display + sound",
            "Display + silent video", "Display + photos / 10 s"};
    private final long duration;
    PhaseSchedule(long duration) {
        if (duration <= 0 || duration > Long.MAX_VALUE / IDS.length)
            throw new IllegalArgumentException("Invalid stage duration");
        this.duration = duration;
    }
    int count() { return IDS.length; }
    String id(int index) { return IDS[index]; }
    String label(int index) { return LABELS[index]; }
    long durationMs() { return duration; }
    long totalDurationMs() { return duration * count(); }
    long startOffsetMs(int index) {
        if (index < 0 || index >= count()) throw new IndexOutOfBoundsException();
        return index * duration;
    }
}
