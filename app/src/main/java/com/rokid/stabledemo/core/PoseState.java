package com.rokid.stabledemo.core;

public final class PoseState {
    private PoseState() {}
    public static boolean isFresh(long sampleNs, long nowNs) {
        return sampleNs > 0 && nowNs >= sampleNs && nowNs-sampleNs <= 250_000_000L;
    }
}
