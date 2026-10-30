package com.world.cloudxsolution;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class PlaybackHealthStatsTest {
    @Test public void audioCanAdvanceWhileVideoHasBeenFrozenForFifteenSeconds() {
        PlaybackHealthStats stats = new PlaybackHealthStats();
        stats.audio("a", 1000000, Map.of("bytesReceived", 1000, "totalSamplesReceived", 48000, "concealedSamples", 0));
        String audio = stats.audio("a", 5000000, Map.of("bytesReceived", 33000, "totalSamplesReceived", 240000, "concealedSamples", 240));
        assertEquals("Áudio: 64.0 kbps | Amostras/s: 48000 | Ocultadas: 240", audio);
        assertEquals("Último quadro decodificado: 15.0 s atrás", PlaybackHealthStats.frameAge(20000, 5000));
        assertEquals("EGL: relatório de 15.0 s atrás", PlaybackHealthStats.rendererAge(20000, 5000));
    }
    @Test public void noAudioProgressReportsZeroRatherThanAssumingHealthyPlayback() {
        PlaybackHealthStats stats = new PlaybackHealthStats();
        Map<String, Object> sample = Map.of("bytesReceived", 1000, "totalSamplesReceived", 48000, "concealedSamples", 0);
        stats.audio("a", 1000000, sample);
        assertEquals("Áudio: 0.0 kbps | Amostras/s: 0 | Ocultadas: 0", stats.audio("a", 5000000, sample));
    }
    @Test public void absentCountersAndNewStreamDoNotInventRates() {
        PlaybackHealthStats stats = new PlaybackHealthStats();
        stats.audio("a", 1000000, Map.of("bytesReceived", 1000));
        assertTrue(stats.audio("b", 5000000, Map.of("bytesReceived", 5000)).contains("n/d kbps"));
        stats.reset();
        assertTrue(stats.audio("b", 9000000, Map.of()).contains("Amostras/s: n/d"));
        assertEquals("Quadro decodificado: aguardando", PlaybackHealthStats.frameAge(10000, -1));
    }
}
