package com.world.cloudxsolution;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Optional playback notification. Neither binding nor native startup waits for it. */
final class SessionForeground {
    interface Notification {
        void start();
        void stop();
    }
    private final Executor executor;
    private final Notification notification;
    private final Consumer<RuntimeException> onFailure;
    private boolean active, closed, scheduled;

    SessionForeground(Executor executor, Notification notification, Consumer<RuntimeException> onFailure) {
        this.executor = executor;
        this.notification = notification;
        this.onFailure = onFailure;
    }

    synchronized void setActive(boolean value) {
        if (closed || active == value) return;
        active = value;
        schedule();
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        active = false;
        schedule();
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        executor.execute(this::apply);
    }

    private void apply() {
        while (true) {
            boolean target;
            synchronized (this) { target = active && !closed; }
            try {
                if (target) notification.start(); else notification.stop();
            } catch (RuntimeException e) {
                // A denied foreground promotion must not terminate the bound video service.
                onFailure.accept(e);
            }
            synchronized (this) {
                if (target == (active && !closed)) {
                    scheduled = false;
                    return;
                }
            }
        }
    }
}
