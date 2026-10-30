package com.world.cloudxsolution;
import org.junit.Test;
import org.webrtc.RtpCapabilities.CodecCapability;
import java.util.*;
import static org.junit.Assert.*;
public class CodecSelectionPolicyTest {
    private CodecCapability codec(String name, int pt, String key, String value) {
        CodecCapability c = new CodecCapability(); c.name=name; c.preferredPayloadType=pt; c.parameters=new HashMap<>();
        if (key!=null) c.parameters.put(key,value); return c;
    }
    @Test public void exclusiveHighRetainsOnlyMatchingRtxAndOriginalParameters() {
        CodecCapability baseline=codec("H264",96,"profile-level-id","42e01f"), high=codec("H264",98,"profile-level-id","640c1f");
        CodecCapability oldRtx=codec("rtx",97,"apt","96"), highRtx=codec("rtx",99,"apt","98"), vp9=codec("VP9",100,null,null);
        List<CodecCapability> original=Arrays.asList(baseline,oldRtx,high,highRtx,vp9);
        assertEquals(Arrays.asList(high,highRtx),CodecSelectionPolicy.select(original,3,true));
        assertEquals(5,original.size()); assertEquals("640c1f",high.parameters.get("profile-level-id"));
        assertEquals(original,CodecSelectionPolicy.select(original,2,true));
    }
    @Test public void preferenceAllowsFallbackAndStrictModeRequiresBothCapabilities() {
        CodecCapability b=codec("H264",96,"profile-level-id","42e01f"),h=codec("H264",98,"profile-level-id","64001f");
        assertSame(h,CodecSelectionPolicy.select(Arrays.asList(b,h),1,true).get(0));
        assertSame(b,CodecSelectionPolicy.select(Arrays.asList(b,h),0,true).get(0));
        assertThrows(IllegalArgumentException.class,()->CodecSelectionPolicy.select(Arrays.asList(b,h),3,false));
        assertThrows(IllegalArgumentException.class,()->CodecSelectionPolicy.select(Arrays.asList(b),3,true));
    }
    @Test public void baselineAnswerCannotMasqueradeAsHighAndAudioDoesNotCount() {
        String video="m=video 9 UDP/TLS/RTP/SAVPF 98\r\na=rtpmap:98 H264/90000\r\na=fmtp:98 profile-level-id=";
        assertFalse(CodecSelectionPolicy.acceptsHighAnswer(video+"42e02a\r\n"));
        assertTrue(CodecSelectionPolicy.acceptsHighAnswer(video+"64002a\r\n"));
        assertFalse(CodecSelectionPolicy.acceptsHighAnswer("m=audio 9 UDP/TLS/RTP/SAVPF 98\r\na=rtpmap:98 H264/90000\r\na=fmtp:98 profile-level-id=64002a\r\n"));
        assertFalse(CodecSelectionPolicy.acceptsHighAnswer(video.replace("video 9","video 0")+"64002a\r\n"));
    }
}
