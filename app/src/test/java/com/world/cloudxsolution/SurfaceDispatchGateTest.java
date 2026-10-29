package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceDispatchGateTest {
    @Test public void repeatedSizeCallbacksDoNotRecreateAnAttachedRenderer() {
        SurfaceDispatchGate gate = new SurfaceDispatchGate();
        assertFalse(gate.needsDelivery());
        gate.created();
        assertTrue(gate.needsDelivery());
        gate.delivered(gate.generation());
        // surfaceChanged uses needsDelivery without changing lifecycle identity.
        for (int n = 0; n < 100; n++) assertFalse(gate.needsDelivery());
    }
    @Test public void aNewSurfaceMustBeDeliveredEvenIfSizeIsUnchanged() {
        SurfaceDispatchGate gate = new SurfaceDispatchGate();
        gate.created();
        gate.delivered(gate.generation());
        gate.destroyed();
        assertFalse(gate.needsDelivery());
        gate.created();
        assertTrue(gate.needsDelivery());
    }
    @Test public void oldAsyncDeliveryCannotSatisfyAReplacementSurface() {
        SurfaceDispatchGate gate = new SurfaceDispatchGate();
        gate.created();
        long old = gate.generation();
        gate.destroyed();
        gate.created();
        gate.delivered(old);
        assertTrue(gate.needsDelivery());
        gate.delivered(gate.generation());
        assertFalse(gate.needsDelivery());
    }
    @Test public void nextSessionCanExplicitlyReattachTheSameSurface() {
        SurfaceDispatchGate gate = new SurfaceDispatchGate();
        gate.created();
        gate.delivered(gate.generation());
        gate.requested();
        assertTrue(gate.needsDelivery());
    }
    @Test public void completionAfterDestructionDoesNotMakeSurfaceReady() {
        SurfaceDispatchGate gate = new SurfaceDispatchGate();
        gate.created();
        long old = gate.generation();
        gate.destroyed();
        gate.delivered(old);
        assertFalse(gate.available());
        assertFalse(gate.needsDelivery());
        gate.requested();
        assertFalse(gate.needsDelivery());
        gate.created();
        assertTrue(gate.needsDelivery());
    }
}
