package com.world.cloudxsolution;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputRegressionTest {
    @Test public void leftStickRejectsDriftAndPreservesDiagonalDirection() {
        float[] out = new float[2];
        InputMath.response(.1f, 0f, InputMath.leftDeadzone(.12f), 1f, 1f, out);
        assertArrayEquals(new float[] {0, 0}, out, 0f);
        InputMath.response(.4f, .4f, .15f, 1f, 1f, out);
        assertTrue(out[0] > 0);
        assertEquals(out[0], out[1], 0f);
    }
    @Test public void cameraRetainsSmallMotionAndNeutral() {
        float[] out = new float[2];
        InputMath.response(.0009f, 0f, 0f, 1f, 1f, out);
        assertEquals(.0009f, out[0], .0000001f);
        InputMath.response(0f, 0f, 0f, 1f, 1f, out);
        assertArrayEquals(new float[] {0, 0}, out, 0f);
    }
    @Test public void invalidSettingsCannotCreateNaNOrDivideByZero() {
        float[] out = new float[2];
        InputMath.response(Float.NaN, Float.POSITIVE_INFINITY, 1f, Float.NaN, Float.NaN, out);
        assertArrayEquals(new float[] {0, 0}, out, 0f);
        assertEquals(.95f, InputMath.leftDeadzone(1f), 0f);
        assertEquals(.15f, InputMath.leftDeadzone(Float.NaN), 0f);
    }
    @Test public void packetPreservesSmallCameraMotionAndFinalZero() {
        GamepadTransitionFrame f = new GamepadTransitionFrame(0);
        f.setRightStick(.0009f, .0009f);
        ByteBuffer packet = UnreliableInputPacket.forChangedGamepads(1, 1, f);
        assertEquals(61, packet.remaining());
        assertEquals(29, packet.getShort(44));
        assertEquals(-29, packet.getShort(46));
        f.setRightStick(0, 0);
        packet = UnreliableInputPacket.forChangedGamepads(2, 2, f);
        assertEquals(0, packet.getShort(44));
        assertEquals(0, packet.getShort(46));
    }
    @Test public void releaseAllKeepsTransitionCountersAndClearsHeldFlags() {
        GamepadTransitionFrame f = new GamepadTransitionFrame(0);
        f.bumpButton(GamepadTransitionFrame.INDEX_A);
        f.resetAllHeldButtons();
        assertFalse(f.held[GamepadTransitionFrame.INDEX_A]);
        assertEquals(2, f.transitions[GamepadTransitionFrame.INDEX_A]);
        f.resetAllHeldButtons();
        assertEquals(2, f.transitions[GamepadTransitionFrame.INDEX_A]);
    }
}
