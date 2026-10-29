package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;
import static android.app.ApplicationExitInfo.*;

public class StartupDiagnosticsTest {
    @Test public void oldCrashIsNotAttributedToCurrentAttempt() {
        assertNull(StartupDiagnostics.describeExit("app:stream", 1000, "app:stream", 999, REASON_CRASH, 1));
    }
    @Test public void mainAndWebViewExitsAreNotReportedAsVideoServiceCrashes() {
        assertNull(StartupDiagnostics.describeExit("app:stream", 1000, "app", 1001, REASON_CRASH, 1));
        assertNull(StartupDiagnostics.describeExit("app:stream", 1000, "webview", 1001, REASON_CRASH_NATIVE, 6));
    }
    @Test public void currentNativeCrashAndLowMemoryAreDistinguished() {
        String crash = StartupDiagnostics.describeExit("app:stream", 1000, "app:stream", 1001, REASON_CRASH_NATIVE, 6);
        String memory = StartupDiagnostics.describeExit("app:stream", 1000, "app:stream", 1002, REASON_LOW_MEMORY, 0);
        assertTrue(crash.contains("falha nativa"));
        assertTrue(crash.contains("status 6"));
        assertTrue(memory.contains("falta de memória"));
    }
}
