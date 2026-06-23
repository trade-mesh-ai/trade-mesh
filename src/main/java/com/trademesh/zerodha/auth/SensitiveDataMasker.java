package com.trademesh.zerodha.auth;

/**
 * Utility class for masking sensitive data in logs.
 * <p>
 * Prevents accidental exposure of tokens, passwords, and other sensitive values
 * while still allowing partial visibility for debugging purposes.
 */
public class SensitiveDataMasker {

    private static final String REDACTED = "[REDACTED]";
    private static final String ELLIPSIS = "...";

    private SensitiveDataMasker() {
        // Utility class, no instantiation
    }

    /**
     * Masks a token by showing only the last 4 characters.
     *
     * @param token the token to mask
     * @return masked token in format "...abc123" or "[REDACTED]" if null/too short
     */
    public static String maskToken(String token) {
        return maskValue(token, 4);
    }

    /**
     * Masks a request token by showing only the last 4 characters.
     *
     * @param requestToken the request token to mask
     * @return masked token in format "...abc123" or "[REDACTED]" if null/too short
     */
    public static String maskRequestToken(String requestToken) {
        return maskValue(requestToken, 4);
    }

    /**
     * Generic method to mask any sensitive value.
     *
     * @param value     the value to mask
     * @param showChars number of characters to show from the end
     * @return masked value or "[REDACTED]" if null or shorter than showChars
     */
    public static String maskValue(String value, int showChars) {
        if (value == null || value.length() <= showChars) {
            return REDACTED;
        }
        return ELLIPSIS + value.substring(value.length() - showChars);
    }
}
