package com.world.cloudxsolution;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class VideoStatsSamplerTest {
    @Test public void measuresIntervalBitrateAndReportsActualDimensions() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> stats = new HashMap<>();
        stats.put("frameWidth", 1280);
        stats.put("frameHeight", 720);
        stats.put("bytesReceived", 1000000L);
        Map<String, Object> codec = new HashMap<>();
        codec.put("mimeType", "video/H264");
        codec.put("sdpFmtpLine", "packetization-mode=1;profile-level-id=640c1f");
        assertTrue(sampler.sample("video1", 1000000, stats, codec).contains("medindo..."));
        stats.put("bytesReceived", 6000000L);
        String report = sampler.sample("video1", 3000000, stats, codec);
        assertTrue(report.contains("1280x720 | 20.0 Mbps"));
        assertTrue(report.contains("video/H264 [640c1f]"));
        assertTrue(sampler.sample("video2", 4000000, stats, codec).contains("medindo..."));
        sampler.reset();
        assertTrue(sampler.sample("video2", 5000000, stats, codec).contains("medindo..."));
    }

    @Test public void toleratesMissingOrResetCountersWithoutInventingMeasurements() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> stats = new HashMap<>();
        assertTrue(sampler.sample("v", 1, stats, stats).contains("n/dxn/d | medindo..."));
        stats.put("bytesReceived", 10L);
        assertTrue(sampler.sample("v", 2, stats, stats).contains("medindo..."));
        stats.put("bytesReceived", 1L);
        assertTrue(sampler.sample("v", 3, stats, stats).contains("medindo..."));
    }
}
