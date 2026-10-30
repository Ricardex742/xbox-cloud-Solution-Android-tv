package com.world.cloudxsolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Apply receive bandwidth hints before setLocalDescription, preserving real codec capabilities. */
public final class SdpPolicy {
    private SdpPolicy() {}

    public static String stereoAudio(String sdp) {
        StringBuilder out = new StringBuilder();
        java.util.Set<String> opus = new java.util.HashSet<>();
        boolean audio = false;
        for (String line : sdp.split("\\r?\\n")) {
            if (line.startsWith("m=")) { audio = line.startsWith("m=audio ") && !line.startsWith("m=audio 0 "); opus.clear(); }
            if (audio && line.matches("(?i)a=rtpmap:[0-9]+ opus/48000/2")) opus.add(line.substring(9).split(" ")[0]);
            if (audio && line.startsWith("a=fmtp:")) {
                String[] parts = line.substring(7).split(" +",2);
                if (opus.contains(parts[0])) {
                    java.util.List<String> params = new java.util.ArrayList<>();
                    if (parts.length == 2) for (String param : parts[1].split(";"))
                        if (!param.trim().toLowerCase(java.util.Locale.ROOT).startsWith("stereo=")) params.add(param.trim());
                    params.add("stereo=1"); line = "a=fmtp:" + parts[0] + " " + String.join(";",params);
                }
            }
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    public static String videoBandwidth(String sdp, int requestedKbps) {
        StringBuilder result = new StringBuilder();
        List<String> section = new ArrayList<>();
        for (String line : sdp.split("\\r?\\n")) {
            if (line.startsWith("m=") && !section.isEmpty()) {
                appendSection(result, section, requestedKbps);
                section.clear();
            }
            section.add(line);
        }
        appendSection(result, section, requestedKbps);
        return result.toString();
    }

    private static void appendSection(StringBuilder out, List<String> lines, int requested) {
        if (lines.isEmpty()) return;
        String[] media = lines.get(0).split(" +");
        boolean video = media.length > 1 && media[0].equals("m=video") && !media[1].equals("0");
        Set<String> h264 = new HashSet<>();
        Set<String> configured = new HashSet<>();
        if (video) {
            for (String line : lines) {
                if (line.startsWith("a=rtpmap:")) {
                    String[] mapping = line.substring(9).split(" +", 2);
                    if (mapping.length == 2 && mapping[1].toUpperCase(Locale.ROOT).startsWith("H264/")) {
                        h264.add(mapping[0]);
                    }
                }
            }
        }
        boolean hasConnection = lines.stream().anyMatch(line -> line.startsWith("c="));
        for (String line : lines) {
            if (video && (line.startsWith("b=AS:") || line.startsWith("b=TIAS:"))) continue;
            if (video && line.startsWith("a=fmtp:")) {
                String[] fmtp = line.substring(7).split(" +", 2);
                if (h264.contains(fmtp[0])) {
                    List<String> params = new ArrayList<>();
                    if (fmtp.length == 2) {
                        for (String param : fmtp[1].split(";")) {
                            String trimmed = param.trim();
                            String key = trimmed.split("=", 2)[0].trim().toLowerCase(Locale.ROOT);
                            if (!trimmed.isEmpty() && !key.equals("x-google-min-bitrate")
                                    && !key.equals("x-google-start-bitrate") && !key.equals("x-google-max-bitrate")) {
                                params.add(trimmed);
                            }
                        }
                    }
                    params.add(hints(requested));
                    line = "a=fmtp:" + fmtp[0] + " " + String.join(";", params);
                    configured.add(fmtp[0]);
                }
            }
            out.append(line).append("\r\n");
            if (video && (line.startsWith("c=") || (!hasConnection && line.startsWith("m=")))) {
                int cap = VideoQualityPolicy.maxKbps(requested);
                out.append("b=AS:").append(cap).append("\r\nb=TIAS:").append(cap * 1000).append("\r\n");
            }
        }
        if (video) for (String line : lines) {
            if (!line.startsWith("a=rtpmap:")) continue;
            String payload = line.substring(9).split(" +", 2)[0];
            if (h264.contains(payload) && configured.add(payload)) {
                out.append("a=fmtp:").append(payload).append(' ').append(hints(requested)).append("\r\n");
            }
        }
    }

    private static String hints(int requested) {
        // Best-effort encoder hints. No minimum: congestion control may reduce bitrate.
        return "x-google-start-bitrate=" + VideoQualityPolicy.startKbps(requested)
                + ";x-google-max-bitrate=" + VideoQualityPolicy.maxKbps(requested);
    }
}
