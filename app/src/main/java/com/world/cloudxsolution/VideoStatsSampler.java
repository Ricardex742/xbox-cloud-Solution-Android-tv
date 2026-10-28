package com.world.cloudxsolution;

import java.util.Locale;
import java.util.Map;

/** Interval bitrate, never a cumulative average masquerading as current throughput. */
public final class VideoStatsSampler {
    private String previousId;
    private double previousTimestamp;
    private double previousBytes;
    private double previousReceived = -1, previousDecoded = -1, previousDecodeTime = -1;

    public void reset() {
        previousId = null;
        previousTimestamp = 0;
        previousBytes = 0;
        previousReceived = previousDecoded = previousDecodeTime = -1;
    }

    public String sample(String id, double timestampUs, Map<String, Object> video, Map<String, Object> codec) {
        double bytes = number(video.get("bytesReceived"));
        double elapsed = timestampUs - previousTimestamp;
        String rate = "medindo...";
        if (id.equals(previousId) && elapsed > 0 && bytes >= previousBytes && previousBytes >= 0) {
            // bits / microseconds == megabits / second.
            rate = String.format(Locale.ROOT, "%.1f Mbps", (bytes - previousBytes) * 8 / elapsed);
        }
        double received = number(video.get("framesReceived"));
        double decoded = number(video.get("framesDecoded"));
        double decodeTime = number(video.get("totalDecodeTime"));
        String receivedFps = "medindo...", decodeMs = "n/d";
        if (id.equals(previousId) && elapsed > 0) {
            if (previousReceived >= 0 && received >= previousReceived) {
                receivedFps = String.format(Locale.ROOT, "%.1f", (received - previousReceived) * 1000000 / elapsed);
            }
            if (previousDecoded >= 0 && decoded > previousDecoded && previousDecodeTime >= 0 && decodeTime >= previousDecodeTime) {
                decodeMs = String.format(Locale.ROOT, "%.2f", (decodeTime - previousDecodeTime) * 1000 / (decoded - previousDecoded));
            }
        }
        previousReceived = received;
        previousDecoded = decoded;
        previousDecodeTime = decodeTime;
        previousId = id;
        previousTimestamp = timestampUs;
        previousBytes = bytes;
        String profileId = profileId(codec);
        String profile = profileId == null ? "" : " [" + profileId + "]";
        return "Vídeo recebido: " + value(video, "frameWidth") + "x" + value(video, "frameHeight")
                + " | " + rate + "\nCodec: " + value(codec, "mimeType") + profile
                + "\nDecodificador: " + value(video, "decoderImplementation")
                + "\nFPS recebido: " + receivedFps + " | Decodificado: " + value(video, "framesPerSecond")
                + "\nDecodificação: " + decodeMs + " ms/quadro | Descartados: " + value(video, "framesDropped")
                + " | Pacotes perdidos: " + value(video, "packetsLost");
    }

    public static String profileStatus(boolean preferHigh, boolean hardwareHigh, boolean highOffered, Map<String, Object> codec) {
        String profile = profileId(codec);
        String effective = profileName(profile);
        String status = "Perfil solicitado nesta sessão: " + (preferHigh ? "High" : "Baseline")
                + "\nHigh por hardware: " + (hardwareHigh ? "disponível" : "não confirmado para este app")
                + " | High ofertado: " + (highOffered ? "sim" : "não")
                + "\nPerfil negociado: " + effective;
        if (preferHigh && !hardwareHigh) return status + "\nUsando os perfis disponíveis nesta TV.";
        if (preferHigh && !highOffered) return status + "\nHigh não entrou na oferta nativa.";
        if (preferHigh && profile != null && !profile.startsWith("64")) {
            return status + "\nHigh disponível; a sessão negociou outro perfil.";
        }
        return status;
    }

    static String profileId(Map<String, Object> codec) {
        Object fmtp = codec.get("sdpFmtpLine");
        if (fmtp instanceof String) for (String param : ((String) fmtp).split(";")) {
            String[] pair = param.trim().split("=", 2);
            if (pair.length == 2 && "profile-level-id".equalsIgnoreCase(pair[0].trim())) {
                String value = pair[1].trim().toLowerCase(Locale.ROOT);
                if (value.matches("[0-9a-f]{6}")) return value;
            }
        }
        return null;
    }

    private static String profileName(String profile) {
        if (profile == null) return "n/d";
        if (profile.startsWith("64")) return "H.264 High";
        if (VideoQualityPolicy.h264Rank(profile, false, false) == 6) return "H.264 Baseline";
        if (profile.startsWith("4d")) return "H.264 Main";
        return profile;
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : -1;
    }

    private static String value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "n/d" : String.valueOf(value);
    }
}
