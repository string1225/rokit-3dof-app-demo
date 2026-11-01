package com.rokid.stabledemo.power;

/** Integrates device-wide battery discharge only; never an app power meter. */
final class BatteryEnergy {
    private boolean previousValid;
    private long previousMillis;
    private double previousWatts;
    private double joules;
    private long coveredMillis;

    void sample(long millis, int currentUa, int voltageMv, boolean discharging) {
        boolean valid = discharging && currentUa < 0 && currentUa != Integer.MIN_VALUE
                && voltageMv >= 1000 && voltageMv <= 20_000;
        if (!valid) { breakContinuity(); return; }
        double watts = -(double) currentUa * voltageMv / 1_000_000_000.0;
        long delta = millis - previousMillis;
        if (previousValid && delta <= 0) { breakContinuity(); return; }
        // A delayed sampler must not extrapolate across a suspended or overloaded process.
        if (previousValid && delta > 0 && delta <= 15_000) {
            joules += (previousWatts + watts) * 0.5 * delta / 1000.0;
            coveredMillis += delta;
        }
        previousValid = true;
        previousMillis = millis;
        previousWatts = watts;
    }
    void breakContinuity() { previousValid = false; }
    double joules() { return joules; }
    long coveredMillis() { return coveredMillis; }
}
