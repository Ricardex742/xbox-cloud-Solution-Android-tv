package com.world.cloudxsolution;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Interval measurements, preserving the distinction between reception and decoding. */
public final class VideoStatsSampler {
    private String previousId;
    private double previousTimestamp;
    private Map<String, Object> previous = Collections.emptyMap();

    public void reset() {
        previousId = null;
        previousTimestamp = 0;
        previous = Collections.emptyMap();
    }

    public String sample(String id, double timestampUs, Map<String, Object> video, Map<String, Object> codec) {
        double elapsed = timestampUs - previousTimestamp;
        boolean interval = id.equals(previousId) && Double.isFinite(elapsed) && elapsed > 0;
        double bytes = delta(video, "bytesReceived", interval);
        double received = delta(video, "framesReceived", interval);
        double decoded = delta(video, "framesDecoded", interval);
        double decodeTime = delta(video, "totalDecodeTime", interval);
        double dropped = delta(video, "framesDropped", interval);
        double lost = delta(video, "packetsLost", interval);
        double jitterDelay = delta(video, "jitterBufferDelay", interval);
        double emitted = delta(video, "jitterBufferEmittedCount", interval);
        String rate = bytes >= 0 ? format(bytes * 8 / elapsed, 1) + " Mbps" : "medindo...";
        String receivedFps = received >= 0 ? format(received * 1000000 / elapsed, 1) : "n/d";
        String decodedFps = decoded >= 0 ? format(decoded * 1000000 / elapsed, 1) : "n/d";
        String decodeMs = decoded > 0 && decodeTime >= 0 ? format(decodeTime * 1000 / decoded, 2) : "n/d";
        String bufferMs = emitted > 0 && jitterDelay >= 0 ? format(jitterDelay * 1000 / emitted, 1) : "n/d";
        previousId = id;
        previousTimestamp = timestampUs;
        previous = new HashMap<>(video);
        String profile = "";
        Object fmtp = codec.get("sdpFmtpLine");
        if (fmtp instanceof String) for (String param : ((String) fmtp).split(";")) {
            if (param.trim().startsWith("profile-level-id=")) profile = " [" + param.trim().substring(17) + "]";
        }
        return "Vídeo recebido: " + value(video, "frameWidth") + "x" + value(video, "frameHeight")
                + " | " + rate + "\nCodec: " + value(codec, "mimeType") + profile
                + "\nDecodificador: " + value(video, "decoderImplementation")
                + "\nFPS recebidos: " + receivedFps + " | Decodificados: " + decodedFps
                + "\nDecodificação: " + decodeMs + " ms/quadro | Buffer: " + bufferMs + " ms"
                + "\nDescartes WebRTC: " + count(dropped) + " | Pacotes perdidos: " + count(lost) + " (intervalo)"
                + "\nJitter da rede: " + milliseconds(video.get("jitter")) + " ms";
    }

    public static String networkSummary(Map<String, Object> pair, Map<String, Object> localCandidate) {
        return "RTT ICE: " + milliseconds(pair.get("currentRoundTripTime"))
                + " ms | ICE: " + value(localCandidate, "protocol")
                + (localCandidate.containsKey("relayProtocol") ? " / relay: " + value(localCandidate, "relayProtocol") : "");
    }

    private double delta(Map<String, Object> current, String key, boolean interval) {
        double now = number(current.get(key)), before = number(previous.get(key));
        return interval && now >= 0 && before >= 0 && now >= before ? now - before : -1;
    }

    private static String milliseconds(Object value) {
        double seconds = number(value);
        return seconds >= 0 ? format(seconds * 1000, 1) : "n/d";
    }

    private static String count(double number) { return number >= 0 ? format(number, 0) : "n/d"; }
    private static String format(double value, int precision) {
        return String.format(Locale.ROOT, "%." + precision + "f", value).trim();
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
        if (!(value instanceof Number)) return -1;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : -1;
    }
    private static String value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "n/d" : String.valueOf(value);
    }
}
