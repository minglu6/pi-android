package android.os;

public final class Handler {
    private final Looper looper;
    public Handler() { this(Looper.getMainLooper()); }
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable task) { looper.enqueue(this, task); return true; }
    public boolean postDelayed(Runnable task, long delay) { looper.enqueueDelayed(this, task, delay); return true; }
    public void removeCallbacks(Runnable task) { looper.remove(this, task); }
}
