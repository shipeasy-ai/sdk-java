package ai.shipeasy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Murmur3Test {
    // Values match the Ruby SDK reference impl. The table in
    // experiment-platform/04-evaluation.md disagrees on some inputs; cross-language
    // consistency is the contract.
    @Test void vectors() {
        assertEquals(0x00000000, Murmur3.hash32(""));
        assertEquals(0x3c2569b2, Murmur3.hash32("a"));
        assertEquals(0x9bbfd75f, Murmur3.hash32("ab"));
        assertEquals(0xb3dd93fa, Murmur3.hash32("abc"));
        assertEquals(0x7eeed987, Murmur3.hash32("aaaa"));
        assertEquals(0xe9ca302b, Murmur3.hash32("aaaaa"));
        assertEquals(0xe2a131eb, Murmur3.hash32("Hello, 世界"));
        assertEquals(0x2e4ff723, Murmur3.hash32("The quick brown fox jumps over the lazy dog"));
    }
}
