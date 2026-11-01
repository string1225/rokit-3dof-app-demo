package com.rokid.stabledemo.power;

/** Signed, device-wide first/last observations; distinct from integrated energy. */
final class BatteryDelta {
    private Double firstLevel;
    private Double lastLevel;
    private Integer firstCharge;
    private Integer lastCharge;
    private Long firstLevelElapsed;
    private Long lastLevelElapsed;
    private Long firstChargeElapsed;
    private Long lastChargeElapsed;
    private Boolean chargingObserved;
    private Boolean pluggedObserved;
    private int samples;
    private int dischargingSamples;
    private int unknownPowerStateSamples;
    private int levelCount;
    private int chargeCount;

    void sample(long elapsed, Double level, Integer charge, Boolean charging, Boolean plugged, Boolean discharging) {
        samples++;
        if (Boolean.TRUE.equals(discharging)) dischargingSamples++;
        if (discharging == null) unknownPowerStateSamples++;
        if (charging != null) chargingObserved = Boolean.TRUE.equals(chargingObserved) || charging;
        if (plugged != null) pluggedObserved = Boolean.TRUE.equals(pluggedObserved) || plugged;
        if (level != null && Double.isFinite(level) && level >= 0 && level <= 100) {
            if (firstLevel == null) { firstLevel = level; firstLevelElapsed = elapsed; }
            lastLevel = level;
            lastLevelElapsed = elapsed;
            levelCount++;
        }
        if (charge != null && charge >= 0) {
            if (firstCharge == null) { firstCharge = charge; firstChargeElapsed = elapsed; }
            lastCharge = charge;
            lastChargeElapsed = elapsed;
            chargeCount++;
        }
    }
    Double firstLevel() { return firstLevel; }
    Double lastLevel() { return lastLevel; }
    Integer firstCharge() { return firstCharge; }
    Integer lastCharge() { return lastCharge; }
    Long firstLevelElapsed() { return firstLevelElapsed; }
    Long lastLevelElapsed() { return lastLevelElapsed; }
    Long firstChargeElapsed() { return firstChargeElapsed; }
    Long lastChargeElapsed() { return lastChargeElapsed; }
    Double levelDrop() {
        return levelCount >= 2 && lastLevelElapsed > firstLevelElapsed ? firstLevel - lastLevel : null;
    }
    Double chargeDropMah() {
        return chargeCount >= 2 && lastChargeElapsed > firstChargeElapsed
                ? ((long) firstCharge - lastCharge) / 1000.0 : null;
    }
    Double dischargingLevelDrop() { return Boolean.TRUE.equals(allSamplesDischarging()) ? levelDrop() : null; }
    Double dischargingChargeDropMah() { return Boolean.TRUE.equals(allSamplesDischarging()) ? chargeDropMah() : null; }
    Boolean chargingObserved() { return chargingObserved; }
    Boolean pluggedObserved() { return pluggedObserved; }
    Boolean allSamplesDischarging() { return samples == 0 ? null : samples == dischargingSamples; }
    int levelCount() { return levelCount; }
    int chargeCount() { return chargeCount; }
    int unknownPowerStateSamples() { return unknownPowerStateSamples; }
}
