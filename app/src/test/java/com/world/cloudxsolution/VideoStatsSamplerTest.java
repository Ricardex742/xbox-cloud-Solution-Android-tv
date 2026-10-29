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

    private Map<String, Object> sample(double received, double decoded, double decodeSeconds,
                                        double bufferSeconds, double emitted) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("framesReceived", received);
        stats.put("framesDecoded", decoded);
        stats.put("totalDecodeTime", decodeSeconds);
        stats.put("jitterBufferDelay", bufferSeconds);
        stats.put("jitterBufferEmittedCount", emitted);
        stats.put("framesDropped", received - decoded);
        stats.put("packetsLost", 100);
        return stats;
    }

    @Test public void separatesSixtyReceivedFromThirtyDecodedWithIntervalTimings() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> codec = new HashMap<>();
        sampler.sample("v", 1000000, sample(100, 100, 1, 1, 100), codec);
        Map<String, Object> next = sample(340, 220, 4.6, 10.6, 340);
        next.put("framesPerSecond", 99); // Must never be mislabeled as received FPS.
        next.put("jitter", 0.008);
        String report = sampler.sample("v", 5000000, next, codec);
        assertTrue(report.contains("FPS recebidos: 60.0 | Decodificados: 30.0"));
        assertTrue(report.contains("Decodificação: 30.00 ms/quadro | Buffer: 40.0 ms"));
        assertTrue(report.contains("Descartes WebRTC: 120 | Pacotes perdidos: 0 (intervalo)"));
        assertTrue(report.contains("Jitter da rede: 8.0 ms"));
    }

    @Test public void counterResetsAndStreamChangesNeverProduceNegativeRates() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> codec = new HashMap<>();
        sampler.sample("v", 1000000, sample(100, 100, 1, 1, 100), codec);
        String reset = sampler.sample("v", 2000000, sample(10, 10, 0.1, 0.1, 10), codec);
        assertTrue(reset.contains("FPS recebidos: n/d | Decodificados: n/d"));
        assertTrue(reset.contains("Decodificação: n/d ms/quadro | Buffer: n/d ms"));
        String changed = sampler.sample("new", 3000000, sample(100, 100, 1, 1, 100), codec);
        assertTrue(changed.contains("FPS recebidos: n/d | Decodificados: n/d"));
    }

    @Test public void stalledDecoderReportsZeroFpsWithoutDividingByZero() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> codec = new HashMap<>();
        sampler.sample("v", 1000000, sample(100, 100, 1, 1, 100), codec);
        String report = sampler.sample("v", 2000000, sample(160, 100, 1, 1, 100), codec);
        assertTrue(report.contains("FPS recebidos: 60.0 | Decodificados: 0.0"));
        assertTrue(report.contains("Decodificação: n/d ms/quadro | Buffer: n/d ms"));
        assertFalse(report.contains("Infinity"));
        assertFalse(report.contains("NaN"));
    }

    @Test public void instantDecodeFpsIsNotSubstitutedForMissingReceiveCounters() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> stats = new HashMap<>();
        stats.put("framesPerSecond", 34);
        sampler.sample("v", 1000000, stats, stats);
        assertTrue(sampler.sample("v", 2000000, stats, stats)
                .contains("FPS recebidos: n/d | Decodificados: n/d"));
    }

    @Test public void duplicateTimestampDoesNotCreateFalseFpsOrBitrate() {
        VideoStatsSampler sampler = new VideoStatsSampler();
        Map<String, Object> stats = sample(100, 100, 1, 1, 100);
        sampler.sample("v", 1000000, stats, stats);
        assertTrue(sampler.sample("v", 1000000, stats, stats).contains("FPS recebidos: n/d"));
    }

    @Test public void iceRttAndRelayProtocolAreReportedWithoutAddresses() {
        Map<String, Object> pair = new HashMap<>();
        pair.put("currentRoundTripTime", 0.025);
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("protocol", "udp");
        candidate.put("relayProtocol", "tcp");
        candidate.put("address", "private-address");
        String report = VideoStatsSampler.networkSummary(pair, candidate);
        assertEquals("RTT ICE: 25.0 ms | ICE: udp / relay: tcp", report);
        assertFalse(report.contains("private-address"));
        pair.put("currentRoundTripTime", Double.NaN);
        assertTrue(VideoStatsSampler.networkSummary(pair, candidate).contains("RTT ICE: n/d"));
    }
}
