package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputSessionStateTest {
    @Test public void idleControllerIsReadyRegardlessOfChannelOpeningOrder() {
        for (boolean controlFirst : new boolean[]{false, true}) {
            InputSessionState state = new InputSessionState();
            state.channel(controlFirst ? "control" : "unreliableinput", "OPEN");
            assertFalse(state.canAnnounce());
            state.channel(controlFirst ? "unreliableinput" : "control", "OPEN");
            // No physical key or axis event has occurred.
            assertTrue(state.canAnnounce());
            assertEquals("unreliableinput", state.activeChannel());
        }
    }

    @Test public void preferenceCannotRouteInputIntoAClosedChannel() {
        InputSessionState state = new InputSessionState();
        state.channel("input", "OPEN");
        assertEquals("input", state.activeChannel());
        state.channel("unreliableinput", "OPEN");
        assertEquals("unreliableinput", state.activeChannel());
        state.preferModern(false);
        assertEquals("input", state.activeChannel());
        state.channel("input", "CLOSING");
        assertEquals("unreliableinput", state.activeChannel());
        state.channel("unreliableinput", "CLOSED");
        assertEquals("", state.activeChannel());
    }

    @Test public void secondSessionMustWaitForItsOwnChannels() {
        InputSessionState state = new InputSessionState();
        state.channel("control", "OPEN");
        state.channel("input", "OPEN");
        assertTrue(state.canAnnounce());
        state.reset();
        assertFalse(state.canAnnounce());
        state.channel("input", "OPEN");
        assertFalse(state.canAnnounce());
        state.channel("control", "OPEN");
        assertTrue(state.canAnnounce());
    }
}
