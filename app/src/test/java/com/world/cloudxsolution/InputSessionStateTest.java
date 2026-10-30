package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputSessionStateTest {
    @Test public void idleRegistrationNeedsBothChannelsAndAuthorizationInAnyOrder() {
        int[][] orders = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        for (int[] order : orders) {
            InputSessionState state = new InputSessionState();
            for (int n = 0; n < 3; n++) {
                if (order[n] == 0) state.channel("control", "OPEN");
                else if (order[n] == 1) state.channel("unreliableinput", "OPEN");
                else state.authorizationSent();
                assertEquals(n == 2, state.canAnnounce());
            }
        }
    }
    @Test public void preferenceNeverSelectsAClosedTransport() {
        InputSessionState state = new InputSessionState();
        state.channel("input", "OPEN");
        assertEquals("input", state.activeChannel());
        state.channel("unreliableinput", "OPEN");
        assertEquals("unreliableinput", state.activeChannel());
        state.preferModern(false);
        assertEquals("input", state.activeChannel());
        state.channel("input", "CLOSED");
        assertEquals("unreliableinput", state.activeChannel());
        state.channel("unreliableinput", "CLOSED");
        assertEquals("", state.activeChannel());
    }
    @Test public void snapshotRecoversLostOpenEventWithoutPhysicalInput() {
        InputSessionState state = new InputSessionState();
        state.authorizationSent();
        state.snapshot(false, true, true);
        assertEquals("unreliableinput", state.activeChannel());
        assertTrue(state.canAnnounce());
    }
    @Test public void secondSessionAndReopenedControlMustAuthorizeAgain() {
        InputSessionState state = new InputSessionState();
        state.snapshot(true, false, true);
        state.authorizationSent();
        assertTrue(state.canAnnounce());
        state.channel("control", "CLOSED");
        state.channel("control", "OPEN");
        assertFalse(state.canAnnounce());
        state.authorizationSent();
        assertTrue(state.canAnnounce());
        state.reset();
        state.snapshot(true, false, true);
        assertFalse(state.canAnnounce());
    }
}
