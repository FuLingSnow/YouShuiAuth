package io.github.fulingsnow.youshuiauth.platform.quarantine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuarantinePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"login", "/login", "LOGIN", "/Login hunter2", "reg",
            "l", "totp", "ysauth stats", "/2fa setup", "qqbind", "logout"})
    void allowedCommandsPassRegardlessOfCaseSlashesAndArguments(String raw) {
        assertTrue(QuarantinePolicy.isAllowedCommand(raw), raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"op", "/op someone", "gamemode 1", "tp", "give",
            "unregister", "setpassword2", "loginx"})
    void otherCommandsAreBlocked(String raw) {
        assertFalse(QuarantinePolicy.isAllowedCommand(raw), raw);
    }

    @Test
    void namespacedCommandResolvesToItsBareName() {
        assertTrue(QuarantinePolicy.isAllowedCommand("youshuiauth:login"));
        assertTrue(QuarantinePolicy.isAllowedCommand("/youshuiauth:2fa"));
        assertFalse(QuarantinePolicy.isAllowedCommand("minecraft:op"));
    }

    @Test
    void blankInputIsNotAllowed() {
        assertFalse(QuarantinePolicy.isAllowedCommand(null));
        assertFalse(QuarantinePolicy.isAllowedCommand(""));
        assertFalse(QuarantinePolicy.isAllowedCommand("   "));

        assertFalse(QuarantinePolicy.isAllowedCommand("/"));
    }

    @Test
    void sameBlockIgnoresSubBlockMovement() {
        assertTrue(QuarantinePolicy.isSameBlock(10.1, 64.0, -3.9, 10.9, 64.99, -3.01));
        assertFalse(QuarantinePolicy.isSameBlock(10.0, 64.0, 0.0, 11.0, 64.0, 0.0));
        assertFalse(QuarantinePolicy.isSameBlock(10.0, 64.0, 0.0, 10.0, 65.0, 0.0));
        assertFalse(QuarantinePolicy.isSameBlock(10.0, 64.0, 0.0, 10.0, 64.0, -1.0));
    }

    @Test
    void negativeCoordinatesUseFloorNotTruncation() {

        assertTrue(QuarantinePolicy.isSameBlock(-0.5, 0, 0, -0.1, 0, 0));
        assertFalse(QuarantinePolicy.isSameBlock(-0.5, 0, 0, 0.5, 0, 0));
    }

    @Test
    void anchorToleranceComparesSquaredDistance() {

        assertFalse(QuarantinePolicy.isOutsideAnchor(0.9, 0, 0, 1.0));
        assertTrue(QuarantinePolicy.isOutsideAnchor(1.5, 0, 0, 1.0));

        assertTrue(QuarantinePolicy.isOutsideAnchor(0.7, 0.7, 0.7, 1.0));

        assertFalse(QuarantinePolicy.isOutsideAnchor(0.5, 0.5, 0.5, 1.0));
    }
}
