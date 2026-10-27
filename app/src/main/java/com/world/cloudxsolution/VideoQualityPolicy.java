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

    public static boolean offersH264High(String sdp) {
        boolean video = false;
        java.util.Set<String> h264 = new java.util.HashSet<>();
        // Native offers put rtpmap before fmtp for each payload.
        for (String line : sdp.split("\\r?\\n")) {
            if (line.startsWith("m=")) {
                video = line.startsWith("m=video ") && !line.startsWith("m=video 0 ");
                h264.clear();
            }
            if (!video) continue;
            if (line.startsWith("a=rtpmap:")) {
                String[] mapping = line.substring(9).split(" +", 2);
                if (mapping.length == 2 && mapping[1].equalsIgnoreCase("H264/90000")) h264.add(mapping[0]);
            }
            if (line.startsWith("a=fmtp:")) {
                String[] fmtp = line.substring(7).split(" +", 2);
                if (fmtp.length != 2 || !h264.contains(fmtp[0])) continue;
                for (String parameter : fmtp[1].split(";")) {
                    String[] pair = parameter.trim().split("=", 2);
                    if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("profile-level-id")
                            && pair[1].trim().matches("(?i)64[0-9a-f]{4}")) return true;
                }
            }
        }
        return false;
    }
}
