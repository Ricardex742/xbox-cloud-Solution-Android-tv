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
}
