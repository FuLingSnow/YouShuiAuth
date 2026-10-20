package net.rafalohaki.veloauth.qq;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqLoginChallengesTest {

    private static final UUID UUID_VALUE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void codeIsSixDigitsAndConsumedExactlyOnce() {
        QqLoginChallenges challenges = new QqLoginChallenges(120);

        Optional<String> code = challenges.issue(UUID_VALUE, null);

        assertTrue(code.isPresent());
        assertTrue(code.get().matches("\\d{6}"));
        Optional<QqLoginChallenges.Challenge> first = challenges.consume(code.get());
        assertTrue(first.isPresent());
        assertEquals(UUID_VALUE, first.get().uuid());
        assertFalse(first.get().isExpired(System.currentTimeMillis()));
        assertFalse(challenges.consume(code.get()).isPresent());
    }

    @Test
    void blankCodesAreRejected() {
        QqLoginChallenges challenges = new QqLoginChallenges(120);
        assertFalse(challenges.consume(null).isPresent());
        assertFalse(challenges.consume("   ").isPresent());
        assertFalse(challenges.consume("123456").isPresent());
    }

    @Test
    void reissuesTheSameLiveCodeForTheSameConnection() {
        QqLoginChallenges challenges = new QqLoginChallenges(120);
        String first = challenges.issue(UUID_VALUE, null).orElseThrow();
        String second = challenges.issue(UUID_VALUE, null).orElseThrow();
        assertEquals(first, second);
    }

    @Test
    void consumedCodeIsReplacedAndTheOldOneStopsWorking() {
        QqLoginChallenges challenges = new QqLoginChallenges(120);
        String first = challenges.issue(UUID_VALUE, null).orElseThrow();
        assertTrue(challenges.consume(first).isPresent());

        String second = challenges.issue(UUID_VALUE, null).orElseThrow();
        assertNotEquals(first, second);
        assertFalse(challenges.consume(first).isPresent());
    }
}
