package com.world.cloudxsolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.webrtc.RtpCapabilities;

/** Filter capabilities, never relabel payloads or invent a profile/level. */
final class CodecSelectionPolicy {
    static List<RtpCapabilities.CodecCapability> select(List<RtpCapabilities.CodecCapability> codecs, int mode, boolean hardwareHigh) {
        if (mode == 2) return new ArrayList<>(codecs);
        List<RtpCapabilities.CodecCapability> h264 = new ArrayList<>(), other = new ArrayList<>();
        for (RtpCapabilities.CodecCapability c : codecs) {
            if ("H264".equalsIgnoreCase(c.name)) h264.add(c); else other.add(c);
        }
        boolean preferHigh = mode == 1 || mode == 3;
        h264.sort((a,b) -> Integer.compare(
                VideoQualityPolicy.h264Rank(b.parameters.get("profile-level-id"), preferHigh, hardwareHigh),
                VideoQualityPolicy.h264Rank(a.parameters.get("profile-level-id"), preferHigh, hardwareHigh)));
        if (mode == 3) {
            if (!hardwareHigh) throw new IllegalArgumentException("High hardware unavailable");
            h264.removeIf(c -> !VideoQualityPolicy.isHighProfile(c.parameters.get("profile-level-id")));
            if (h264.isEmpty()) throw new IllegalArgumentException("High receive capability unavailable");
            Set<String> payloads = new HashSet<>();
            for (RtpCapabilities.CodecCapability c : h264) payloads.add(String.valueOf(c.preferredPayloadType));
            other.removeIf(c -> !"rtx".equalsIgnoreCase(c.name)
                    || (c.parameters.containsKey("apt") && !payloads.contains(c.parameters.get("apt"))));
        }
        h264.addAll(other);
        return h264;
    }
    static boolean acceptsHighAnswer(String sdp) {
        Set<String> profiles = VideoQualityPolicy.h264Profiles(sdp);
        return !profiles.isEmpty() && profiles.stream().allMatch(VideoQualityPolicy::isHighProfile);
    }
}
