package eu.justnoone.geopackwiz.ram;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A byte array whose contents are XOR-obfuscated in memory so the raw asset
 * data cannot be trivially read from a heap dump or debugger. The obfuscation
 * key is stored in a separate array so the two never sit adjacent in the heap.
 *
 * <p>This is <b>not</b> cryptographic protection — it is a best-effort barrier
 * against casual inspection (e.g. {@code jmap -histo}, VisualVM, or a simple
 * {@code toString()} on the backing array). A determined attacker with JVM
 * access can always bypass it.
 */
public final class SecureByteArray {

    private static final SecureRandom RNG = new SecureRandom();

    private byte[] data;
    private byte[] key;
    private final int length;

    /**
     * Create a new obfuscated byte array from the given plain-text bytes.
     * The original {@code source} array is zeroed after copying.
     */
    public SecureByteArray(byte[] source) {
        this.length = source.length;
        this.key = generateKey(length);
        this.data = new byte[length];
        obfuscate(source, key, data);
        // Wipe the caller's copy
        Arrays.fill(source, (byte) 0);
    }

    /** Return the de-obfuscated copy. The caller must not hold on to it. */
    public byte[] get() {
        byte[] plain = new byte[length];
        deobfuscate(data, key, plain);
        return plain;
    }

    /** Copy de-obfuscated bytes into the supplied buffer (no extra allocation). */
    public void getInto(byte[] dest) {
        if (dest.length < length) {
            throw new IllegalArgumentException("destination too short");
        }
        deobfuscate(data, key, dest);
    }

    /** Length of the original byte array. */
    public int length() {
        return length;
    }

    /**
     * Overwrite the internal data and key with zeros. After this call the
     * object is unusable and should be discarded.
     */
    public void wipe() {
        if (data != null) Arrays.fill(data, (byte) 0);
        if (key != null) Arrays.fill(key, (byte) 0);
        data = null;
        key = null;
    }

    /** Returns {@code true} after {@link #wipe()} has been called. */
    public boolean isWiped() {
        return data == null;
    }

    // ---- internals -----------------------------------------------------------

    private static byte[] generateKey(int len) {
        byte[] k = new byte[len];
        RNG.nextBytes(k);
        return k;
    }

    /** plain = data XOR key (in-place on {@code out}, reading from {@code plain} for the initial obfuscation). */
    private static void obfuscate(byte[] plain, byte[] key, byte[] out) {
        for (int i = 0; i < plain.length; i++) {
            out[i] = (byte) (plain[i] ^ key[i]);
        }
    }

    /** plain = data XOR key (in-place on {@code out}). */
    private static void deobfuscate(byte[] obfuscated, byte[] key, byte[] out) {
        for (int i = 0; i < obfuscated.length; i++) {
            out[i] = (byte) (obfuscated[i] ^ key[i]);
        }
    }
}
