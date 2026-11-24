package com.hackathon.agent.evidence.sanitizer;

import com.hackathon.agent.evidence.model.SanitizedLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Turns raw log bytes and status messages into text that is safe to store: valid UTF-8 without NUL characters,
 * credentials redacted, size bounded. Redaction is best effort and cannot recognize arbitrary secrets.
 */
@Component
public class LogSanitizer {

    /**
     * Extra bytes to keep in front of the retained tail. They give redaction the context of a secret that
     * starts just before the cut, and are dropped again afterwards.
     */
    public static final int CONTEXT_BYTES = 8192;

    /**
     * @param raw        the last bytes of the log, at most {@code maxBytes + CONTEXT_BYTES}
     * @param overflowed whether earlier bytes were dropped before {@code raw}
     * @param maxBytes   maximum UTF-8 size of the result
     */
    public SanitizedLog sanitizeLog(byte[] raw, boolean overflowed, int maxBytes) {
        int start = overflowed ? startOfRetainedText(raw) : 0;
        String text = clean(new String(raw, start, raw.length - start, StandardCharsets.UTF_8));
        SecretRedactor.Result redacted = SecretRedactor.redact(text);
        String content = lastBytes(redacted.text(), maxBytes);
        boolean truncated = overflowed || content.length() < redacted.text().length();
        return toLog(content, truncated, redacted.redactions());
    }

    /** Shortens an already sanitized log to its last {@code maxBytes} bytes. */
    public SanitizedLog shorten(SanitizedLog log, int maxBytes) {
        if (log.storedBytes() <= maxBytes) {
            return log;
        }
        return toLog(lastBytes(log.content(), maxBytes), true, log.redactions());
    }

    /** Sanitizes a status message; returns null for blank input. */
    public String sanitizeMessage(String message, int maxLength) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = SecretRedactor.redact(clean(message)).text();
        if (text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }

    /**
     * After an overflow the first line is incomplete and may start inside a secret. It is dropped; when no line
     * break is found within the context bytes, the context bytes themselves are dropped.
     */
    private static int startOfRetainedText(byte[] raw) {
        int limit = Math.min(CONTEXT_BYTES, raw.length);
        for (int i = 0; i < limit; i++) {
            if (raw[i] == '\n') {
                return i + 1;
            }
        }
        return limit;
    }

    private static String clean(String text) {
        return text.replace("\0", "");
    }

    /** Last part of the text that fits into {@code maxBytes} UTF-8 bytes, cut on a character boundary. */
    private static String lastBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static SanitizedLog toLog(String content, boolean truncated, int redactions) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new SanitizedLog(content, bytes.length, truncated, sha256(bytes), redactions);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
