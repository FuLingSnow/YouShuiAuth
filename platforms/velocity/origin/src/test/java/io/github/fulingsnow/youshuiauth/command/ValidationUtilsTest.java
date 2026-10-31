package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.VelocityPlayerAddressUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ValidationUtils.
 * Tests validation logic, IP extraction, and component creation.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S2068", "java:S100"})
class ValidationUtilsTest {

    @Mock
    private Player mockPlayer;

    private Settings mockSettings;
    private Messages messages;

    @BeforeEach
    void setUp() {
        mockSettings = new TestValidationSettings(java.nio.file.Path.of(".test-validation"), 6, 32);
        messages = new Messages();
        messages.setLanguage("en");
    }

    @Test
    @SuppressWarnings("java:S2068")
    void testValidatePassword_ValidPassword_ReturnsSuccess() {

        String validPassword = "testPassword123";

        ValidationUtils.ValidationResult result = ValidationUtils.validatePassword(validPassword, mockSettings, messages);

        assertTrue(result.valid());
        assertNull(result.getErrorMessage());
    }

    @Test
    void testValidatePassword_EmptyPassword_ReturnsError() {
        ValidationUtils.ValidationResult result = ValidationUtils.validatePassword("", mockSettings, messages);

        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.empty"), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_NullPassword_ReturnsError() {
        ValidationUtils.ValidationResult result = ValidationUtils.validatePassword(null, mockSettings, messages);

        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.empty"), result.getErrorMessage());
    }

    @Test
    @SuppressWarnings("java:S2068")
    void testValidatePassword_TooShort_ReturnsError() {

        String shortPassword = "test";

        ValidationUtils.ValidationResult result = ValidationUtils.validatePassword(shortPassword, mockSettings, messages);

        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.too_short", 6), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_TooLong_ReturnsError() {

        String longPassword = "a".repeat(33);

        ValidationUtils.ValidationResult result = ValidationUtils.validatePassword(longPassword, mockSettings, messages);

        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.too_long", 32), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_Utf8TooLong_ReturnsLocalizedError() {
        String utf8HeavyPassword = "你".repeat(25);

        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword(utf8HeavyPassword, mockSettings, messages);

        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.utf8_too_long", 75), result.getErrorMessage());
        assertFalse("validation.password.utf8_too_long".equals(result.getErrorMessage()));
    }

    @Test
    void testValidatePassword_PolicyDisabled_AcceptsAnyShape() {

        Settings noPolicy = new TestValidationSettings(java.nio.file.Path.of(".test"), 6, 32);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("alllowercase", noPolicy, messages);
        assertTrue(result.valid());
    }

    @Test
    void testValidatePassword_PolicyRequiresDigit_RejectsAlphaOnly() {
        Settings policy = new TestValidationSettings(
                java.nio.file.Path.of(".test"), 6, 32, 1, 0, 0, 0);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("noDigitsHere", policy, messages);
        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.needs_digit", 1), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_PolicyRequiresUpper_RejectsLowerOnly() {
        Settings policy = new TestValidationSettings(
                java.nio.file.Path.of(".test"), 6, 32, 0, 1, 0, 0);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("alllower123", policy, messages);
        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.needs_upper", 1), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_PolicyRequiresLower_RejectsUpperOnly() {
        Settings policy = new TestValidationSettings(
                java.nio.file.Path.of(".test"), 6, 32, 0, 0, 1, 0);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("ALLUPPER123", policy, messages);
        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.needs_lower", 1), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_PolicyRequiresSpecial_RejectsAlnumOnly() {
        Settings policy = new TestValidationSettings(
                java.nio.file.Path.of(".test"), 6, 32, 0, 0, 0, 2);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("OnlyAlnum123", policy, messages);
        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.needs_special", 2), result.getErrorMessage());
    }

    @Test
    void testValidatePassword_PolicyAllSatisfied_ReturnsSuccess() {
        Settings policy = new TestValidationSettings(
                java.nio.file.Path.of(".test"), 6, 32, 1, 1, 1, 1);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("Strong#Pass1", policy, messages);
        assertTrue(result.valid());
        assertNull(result.getErrorMessage());
    }

