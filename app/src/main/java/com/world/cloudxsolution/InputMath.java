package com.world.cloudxsolution;

/** Shared input policy for both transports and the on-screen preview. */
public final class InputMath {
    private InputMath() {}

    public static float leftDeadzone(float value) {
        return Float.isFinite(value) ? Math.max(0.15f, Math.min(0.95f, value)) : 0.15f;
    }

    public static float clamp(float value) {
        return Float.isFinite(value) ? Math.max(-1f, Math.min(1f, value)) : 0f;
    }

    public static float sensitivity(float value) {
        return Float.isFinite(value) ? Math.max(0.1f, Math.min(5f, value)) : 1f;
    }

    public static void response(float x, float y, float deadzone, float curve,
                                float sensitivity, float[] out) {
        x = clamp(x);
        y = clamp(y);
        deadzone = Float.isFinite(deadzone) ? Math.max(0f, Math.min(0.95f, deadzone)) : 0f;
        curve = Float.isFinite(curve) ? Math.max(0.25f, Math.min(4f, curve)) : 1f;
        float magnitude = (float) Math.hypot(x, y);
        if (magnitude <= deadzone) {
            out[0] = out[1] = 0f;
            return;
        }
        float normalized = Math.min(1f, (magnitude - deadzone) / (1f - deadzone));
        float scale = (float) Math.pow(normalized, curve) / magnitude;
        out[0] = clamp(x * scale * sensitivity(sensitivity));
        out[1] = clamp(y * scale * sensitivity(sensitivity));
    }
}
