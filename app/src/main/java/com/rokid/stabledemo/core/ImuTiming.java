package com.rokid.stabledemo.core;

public final class ImuTiming {
    private ImuTiming() {}
    /** Different sensor queues may deliver a slightly newer acceleration first. */
    public static boolean canPair(long accelNs,long gyroNs) {
        return accelNs > 0 && gyroNs > 0 && Math.abs(accelNs-gyroNs) <= 50_000_000L;
    }
    /** Missing integration time cannot be recovered by silently applying one fresh rate sample. */
    public static boolean hasGap(long integratedNs,long nextNs) {
        return integratedNs > 0 && (nextNs <= integratedNs || nextNs-integratedNs > 100_000_000L);
    }
}
