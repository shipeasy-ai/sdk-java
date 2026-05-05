package ai.shipeasy;

import java.nio.charset.StandardCharsets;

public final class Murmur3 {
    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private Murmur3() {}

    public static int hash32(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int n = data.length;
        int h1 = 0;
        int nblocks = n / 4;

        for (int i = 0; i < nblocks; i++) {
            int off = i * 4;
            int k1 = (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int tail = nblocks * 4;
        int k1 = 0;
        switch (n & 3) {
            case 3: k1 ^= (data[tail + 2] & 0xff) << 16;
            case 2: k1 ^= (data[tail + 1] & 0xff) << 8;
            case 1:
                k1 ^= (data[tail] & 0xff);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
        }

        h1 ^= n;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    /** Unsigned modulo: maps result of hash32 to [0, mod). */
    public static int bucket(String key, int mod) {
        return Integer.remainderUnsigned(hash32(key), mod);
    }
}
