package com.zillion.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Delayed-tick task manager with two independent queues: one driven by the server tick, one by the client tick.
 * <pre>
 *   TickScheduler.server(player.getUUID(), 27, () -> ...);   // runs 27 server ticks later
 *   TickScheduler.client(entityId, 3, () -> ...);            // runs 3 client ticks later
 *   TickScheduler.cancelServer(player.getUUID());            // drop every pending task of that group
 * </pre>
 * {@code delay = 0} runs on the next tick. Tasks may schedule further tasks from inside their runnable.
 * The queues are pumped by {@link #tickServer()} (ServerTickEvent.Post) and {@link #tickClient()} (ClientTickEvent.Post)
 * and are cleared on server stop / client logout.
 */
public final class TickScheduler {
    private static final Queue SERVER = new Queue();
    private static final Queue CLIENT = new Queue();

    private TickScheduler() {}

    // ------------------------------------------------------------------ server
    public static Task server(int delay, Runnable action) {
        return SERVER.add(null, delay, action);
    }

    public static Task server(@Nullable Object group, int delay, Runnable action) {
        return SERVER.add(group, delay, action);
    }

    public static void cancelServer(Object group) {
        SERVER.cancel(group);
    }

    public static void tickServer() {
        SERVER.tick();
    }

    public static void clearServer() {
        SERVER.clear();
    }

    // ------------------------------------------------------------------ client
    public static Task client(int delay, Runnable action) {
        return CLIENT.add(null, delay, action);
    }

    public static Task client(@Nullable Object group, int delay, Runnable action) {
        return CLIENT.add(group, delay, action);
    }

    public static void cancelClient(Object group) {
        CLIENT.cancel(group);
    }

    public static void tickClient() {
        CLIENT.tick();
    }

    public static void clearClient() {
        CLIENT.clear();
    }

    // ------------------------------------------------------------------ impl
    /** Handle of a scheduled task; {@link #cancel()} prevents it from running. */
    public static final class Task {
        @Nullable
        final Object group;
        final Runnable action;
        int remaining;
        boolean cancelled;

        Task(@Nullable Object group, int delay, Runnable action) {
            this.group = group;
            this.remaining = Math.max(0, delay);
            this.action = action;
        }

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isPending() {
            return !this.cancelled && this.remaining >= 0;
        }
    }

    private static final class Queue {
        private final List<Task> tasks = new ArrayList<>();
        private final List<Task> incoming = new ArrayList<>();
        private boolean ticking;

        synchronized Task add(@Nullable Object group, int delay, Runnable action) {
            Task task = new Task(group, delay, action);
            (this.ticking ? this.incoming : this.tasks).add(task);
            return task;
        }

        synchronized void cancel(Object group) {
            for (Task t : this.tasks)
                if (group.equals(t.group))
                    t.cancelled = true;
            for (Task t : this.incoming)
                if (group.equals(t.group))
                    t.cancelled = true;
        }

        synchronized void clear() {
            this.tasks.clear();
            this.incoming.clear();
        }

        void tick() {
            List<Task> due = new ArrayList<>();
            synchronized (this) {
                this.ticking = true;
                Iterator<Task> it = this.tasks.iterator();
                while (it.hasNext()) {
                    Task t = it.next();
                    if (t.cancelled) {
                        it.remove();
                        continue;
                    }
                    if (t.remaining-- <= 0) {
                        due.add(t);
                        it.remove();
                    }
                }
            }
            try {
                for (Task t : due)
                    if (!t.cancelled) {
                        t.remaining = -1;
                        t.action.run();
                    }
            } finally {
                synchronized (this) {
                    this.ticking = false;
                    this.tasks.addAll(this.incoming);
                    this.incoming.clear();
                }
            }
        }
    }
}
