package eu.justnoone.geopackwiz.sync;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.gui.gl.GlHelper;
import eu.justnoone.geopackwiz.io.ProgressReceiver;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Thread-safe sink for everything a sync reports (log lines, progress, per-pack
 * status, failures). The sync engine writes to it from background threads and
 * any UI — the pre-menu boot screen or the in-game progress screen — reads
 * snapshots from the render thread. No GL or Minecraft calls happen here.
 */
public class SyncState implements ProgressReceiver {

    private static final int MAX_LOG_LINES = 600;

    private final Object lock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final List<String> packRows = new ArrayList<>();
    private final List<Thread> workerThreads = Collections.synchronizedList(new ArrayList<>());

    private String stageText = "Preparing ...";
    private String subText = "";
    private float progress;

    private volatile boolean abortRequested;

    private Exception failure;
    private boolean finished;
    private boolean success;
    private boolean cancelled;

    /** UI-only hint of when the failure state became visible (epoch millis). */
    private volatile long failedAtMillis;

    /** Register a worker thread so cancelling can interrupt it mid-download. */
    public void registerWorker(Thread thread) {
        if (thread != null) workerThreads.add(thread);
    }

    public void unregisterWorker(Thread thread) {
        if (thread != null) workerThreads.remove(thread);
    }

    // ---- ProgressReceiver ------------------------------------------------------

    @Override
    public void printLog(String line) throws GlHelper.MinecraftStoppingException {
        GeoPackWiz.LOGGER.info(line);
        synchronized (lock) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
            if (line != null && !line.isEmpty()) stageText = line;
        }
    }

    @Override
    public void printLogOutsidePolling(String line) throws GlHelper.MinecraftStoppingException {
        printLog(line);
    }

    @Override
    public void amendLastLog(String postfix) throws GlHelper.MinecraftStoppingException {
        synchronized (lock) {
            if (!logLines.isEmpty()) {
                String amended = logLines.removeLast() + (postfix == null ? "" : postfix);
                logLines.addLast(amended);
                if (!amended.isEmpty()) stageText = amended;
            }
        }
        GeoPackWiz.LOGGER.info(postfix == null ? "" : postfix);
    }

    @Override
    public void setProgress(float primary, float secondary) throws GlHelper.MinecraftStoppingException {
        this.progress = Math.max(0f, Math.min(1f, primary));
    }

    @Override
    public void setInfo(String secondary, String textValue) throws GlHelper.MinecraftStoppingException {
        String text = textValue == null ? "" : textValue;
        String aux = secondary == null ? "" : secondary;
        synchronized (lock) {
            subText = text.isEmpty() ? aux : text;
        }
    }

    @Override
    public void setPackStatus(List<String> lines) throws GlHelper.MinecraftStoppingException {
        synchronized (lock) {
            packRows.clear();
            if (lines != null) packRows.addAll(lines);
        }
    }

    @Override
    public void setException(Exception exception) throws GlHelper.MinecraftStoppingException {
        // The engine marks failure itself; nothing to do here.
    }

    @Override
    public boolean isAborted() {
        return abortRequested;
    }

    // ---- Writing (engine side) -------------------------------------------------

    public void requestAbort() {
        abortRequested = true;
        List<Thread> copy;
        synchronized (workerThreads) {
            copy = new ArrayList<>(workerThreads);
        }
        for (Thread t : copy) t.interrupt();
    }

    void markFinished(boolean ok) {
        synchronized (lock) {
            finished = true;
            success = ok;
            failure = ok ? null : failure;
        }
    }

    void markFailed(Exception ex) {
        synchronized (lock) {
            finished = true;
            success = false;
            cancelled = false;
            failure = ex == null ? new IllegalStateException("Unknown sync error") : ex;
            failedAtMillis = System.currentTimeMillis();
        }
    }

    void markCancelled() {
        synchronized (lock) {
            finished = true;
            success = false;
            cancelled = true;
            failure = null;
        }
    }

    // ---- Reading (UI side) -----------------------------------------------------

    public boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }

    public boolean succeeded() {
        synchronized (lock) {
            return finished && success;
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    public boolean failed() {
        synchronized (lock) {
            return finished && !success && !cancelled;
        }
    }

    public Exception failure() {
        synchronized (lock) {
            return failure;
        }
    }

    public long failedAtMillis() {
        return failedAtMillis;
    }

    public String stageText() {
        synchronized (lock) {
            return stageText;
        }
    }

    public String subText() {
        synchronized (lock) {
            return subText;
        }
    }

    public float progress() {
        return progress;
    }

    /** All log lines, oldest first (chronological). */
    public List<String> logs() {
        synchronized (lock) {
            return new ArrayList<>(logLines);
        }
    }

    public List<String> packRows() {
        synchronized (lock) {
            return new ArrayList<>(packRows);
        }
    }

    /** Full failure report used by the "report error" action (copy to clipboard). */
    public String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("GeoRail Pack Sync v").append(GeoPackWiz.MOD_VERSION)
          .append(" — ").append(Date.from(Instant.now())).append('\n');
        Exception ex = failure();
        if (ex != null) {
            sb.append("Error: ").append(ex).append('\n');
            for (StackTraceElement el : ex.getStackTrace()) {
                sb.append("  at ").append(el).append('\n');
            }
        }
        sb.append("---- live log ----\n");
        for (String line : logs()) sb.append(line).append('\n');
        return sb.toString();
    }
}
