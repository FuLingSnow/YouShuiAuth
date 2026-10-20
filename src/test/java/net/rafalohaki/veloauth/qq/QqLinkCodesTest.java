package net.rafalohaki.veloauth.qq;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqLinkCodesTest {

    @Test
    void generatedCodesUseTheUnambiguousAlphabet() {
        for (int i = 0; i < 200; i++) {
            String code = QqLinkCodes.generate(8);
            assertEquals(8, code.length());
            assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{8}"), code);
        }
    }

    @Test
    void normalizeFoldsCaseSeparatorsAndAmbiguousCharacters() {
        assertEquals("AB12CD34", QqLinkCodes.normalize("ab12-cd34"));
        assertEquals("AB0121CD34", QqLinkCodes.normalize("abo121cd34"));
        assertEquals("V1234", QqLinkCodes.normalize("u1234"));
        assertEquals("", QqLinkCodes.normalize(null));
    }

    @Test
    void hashIsStableAcrossFormatting() {
        assertEquals(QqLinkCodes.hash("AB12CD34"), QqLinkCodes.hash("ab12-cd34"));
        assertNotEquals(QqLinkCodes.hash("AB12CD34"), QqLinkCodes.hash("AB12CD35"));
    }

    @Test
    void generatedCodesAreUniqueEnoughForTheBindWindow() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            codes.add(QqLinkCodes.generate(8));
        }
        assertFalse(codes.size() < 1_990, "unexpected collision rate: " + codes.size());
    }
}
