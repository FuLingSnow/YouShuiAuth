package net.rafalohaki.veloauth.qq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generation, normalization and hashing of the short codes exchanged through QQ.
 *
 * <p>Codes are generated from Crockford's Base32 alphabet (no I, L, O, U), which keeps them
 * readable on a phone keyboard. Only the SHA-256 hash is persisted.
 */
public final class QqLinkCodes {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private QqLinkCodes() {
    }

    /**
     * Generates a code of {@code length} symbols. 8 symbols provide 40 bits of entropy.
     */
    public static String generate(int length) {
        if (length < 4 || length > 32) {
            throw new IllegalArgumentException("code length must be in range 4-32");
        }
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    /**
     * Normalizes user input so {@code ab12-cd34}, {@code AB12 CD34} and {@code aB12cD34}
     * all map to the same code. Ambiguous characters follow the Crockford decoding rules.
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char value = Character.toUpperCase(input.charAt(i));
            if (value == '-' || value == ' ' || value == '_') {
                continue;
            }
            switch (value) {
                case 'O' -> value = '0';
                case 'I', 'L' -> value = '1';
                case 'U' -> value = 'V';
                default -> {
                    // keep as-is
                }
            }
            builder.append(value);
        }
        return builder.toString();
    }

    public static String hash(String code) {
        String normalized = normalize(code);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JVM", e);
        }
    }

}
