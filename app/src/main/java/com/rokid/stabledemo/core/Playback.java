package com.rokid.stabledemo.core;

/** Pure elapsed-time state: switching comparison modes cannot restart playback. */
public final class Playback {
    private final String text;
    private final long intervalMs, durationMs;
    private final int count;
    public Playback(String text, long intervalMs, long holdMs) {
        if (text.isEmpty() || intervalMs <= 0 || holdMs < 0) throw new IllegalArgumentException("Invalid playback");
        this.text=text; this.intervalMs=intervalMs;
        count=text.codePointCount(0,text.length());
        durationMs=count*intervalMs+holdMs;
    }
    public Frame at(long elapsedMs) {
        long elapsed=Math.max(0,elapsedMs), local=elapsed%durationMs;
        int visible=(int)Math.min(count,local/intervalMs);
        return new Frame(text.substring(0,text.offsetByCodePoints(0,visible)), visible,
                elapsed/durationMs+1, visible == count);
    }
    public static final class Frame {
        public final String text;
        public final int visibleCodepoints;
        public final long round;
        public final boolean complete;
        Frame(String text, int visibleCodepoints, long round, boolean complete) {
            this.text=text; this.visibleCodepoints=visibleCodepoints; this.round=round; this.complete=complete;
        }
    }
}
