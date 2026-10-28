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
        if (idc == 0x64) return preferHigh && hardwareSupported ? (constraints == 0 ? 11 : 10) : 2;
        if (baseline) return 6;
        return idc == 0x4d ? 3 : 0;
    }

    public static boolean offersH264High(String sdp) {
        return h264Profiles(sdp).stream().anyMatch(profile -> profile.startsWith("64"));
    }

    /** Only active video payloads; never expose ICE credentials or the full SDP in telemetry. */
    public static java.util.Set<String> h264Profiles(String sdp) {
        java.util.Set<String> profiles = new java.util.LinkedHashSet<>();
        if (sdp == null) return profiles;
        for (String section : sdp.split("(?m)(?=^m=)")) {
            String[] lines = section.split("\\r?\\n");
            if (lines.length == 0 || !lines[0].startsWith("m=video ")) continue;
            String[] media = lines[0].split(" +");
            if (media.length < 4 || "0".equals(media[1])) continue;
            java.util.Set<String> payloads = new java.util.HashSet<>(java.util.Arrays.asList(media).subList(3, media.length));
            java.util.Set<String> h264 = new java.util.HashSet<>();
            for (String line : lines) {
                if (!line.startsWith("a=rtpmap:")) continue;
                String[] mapping = line.substring(9).split(" +", 2);
                if (mapping.length == 2 && payloads.contains(mapping[0]) && mapping[1].equalsIgnoreCase("H264/90000")) h264.add(mapping[0]);
            }
            for (String line : lines) {
                if (!line.startsWith("a=fmtp:")) continue;
                String[] fmtp = line.substring(7).split(" +", 2);
                if (fmtp.length != 2 || !h264.contains(fmtp[0])) continue;
                for (String parameter : fmtp[1].split(";")) {
                    String[] pair = parameter.trim().split("=", 2);
                    if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("profile-level-id")
                            && pair[1].trim().matches("(?i)[0-9a-f]{6}")) {
                        profiles.add(pair[1].trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        return profiles;
    }
}
