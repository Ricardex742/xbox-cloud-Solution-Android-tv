package com.world.cloudxsolution;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/** Opt-in, bounded, local trace. Only controller events/packets are admitted. */
final class InputTrace {
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long expiresAt, lastMotionAt;
    private int lastMenu = -1;
    synchronized void enable(long now) { lines.clear(); expiresAt = now + 600000; lastMenu = -1; lastMotionAt = 0; }
    synchronized void disable() { expiresAt = 0; }
    synchronized boolean enabled(long now) { return expiresAt > now; }
    synchronized void add(long now, String text) {
        if (!enabled(now)) return;
        if (lines.size() == 160) lines.removeFirst();
        lines.addLast(now + " " + text);
    }
    synchronized boolean motionDue(long now) {
        if (!enabled(now) || now - lastMotionAt < 200) return false;
        lastMotionAt = now; return true;
    }
    synchronized void packet(long now, String channel, byte[] bytes) {
        if (!enabled(now)) return;
        int menu = menuValue(channel, bytes);
        if (menu < 0 || menu == lastMenu) return;
        lastMenu = menu;
        add(now, "pacote " + channel + " Menu=" + menu + " bytes=" + bytes.length);
    }
    static int menuValue(String channel, byte[] bytes) {
        if (bytes == null) return -1;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if ("input".equals(channel) && bytes.length >= 18 && (b.getShort(0) & 2) != 0)
            return (b.getShort(16) & GamepadFrame.MENU) == 0 ? 0 : 1;
        if ("unreliableinput".equals(channel) && bytes.length >= 25 && (b.getShort(0) & 512) != 0)
            return bytes[24] & 255; // transition counter, not a held-state flag
        return -1;
    }
    synchronized String report() { return String.join("\n", lines); }
}