    @Test
    void testValidatePassword_PolicyChecked_AfterLengthLimits() {

        Settings policy = new TestValidationSettings(
                java.nio.file.Path.of(".test"), 10, 32, 5, 0, 0, 0);
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword("short", policy, messages);
        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.too_short", 10), result.getErrorMessage());
    }

    @Test
    @SuppressWarnings("java:S2068")
    void testValidatePasswordMatch_MatchingPasswords_ReturnsSuccess() {
        String password = "test123";
        String confirmPassword = "test123";

        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePasswordMatch(password, confirmPassword, messages);

        assertTrue(result.valid());
        assertNull(result.getErrorMessage());
    }

    @Test
    @SuppressWarnings("java:S2068")
    void testValidatePasswordMatch_NonMatchingPasswords_ReturnsError() {
        String password = "testPassword123";
        String confirmPassword = "differentPassword";

        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePasswordMatch(password, confirmPassword, messages);

        assertFalse(result.valid());
        assertEquals(messages.get("validation.password.mismatch"), result.getErrorMessage());
    }

    @Test
    @SuppressWarnings("java:S5164")
    void testGetPlayerIp_ValidInetSocketAddress_ReturnsIp() throws java.net.UnknownHostException {
        String expectedIp = "127.0.0.1";
        InetAddress address = InetAddress.getByName(expectedIp);
        InetSocketAddress socketAddress = new InetSocketAddress(address, 25565);
        when(mockPlayer.getRemoteAddress()).thenReturn(socketAddress);

        String result = VelocityPlayerAddressUtils.getPlayerIp(mockPlayer);

        assertEquals(expectedIp, result);
    }

    @Test
    void testGetPlayerAddress_ValidInetSocketAddress_ReturnsInetAddress() throws java.net.UnknownHostException {
        InetAddress address = InetAddress.getByName("192.168.1.2");
        @SuppressWarnings("java:S5164")
        InetSocketAddress socketAddress = new InetSocketAddress(address, 25565);
        when(mockPlayer.getRemoteAddress()).thenReturn(socketAddress);

        InetAddress result = VelocityPlayerAddressUtils.getPlayerAddress(mockPlayer);

        assertEquals(address, result);
    }

    @Test
    void testValidateArgumentCount_CorrectCount_ReturnsSuccess() {
        String[] args = {"arg1", "arg2"};
        String usage = "Użycie: /command <arg1> <arg2>";

        ValidationUtils.ValidationResult result = ValidationUtils.validateArgumentCount(args, 2, usage);

        assertTrue(result.valid());
        assertNull(result.getErrorMessage());
    }

    @Test
    void testValidateArgumentCount_IncorrectCount_ReturnsError() {
        String[] args = {"arg1"};
        String usage = "Użycie: /command <arg1> <arg2>";

        ValidationUtils.ValidationResult result = ValidationUtils.validateArgumentCount(args, 2, usage);

        assertFalse(result.valid());
        assertEquals(usage, result.getErrorMessage());
    }

    @Test
    void testCreateErrorComponent_CreatesRedComponent() {
        String message = "Error message";

        var component = ValidationUtils.createErrorComponent(message);

        assertNotNull(component);
    }

    @Test
    void testCreateSuccessComponent_CreatesGreenComponent() {
        String message = "Success message";

        var component = ValidationUtils.createSuccessComponent(message);

        assertNotNull(component);
    }

    @Test
    void testCreateWarningComponent_CreatesYellowComponent() {
        String message = "Warning message";

        var component = ValidationUtils.createWarningComponent(message);

        assertNotNull(component);
    }

    @Test
    void testValidationResult_FactoryMethods() {

        ValidationUtils.ValidationResult success = ValidationUtils.ValidationResult.success();
        assertTrue(success.valid());
        assertNull(success.getErrorMessage());

        String errorMessage = "Test error";
        ValidationUtils.ValidationResult error = ValidationUtils.ValidationResult.error(errorMessage);
        assertFalse(error.valid());
        assertEquals(errorMessage, error.getErrorMessage());
    }
}
