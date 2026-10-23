package io.github.fulingsnow.youshuiauth.platform.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SkinPartsCodecTest {

    @Test
    void allDisabledIsSevenDashes() {
        assertEquals("-------", SkinPartsCodec.encode(false, false, false, false,
                false, false, false));
    }

    @Test
    void allEnabledUsesHistoricalLetters() {
        assertEquals("cjsspph", SkinPartsCodec.encode(true, true, true, true,
                true, true, true));
    }

    @Test
    void encodingStaysStableForExistingFingerprints() {

        assertEquals("c------", SkinPartsCodec.encode(true, false, false, false,
                false, false, false));
        assertEquals("-j-----", SkinPartsCodec.encode(false, true, false, false,
                false, false, false));
        assertEquals("--s----", SkinPartsCodec.encode(false, false, true, false,
                false, false, false));
        assertEquals("---s---", SkinPartsCodec.encode(false, false, false, true,
                false, false, false));
        assertEquals("----p--", SkinPartsCodec.encode(false, false, false, false,
                true, false, false));
        assertEquals("-----p-", SkinPartsCodec.encode(false, false, false, false,
                false, true, false));
        assertEquals("------h", SkinPartsCodec.encode(false, false, false, false,
                false, false, true));
    }

    @Test
    void leftAndRightAreDistinguishedByPosition() {

        assertEquals("--s----", SkinPartsCodec.encode(false, false, true, false,
                false, false, false));
        assertEquals("---s---", SkinPartsCodec.encode(false, false, false, true,
                false, false, false));
        assertEquals("--ss---", SkinPartsCodec.encode(false, false, true, true,
                false, false, false));
        assertEquals("----pp-", SkinPartsCodec.encode(false, false, false, false,
                true, true, false));
        assertNotEquals(SkinPartsCodec.encode(false, false, true, false, false, false, false),
                SkinPartsCodec.encode(false, false, false, false, false, false, false));
    }

    @Test
    void alwaysSevenCharacters() {
        for (int mask = 0; mask < 128; mask++) {
            String key = SkinPartsCodec.encode(
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                    (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0);
            assertEquals(7, key.length(), "mask=" + mask + " key=" + key);
        }
    }
}
