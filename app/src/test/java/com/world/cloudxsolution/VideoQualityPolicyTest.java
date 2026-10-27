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

    @Test public void detectsHighInTheActualVideoOfferWithoutConfusingAudioOrRtx() {
        String header = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97\r\na=rtpmap:96 H264/90000\r\na=rtpmap:97 rtx/90000\r\n";
        assertTrue(VideoQualityPolicy.offersH264High(header + "a=fmtp:96 packetization-mode=1;profile-level-id=640c1f\r\n"));
        assertFalse(VideoQualityPolicy.offersH264High(header + "a=fmtp:96 profile-level-id=42e01f\r\na=fmtp:97 profile-level-id=640c1f\r\n"));
        assertFalse(VideoQualityPolicy.offersH264High("m=audio 9 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=640c1f\r\n"));
        assertFalse(VideoQualityPolicy.offersH264High(header.replace("m=video 9", "m=video 0") + "a=fmtp:96 profile-level-id=640c1f\r\n"));
    }
}
