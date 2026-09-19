package eu.justnoone.geopackwiz;

/**
 * Connection settings baked into the mod jar at build time.
 *
 * Players cannot (and should not need to) change these: the GeoRail pack website
 * credentials and the server the pack is served for are fixed per jar build. The
 * values are stored XOR-masked so they do not sit as plain text in the jar.
 */
public final class ServerConfig {

    private ServerConfig() {
    }

    /** Pack website the mod talks to (pack list + encrypted download). */
    public static final String WEBSITE_BASE_URL = "https://grpu.justnoone.eu";
    /** Endpoint path for the encrypted .geopak download. */
    public static final String REQUEST_PATH = "/api/request-pack";
    /** Canonical server shown to the player (the pack works on any GeoRail host). */
    public static final String ENABLED_SERVER_ADDRESS = "play.georail.eu";
    /** Any joined host containing this marker (case-insensitive) counts as a GeoRail server. */
    public static final String ENABLED_HOST_MARKER = "georail";
    /** Extra hosts/IPs that also count as GeoRail servers (e.g. the direct IP). */
    public static final String[] ENABLED_EXTRA_HOSTS = { "159.195.108.223" };

    private static final int[] MASKED_API_KEY = {
            100, 20, 117, 93, 183, 193, 242, 215, 157, 40, 8, 106, 73, 248, 218, 235,
            207, 37, 87, 96, 75, 243, 131, 180, 140, 108, 24, 125, 82, 189, 145, 169,
            129, 50, 70, 33, 86, 187, 148, 245, 238, 156, 121, 90, 62, 67, 169, 138,
            226, 149, 112, 87, 49, 16, 161, 134, 170, 223, 105, 20, 126, 92, 187, 205
    };

    private static final int[] MASKED_SECRET_KEY = {
            55, 69, 114, 80, 177, 196, 241, 213, 154, 123, 11, 101, 75, 254, 140, 235,
            195, 32, 87, 102, 69, 165, 213, 181, 139, 56, 28, 127, 90, 187, 157, 241,
            211, 62, 28, 33, 87, 231, 147, 164, 186, 155, 41, 14, 98, 28, 253, 222,
            179, 144, 33, 5, 96, 68, 243, 213, 171, 214, 60, 21, 42, 90, 232, 155
    };

    /** The website's MOD_API_KEY (X-API-Key header). */
    public static final String API_KEY = unmask(MASKED_API_KEY);
    /** The website's GEOPAK_SECRET_KEY, 64 hex chars -> 32 bytes (AES-256-GCM). */
    public static final String SECRET_KEY = unmask(MASKED_SECRET_KEY);

    private static String unmask(int[] masked) {
        StringBuilder sb = new StringBuilder(masked.length);
        for (int i = 0; i < masked.length; i++) {
            sb.append((char) (masked[i] ^ ((i * 31 + 7) & 0xFF)));
        }
        return sb.toString();
    }
}
