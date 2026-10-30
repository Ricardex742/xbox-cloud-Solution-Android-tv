package com.world.cloudxsolution;
import org.junit.Test;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;
public class PlaybackOptionsTest {
    @Test public void outputPreservesAspectDoesNotDownscaleAndRespectsCaps() {
        assertArrayEquals(new int[]{1280,720},VideoOutputPolicy.size(1280,720,3840,2160,0));
        assertArrayEquals(new int[]{1920,1080},VideoOutputPolicy.size(1280,720,3840,2160,1));
        assertArrayEquals(new int[]{3840,2160},VideoOutputPolicy.size(1920,1080,3840,2160,2));
        assertArrayEquals(new int[]{1440,1080},VideoOutputPolicy.size(640,480,3840,2160,1));
        assertArrayEquals(new int[]{2560,1440},VideoOutputPolicy.size(2560,1440,1920,1080,1));
    }
    @Test public void triggerPacketsCannotBeMistakenForMenuInEitherTransport() {
        GamepadFrame legacy=new GamepadFrame(0); legacy.axes[GamepadFrame.AXIS_RIGHT_TRIGGER]=1;
        ByteBuffer packet=new InputPacket().forFrame(1,legacy,1920,1080);
        byte[] bytes=new byte[packet.remaining()];packet.get(bytes);
        assertEquals(0,InputTrace.menuValue("input",bytes));
        legacy.buttons=GamepadFrame.MENU;packet=new InputPacket().forFrame(2,legacy,1920,1080);packet.get(bytes);
        assertEquals(1,InputTrace.menuValue("input",bytes));
        GamepadTransitionFrame modern=new GamepadTransitionFrame(0);modern.axes[GamepadTransitionFrame.AXIS_RIGHT_TRIGGER]=1;
        packet=UnreliableInputPacket.forChangedGamepads(1,1,modern);bytes=new byte[packet.remaining()];packet.get(bytes);
        assertEquals(0,InputTrace.menuValue("unreliableinput",bytes));
        modern.bumpButton(GamepadTransitionFrame.INDEX_MENU);packet=UnreliableInputPacket.forChangedGamepads(2,2,modern);packet.get(bytes);
        assertEquals(1,InputTrace.menuValue("unreliableinput",bytes));
    }
    @Test public void traceIsOptInBoundedAndExpires() {
        InputTrace trace=new InputTrace();trace.add(1,"not recorded");assertEquals("",trace.report());
        trace.enable(100);for(int i=0;i<200;i++)trace.add(100+i,"test");
        assertEquals(160,trace.report().split("\n").length);assertFalse(trace.enabled(600100));
        String before=trace.report();trace.add(600101,"expired");assertEquals(before,trace.report());
        assertEquals(-1,InputTrace.menuValue("input",new byte[]{2}));
    }
    @Test public void truncatedRumbleCannotOverrunAndCombinedAckUsesElevenBytes() {
        for(int n=2;n<13;n++) {byte[] data=new byte[n];data[0]=(byte)128;InputMessageParser.parse(data,null,(a,b,c,d,e,f,g,h)->fail("truncated"),null);}
        byte[] data=new byte[17];ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0,(short)(128|1024));data[12]=3;b.putInt(13,123456);
        List<Integer> values=new ArrayList<>();
        InputMessageParser.parse(data,null,(idx,l,r,lt,rt,duration,delay,repeat)->values.add(repeat),values::add);
        assertEquals(Arrays.asList(3,123456),values);
    }
}
