package eu.justnoone.geopackwiz.drm;

/**
 * Kept for the legacy server-lock handshake packets (the server asks the client
 * which mod version it runs). With the pack living entirely in RAM there is no
 * on-disk encryption to coordinate anymore; this registry only tracks the lock
 * value received from the server.
 */
public class ServerLockRegistry {

    private static String remoteServerLock;

    private ServerLockRegistry() {
    }

    public static void onLoginInitiated() {
        remoteServerLock = null;
    }

    public static void onSetServerLock(String serverLock) {
        remoteServerLock = serverLock;
    }

    /** @return the server lock value most recently received, or null. */
    public static String currentServerLock() {
        return remoteServerLock;
    }
}
