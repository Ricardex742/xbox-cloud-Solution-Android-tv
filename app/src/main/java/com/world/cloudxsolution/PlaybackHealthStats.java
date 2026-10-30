package com.world.cloudxsolution;

import java.util.Locale;
import java.util.Map;

/** Audio interval counters and age of the last frame delivered by the decoder. */
final class PlaybackHealthStats {
    private String id;
    private double timestamp, bytes = -1, samples = -1, concealed = -1;
    void reset() { id = null; timestamp = 0; bytes = samples = concealed = -1; }

    String audio(String nextId, double timeUs, Map<String, Object> counters) {
        double nextBytes = number(counters.get("bytesReceived"));
        double nextSamples = number(counters.get("totalSamplesReceived"));
        double nextConcealed = number(counters.get("concealedSamples"));
        double elapsed = timeUs - timestamp;
        boolean valid = nextId.equals(id) && elapsed > 0 && Double.isFinite(elapsed);
        String bitrate = valid && bytes >= 0 && nextBytes >= bytes
                ? String.format(Locale.ROOT, "%.1f", (nextBytes - bytes) * 8000 / elapsed) : "n/d";
        String received = valid && samples >= 0 && nextSamples >= samples
                ? String.format(Locale.ROOT, "%.0f", (nextSamples - samples) * 1000000 / elapsed) : "n/d";
        String missing = valid && concealed >= 0 && nextConcealed >= concealed
                ? String.format(Locale.ROOT, "%.0f", nextConcealed - concealed) : "n/d";
        id = nextId; timestamp = timeUs; bytes = nextBytes; samples = nextSamples; concealed = nextConcealed;
        return "Áudio: " + bitrate + " kbps | Amostras/s: " + received + " | Ocultadas: " + missing;
    }

    static String frameAge(long nowMs, long lastFrameMs) {
        return lastFrameMs < 0 ? "Quadro decodificado: aguardando"
                : String.format(Locale.ROOT, "Último quadro decodificado: %.1f s atrás", Math.max(0, nowMs - lastFrameMs) / 1000.0);
    }

    static String rendererAge(long nowMs, long sampledAtMs) {
        return sampledAtMs < 0 ? "EGL: aguardando relatório"
                : String.format(Locale.ROOT, "EGL: relatório de %.1f s atrás", Math.max(0, nowMs - sampledAtMs) / 1000.0);
    }

    private static double number(Object value) {
        if (!(value instanceof Number)) return -1;
        double n = ((Number) value).doubleValue();
        return Double.isFinite(n) && n >= 0 ? n : -1;
    }
}
