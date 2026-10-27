package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoQualityPolicyTest {
    @Test public void baselineRemainsDefaultAndHighRequiresHardware() {
        int baseline = VideoQualityPolicy.h264Rank("42e01f", false, true);
        assertTrue(baseline > VideoQualityPolicy.h264Rank("640c1f", false, true));
        assertTrue(baseline > VideoQualityPolicy.h264Rank("640c1f", true, false));
        assertTrue(VideoQualityPolicy.h264Rank("640c1f", true, true) > baseline);
        assertTrue(VideoQualityPolicy.h264Rank("64001f", true, true) > baseline);
    }

    @Test public void acceptsConstrainedBaselineAliasesAndRejectsMalformedProfiles() {
        assertEquals(6, VideoQualityPolicy.h264Rank("4DE01F", false, true));
        assertEquals(6, VideoQualityPolicy.h264Rank("58c01f", false, true));
        assertEquals(0, VideoQualityPolicy.h264Rank(null, true, true));
        assertEquals(0, VideoQualityPolicy.h264Rank("6400xx", true, true));
    }

    @Test public void boundedStartupLeavesHeadroomForCongestionControl() {
        assertEquals(20000, VideoQualityPolicy.maxKbps(0));
        assertEquals(20000, VideoQualityPolicy.maxKbps(Integer.MAX_VALUE));
        assertEquals(1000, VideoQualityPolicy.maxKbps(1));
        assertEquals(10000, VideoQualityPolicy.startKbps(20000));
        assertEquals(7500, VideoQualityPolicy.startKbps(15000));
        assertEquals(4000, VideoQualityPolicy.startKbps(8000));
    }
}
