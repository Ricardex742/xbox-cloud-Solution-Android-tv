package com.world.cloudxsolution;
import org.junit.Test;
import java.nio.*;
import static org.junit.Assert.*;
public class InputProtocolVersionTest {
    @Test public void sdkLegacyHeadersMatchVersionOneThroughTen() {
        InputPacket writer=new InputPacket();GamepadFrame f=new GamepadFrame(0);f.buttons=GamepadFrame.A;
        for(int v=1;v<=10;v++) {
            writer.setVersion(v);ByteBuffer b=writer.forFrame(0x12345678,f,1920,1080).order(ByteOrder.LITTLE_ENDIAN);
            int header=v>=8?14:v>=7?13:5;
            assertEquals(header+1+(v>=2?23:15),b.remaining());
            assertEquals(2,v>=8?b.getShort(0):b.get(0));
            assertEquals(0x12345678,b.getInt(v>=8?2:1));
            assertEquals(1,b.get(header));assertEquals(0,b.get(header+1));assertEquals(16,b.getShort(header+2));
            byte[] bytes=new byte[b.remaining()];b.get(bytes);assertEquals(0,InputTrace.menuValue("input",bytes,v));
        }
    }
    @Test public void sdkModernNineOmitsLockKeysByteAndTenIncludesIt() {
        GamepadTransitionFrame f=new GamepadTransitionFrame(0);f.bumpButton(GamepadTransitionFrame.INDEX_A);
        ByteBuffer nine=UnreliableInputPacket.forChangedGamepads(1,2,f,9),ten=UnreliableInputPacket.forChangedGamepads(1,2,f,10);
        assertEquals(60,nine.remaining());assertEquals(61,ten.remaining());
        assertEquals(1,nine.get(32));assertEquals(1,ten.get(32)); // A at frame offset 20 + 12
        assertEquals(0,nine.get(59));assertEquals(0,ten.get(60));
        assertThrows(IllegalArgumentException.class,()->UnreliableInputPacket.forChangedGamepads(1,2,f,8));
    }
    @Test public void oldServerMetadataUsesSingleByteReportFlags() {
        ByteBuffer b=ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)16).putInt(720).putInt(1280);
        InputMessageParser.parse(b.array(),7,(w,h)->{assertEquals(1280,w);assertEquals(720,h);},null,null);
    }
    @Test public void stereoIsCommittedBeforeLocalDescriptionWithoutChangingVideoOrRtx() {
        String audio="m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;stereo=0;useinbandfec=1\r\n";
        String video="m=video 9 UDP/TLS/RTP/SAVPF 98 99\r\na=rtpmap:98 H264/90000\r\na=fmtp:98 profile-level-id=64001f\r\na=fmtp:99 apt=98\r\n";
        String result=SdpPolicy.stereoAudio(audio+video);
        assertTrue(result.contains("stereo=1"));assertFalse(result.contains("stereo=0"));assertTrue(result.endsWith(video));
        assertEquals(result,SdpPolicy.stereoAudio(result));
    }
}
