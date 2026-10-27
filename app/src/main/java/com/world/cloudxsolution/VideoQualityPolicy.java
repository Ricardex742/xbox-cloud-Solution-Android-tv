package com.world.cloudxsolution;

/** Pure negotiation policy; advertised profiles and levels are never rewritten. */
public final class VideoQualityPolicy {
    private VideoQualityPolicy() {}

    public static int maxKbps(int requested) {
        return requested <= 0 ? 20000 : Math.max(1000, Math.min(20000, requested));
    }

    public static int startKbps(int requested) {
        return Math.min(10000, maxKbps(requested) / 2);
    }

    public static int h264Rank(String profile, boolean preferHigh, boolean hardwareSupported) {
        if (profile == null || !profile.matches("(?i)[0-9a-f]{6}")) return 0;
        int idc = Integer.parseInt(profile.substring(0, 2), 16);
        int constraints = Integer.parseInt(profile.substring(2, 4), 16);
        boolean baseline = idc == 0x42 || (idc == 0x4d && (constraints & 0x80) != 0)
                || (idc == 0x58 && (constraints & 0xc0) == 0xc0);
        if (idc == 0x64) return preferHigh && hardwareSupported ? 10 : 2;
        if (baseline) return 6;
        return idc == 0x4d ? 3 : 0;
    }
}
