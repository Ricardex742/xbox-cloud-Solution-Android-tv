package com.world.cloudxsolution;

import java.util.Locale;

/** A conservative preset, not an assertion about the TV chipset or installed ABI. */
public final class TvCompatibilityProfile {
    public static final String RESOLUTION = "720";
    public static final int BITRATE_MBPS = 8;
    private TvCompatibilityProfile() {}

    public static boolean isTl042(String model) {
        if (model == null) return false;
        return model.toUpperCase(Locale.ROOT).matches(".*\\bTL[ _-]?042\\b.*");
    }
}
