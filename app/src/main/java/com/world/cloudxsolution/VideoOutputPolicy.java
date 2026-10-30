package com.world.cloudxsolution;

/** Surface allocation only. Never advertises a higher source resolution to the service. */
final class VideoOutputPolicy {
    static int[] size(int width, int height, int displayWidth, int displayHeight, int mode) {
        if (width <= 0 || height <= 0) return new int[]{width, height};
        if (mode == 0 || displayWidth <= 0 || displayHeight <= 0) return new int[]{width, height};
        int capWidth = mode == 1 ? 1920 : 3840, capHeight = mode == 1 ? 1080 : 2160;
        double scale = Math.min((double)Math.min(displayWidth, capWidth) / width,
                (double)Math.min(displayHeight, capHeight) / height);
        if (scale <= 1) return new int[]{width, height};
        return new int[]{(int)Math.round(width * scale), (int)Math.round(height * scale)};
    }
}
