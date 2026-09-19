package eu.justnoone.geopackwiz.sync;

import eu.justnoone.geopackwiz.io.Dispatcher;

/**
 * Runs a pack sync on a background thread so the game (or the pre-menu boot
 * pump) never blocks on network I/O. Progress and results live in {@link
 * SyncState}, which any thread may read.
 */
public final class SyncEngine implements AutoCloseable {

    private final SyncState state = new SyncState();
    private volatile boolean started;
    private volatile Thread thread;

    public SyncState state() {
        return state;
    }

    public boolean isStarted() {
        return started;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        thread = new Thread(this::run, "GeoPackWiz-Sync");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            new Dispatcher().runSync(state);
            state.markFinished(true);
        } catch (SyncCancelledException ex) {
            state.markCancelled();
        } catch (Throwable t) {
            if (state.isAborted()) {
                state.markCancelled();
            } else {
                state.markFailed(t instanceof Exception e ? e : new RuntimeException(t));
            }
        }
    }

    /**
     * Ask the engine (and any in-flight downloads) to stop. Never blocks. The
     * engine thread is interrupted too, so a request stuck on a slow network
     * cannot delay a user who pressed ESC on the boot screen.
     */
    public void cancel() {
        state.requestAbort();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    @Override
    public void close() {
        cancel();
    }
}
