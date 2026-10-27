package com.world.cloudxsolution;

/** Apply bandwidth to the native offer before setLocalDescription. Never forge codec profiles. */
public final class SdpPolicy {
    private SdpPolicy() {}
    public static String videoBandwidth(String sdp, int requestedKbps) {
        int cap = requestedKbps <= 0 ? 20000 : Math.max(1000, Math.min(20000, requestedKbps));
        StringBuilder result = new StringBuilder();
        boolean video = false;
        for (String line : sdp.split("\\r?\\n")) {
            if (line.startsWith("m=")) video = line.startsWith("m=video ");
            if (video && (line.startsWith("b=AS:") || line.startsWith("b=TIAS:"))) continue;
            result.append(line).append("\r\n");
            if (video && line.startsWith("c=")) result.append("b=AS:").append(cap).append("\r\n");
        }
        return result.toString();
    }
}
