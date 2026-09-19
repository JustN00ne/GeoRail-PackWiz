package eu.justnoone.geopackwiz.sync;

/** Thrown inside the sync pipeline when the user asked to abort a running sync. */
public final class SyncCancelledException extends RuntimeException {

    public SyncCancelledException() {
        super("Sync cancelled.");
    }

    public SyncCancelledException(Throwable cause) {
        super("Sync cancelled.", cause);
    }
}
