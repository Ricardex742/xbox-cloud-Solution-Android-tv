package com.world.cloudxsolution;

import java.util.Locale;
import java.util.Map;

/** Interval bitrate, never a cumulative average masquerading as current throughput. */
public final class VideoStatsSampler {
    private String previousId;
    private double previousTimestamp;
    private double previousBytes;

    public void reset() {
        previousId = null;
        previousTimestamp = 0;
        previousBytes = 0;
    }

    public String sample(String id, double timestampUs, Map<String, Object> video, Map<String, Object> codec) {
        double bytes = number(video.get("bytesReceived"));
        double elapsed = timestampUs - previousTimestamp;
        String rate = "medindo...";
        if (id.equals(previousId) && elapsed > 0 && bytes >= previousBytes && previousBytes >= 0) {
            // bits / microseconds == megabits / second.
            rate = String.format(Locale.ROOT, "%.1f Mbps", (bytes - previousBytes) * 8 / elapsed);
        }
        previousId = id;
        previousTimestamp = timestampUs;
        previousBytes = bytes;
        String profile = "";
        Object fmtp = codec.get("sdpFmtpLine");
        if (fmtp instanceof String) for (String param : ((String) fmtp).split(";")) {
            if (param.trim().startsWith("profile-level-id=")) profile = " [" + param.trim().substring(17) + "]";
        }
        return "Vídeo recebido: " + value(video, "frameWidth") + "x" + value(video, "frameHeight")
                + " | " + rate + "\nCodec: " + value(codec, "mimeType") + profile
                + "\nDecodificador: " + value(video, "decoderImplementation")
                + "\nFPS recebido: " + value(video, "framesPerSecond")
                + " | Perdidos na rede: " + value(video, "packetsLost");
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : -1;
    }

    private static String value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "n/d" : String.valueOf(value);
    }
}
