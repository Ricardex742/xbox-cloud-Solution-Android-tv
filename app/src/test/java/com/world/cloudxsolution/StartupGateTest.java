package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.world.cloudxsolution.StartupGate.Stage.*;

public class StartupGateTest {
    @Test public void catalogRequiresFactoryAndSurfaceAndOpensOnlyOnce() {
        StartupGate gate = new StartupGate();
        int attempt = gate.begin();
        assertFalse(gate.advance(attempt, READY));
        assertFalse(gate.ready());
        assertTrue(gate.advance(attempt, INITIALIZING));
        assertFalse(gate.advance(attempt, READY));
        assertTrue(gate.advance(attempt, SURFACE));
        assertTrue(gate.advance(attempt, READY));
        assertTrue(gate.ready());
        assertFalse(gate.advance(attempt, READY));
        assertFalse(gate.accepts(attempt)); // an already scheduled deadline cannot fail it
    }
    @Test public void nativeReturnAfterTimeoutCannotOpenCatalog() {
        for (StartupGate.Stage phase : new StartupGate.Stage[]{BINDING, INITIALIZING, SURFACE}) {
            StartupGate gate = new StartupGate();
            int attempt = gate.begin();
            if (phase.ordinal() >= INITIALIZING.ordinal()) gate.advance(attempt, INITIALIZING);
            if (phase == SURFACE) gate.advance(attempt, SURFACE);
            gate.fail();
            assertFalse(gate.accepts(attempt));
            assertFalse(gate.advance(attempt, READY));
            assertFalse(gate.advance(attempt, INITIALIZING));
            assertFalse(gate.ready());
        }
    }
    @Test public void retryIgnoresCallbacksAndDeadlineFromPreviousAttempt() {
        StartupGate gate = new StartupGate();
        int old = gate.begin();
        gate.advance(old, INITIALIZING);
        gate.fail();
        int current = gate.begin();
        assertFalse(gate.accepts(old));
        assertFalse(gate.advance(old, SURFACE));
        assertTrue(gate.advance(current, INITIALIZING));
        assertTrue(gate.advance(current, SURFACE));
        assertTrue(gate.advance(current, READY));
    }
    @Test public void exitBeforeServiceConnectsRejectsAllLateCallbacks() {
        StartupGate gate = new StartupGate();
        int attempt = gate.begin();
        gate.close();
        assertFalse(gate.advance(attempt, INITIALIZING));
        gate.fail();
        assertEquals(CLOSED, gate.stage());
        assertFalse(gate.ready());
    }
    @Test public void serviceDisconnectRevokesReadiness() {
        StartupGate gate = new StartupGate();
        int attempt = gate.begin();
        gate.advance(attempt, INITIALIZING);
        gate.advance(attempt, SURFACE);
        gate.advance(attempt, READY);
        gate.fail();
        assertFalse(gate.ready());
    }
}
