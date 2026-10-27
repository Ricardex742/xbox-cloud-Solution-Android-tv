package com.world.cloudxsolution;
import org.junit.Test;
import static org.junit.Assert.*;
public class SdpPolicyTest {
    @Test public void preservesProfilesAudioAndRtxAndIsIdempotent() {
        String input = "v=0\r\nm=audio 9 RTP/AVP 111\r\nc=IN IP4 0.0.0.0\r\nb=AS:64\r\n"
                + "a=fmtp:111 minptime=10\r\nm=video 9 RTP/AVP 96 97\r\nc=IN IP4 0.0.0.0\r\n"
                + "b=AS:50000\r\na=fmtp:96 profile-level-id=42e02a\r\na=fmtp:97 apt=96\r\n";
        String result = SdpPolicy.videoBandwidth(input, 20000);
        assertTrue(result.contains("b=AS:64\r\n"));
        assertTrue(result.contains("b=AS:20000\r\n"));
        assertFalse(result.contains("50000"));
        assertTrue(result.contains("a=fmtp:96 profile-level-id=42e02a\r\n"));
        assertTrue(result.contains("a=fmtp:97 apt=96\r\n"));
        assertEquals(result, SdpPolicy.videoBandwidth(result, 20000));
        assertEquals(result, SdpPolicy.videoBandwidth(input, 0));
    }

    @Test public void onlyH264GetsHintsAndNoMinimumIsForced() {
        String audio = "m=audio 9 RTP/AVP 111\r\nc=IN IP4 0.0.0.0\r\n"
                + "a=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n";
        String input = "v=0\r\n" + audio + "m=video 9 RTP/AVP 96 97 98\r\nc=IN IP4 0.0.0.0\r\n"
                + "b=TIAS:50000000\r\na=rtpmap:96 H264/90000\r\na=rtpmap:97 rtx/90000\r\n"
                + "a=rtpmap:98 VP8/90000\r\na=fmtp:96 profile-level-id=42e01f;packetization-mode=1;"
                + "level-asymmetry-allowed=1;x-google-min-bitrate=15000;x-google-start-bitrate=20000\r\n"
                + "a=fmtp:97 apt=96\r\n";
        String result = SdpPolicy.videoBandwidth(input, 20000);
        assertTrue(result.contains(audio));
        assertTrue(result.contains("b=TIAS:20000000\r\n"));
        assertTrue(result.contains("profile-level-id=42e01f;packetization-mode=1;level-asymmetry-allowed=1;"
                + "x-google-start-bitrate=10000;x-google-max-bitrate=20000\r\n"));
        assertFalse(result.contains("x-google-min-bitrate"));
        assertTrue(result.contains("a=fmtp:97 apt=96\r\n"));
        assertFalse(result.contains("a=fmtp:98"));
        assertEquals(result, SdpPolicy.videoBandwidth(result, 20000));
    }

    @Test public void handlesInheritedConnectionAndAbsentFmtpAndLeavesRejectedVideoAlone() {
        String rejected = "m=video 0 RTP/AVP 98\r\na=rtpmap:98 H264/90000\r\na=fmtp:98 profile-level-id=640c1f\r\n";
        String input = "v=0\r\nc=IN IP4 0.0.0.0\r\nm=video 9 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
                + rejected + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=sctp-port:5000\r\n";
        String result = SdpPolicy.videoBandwidth(input, 8000);
        assertTrue(result.contains("m=video 9 RTP/AVP 96\r\nb=AS:8000\r\nb=TIAS:8000000\r\n"));
        assertTrue(result.contains("a=fmtp:96 x-google-start-bitrate=4000;x-google-max-bitrate=8000\r\n"));
        assertTrue(result.contains(rejected));
        assertTrue(result.endsWith("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=sctp-port:5000\r\n"));
        assertEquals(result, SdpPolicy.videoBandwidth(result, 8000));
    }
}
