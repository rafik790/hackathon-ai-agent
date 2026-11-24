package com.hackathon.agent.evidence.model;

/**
 * Log text that is safe to persist.
 *
 * @param storedBytes UTF-8 length of {@code content}
 * @param sha256      hex digest of the UTF-8 bytes of {@code content}
 */
public record SanitizedLog(String content, int storedBytes, boolean truncated, String sha256, int redactions) {

    public SanitizedLog asTruncated() {
        return truncated ? this : new SanitizedLog(content, storedBytes, true, sha256, redactions);
    }
}
