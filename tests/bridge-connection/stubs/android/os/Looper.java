package android.os;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/** Deterministic queue: no timers/threads, tests explicitly drain or advance virtual time. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private static final class Task {
        final Handler owner;
        final Runnable runnable;
        final long due;
        Task(Handler owner, Runnable runnable, long due) { this.owner = owner; this.runnable = runnable; this.due = due; }
    }
    private final Deque<Task> queue = new ArrayDeque<>();
    private final List<Task> delayed = new ArrayList<>();
    private long now;
    public static Looper getMainLooper() { return MAIN; }
    synchronized void enqueue(Handler owner, Runnable task) { queue.add(new Task(owner, task, now)); }
    synchronized void enqueueDelayed(Handler owner, Runnable task, long delay) {
        delayed.add(new Task(owner, task, now + Math.max(0, delay)));
    }
    synchronized void remove(Handler owner, Runnable task) {
        queue.removeIf(t -> t.owner == owner && t.runnable == task);
        delayed.removeIf(t -> t.owner == owner && t.runnable == task);
    }
    public synchronized int pending() { return queue.size(); }
    public synchronized int pendingDelayed() { return delayed.size(); }
    public void drain() {
        for (;;) {
            Task task;
            synchronized (this) { task = queue.poll(); }
            if (task == null) return;
            task.runnable.run();
        }
    }
    /** Advance fake time; run only tasks whose original due time is reached. No wall-clock sleep. */
    public void advanceBy(long millis) {
        if (millis < 0) throw new IllegalArgumentException("negative time");
        synchronized (this) {
            now += millis;
            delayed.sort(Comparator.comparingLong(t -> t.due));
            for (Task task : delayed) if (task.due <= now) queue.add(task);
            delayed.removeIf(task -> task.due <= now);
        }
        drain();
    }
    /** Run the currently scheduled delayed wave, not new delayed tasks scheduled by its callbacks. */
    public void runDelayed() {
        long target;
        synchronized (this) {
            target = now;
            for (Task task : delayed) target = Math.max(target, task.due);
            target -= now;
        }
        advanceBy(target);
    }
    public synchronized void clear() { queue.clear(); delayed.clear(); now = 0; }
}
