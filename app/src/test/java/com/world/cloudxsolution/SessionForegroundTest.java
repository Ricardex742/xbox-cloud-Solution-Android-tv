package com.world.cloudxsolution;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SessionForegroundTest {
    private static class Fixture {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        final List<RuntimeException> errors = new ArrayList<>();
        boolean deny;
        Runnable duringStart = () -> {};
        final SessionForeground subject = new SessionForeground(tasks::add, new SessionForeground.Notification() {
            public void start() {
                events.add("start");
                duringStart.run();
                if (deny) throw new SecurityException("denied by system");
            }
            public void stop() { events.add("stop"); }
        }, errors::add);
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    @Test public void creationAndInactiveBindingNeverCallNotificationSystem() {
        Fixture f = new Fixture();
        f.subject.setActive(false);
        assertTrue(f.tasks.isEmpty());
        assertTrue(f.events.isEmpty());
    }
    @Test public void sessionActivationDoesNotWaitForNotificationWork() {
        Fixture f = new Fixture();
        f.subject.setActive(true);
        assertTrue(f.events.isEmpty());
        assertEquals(1, f.tasks.size());
        f.drain();
        assertEquals(List.of("start"), f.events);
    }
    @Test public void deniedPromotionDoesNotCrashOrRetryForever() {
        Fixture f = new Fixture();
        f.deny = true;
        f.subject.setActive(true);
        f.drain();
        assertEquals(1, f.errors.size());
        f.subject.setActive(true);
        assertTrue(f.tasks.isEmpty());
        f.subject.setActive(false);
        f.drain();
        assertEquals(List.of("start", "stop"), f.events);
    }
    @Test public void quickExitNeverPromotesAnEndedSession() {
        Fixture f = new Fixture();
        f.subject.setActive(true);
        f.subject.setActive(false);
        assertEquals(1, f.tasks.size());
        f.drain();
        assertEquals(List.of("stop"), f.events);
    }
    @Test public void destructionDuringSlowPromotionRemovesNotificationAfterReturn() {
        Fixture f = new Fixture();
        f.duringStart = f.subject::close;
        f.subject.setActive(true);
        f.drain();
        assertEquals(List.of("start", "stop"), f.events);
        f.subject.setActive(true);
        assertTrue(f.tasks.isEmpty());
    }
    @Test public void repeatedStateChangesDoNotBuildAnUnboundedNotificationQueue() {
        Fixture f = new Fixture();
        for (int n = 0; n < 1000; n++) { f.subject.setActive(true); f.subject.setActive(false); }
        assertEquals(1, f.tasks.size());
        f.drain();
        assertEquals(List.of("stop"), f.events);
    }
}
