package com.world.cloudxsolution;

/** UI-thread identity of the Surface lifecycle, independent of buffer-size callbacks. */
final class SurfaceDispatchGate {
    private long generation;
    private long delivered = -1;
    private boolean available;

    void created() { ++generation; available = true; }
    void destroyed() { ++generation; available = false; }
    void requested() { ++generation; }
    long generation() { return generation; }
    boolean available() { return available; }
    boolean needsDelivery() { return available && delivered != generation; }
    void delivered(long sentGeneration) {
        if (available && generation == sentGeneration) delivered = sentGeneration;
    }
}
