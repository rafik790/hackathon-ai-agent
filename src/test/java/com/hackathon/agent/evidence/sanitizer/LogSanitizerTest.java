package com.hackathon.agent.evidence.sanitizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.hackathon.agent.evidence.model.SanitizedLog;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogSanitizerTest {

    private static final int MAX = 65536;

    private final LogSanitizer sanitizer = new LogSanitizer();

    @ParameterizedTest
    @ValueSource(strings = {
            "password=S3cr3tValue",
            "DB_PASSWORD: S3cr3tValue",
            "{\"password\": \"S3cr3tValue\"}",
            "{\\\"clientSecret\\\":\\\"S3cr3tValue\\\"}",
            "api_key='S3cr3tValue'",
            "export AWS_SECRET_ACCESS_KEY=S3cr3tValue",
            "curl https://x.example/?access_token=S3cr3tValue&a=b",
            "Authorization: Basic S3cr3tValue",
            "\"authorization\": \"Bearer S3cr3tValue\"",
            "token Bearer S3cr3tValueS3cr3tValue",
            "git clone https://user:S3cr3tValue@git.example/repo.git",
            "login --username me --password S3cr3tValue",
            "{\"auths\": {\"ghcr.io\": {\"auth\": \"S3cr3tValue\"}}}",
            "postgres://app:S3cr3tValue@db:5432/x"})
    void redactsCredentials(String line) {
        SanitizedLog log = sanitize("before\n" + line + "\nafter");

        assertThat(log.content()).doesNotContain("S3cr3tValue").contains("[REDACTED]").startsWith("before\n")
                .endsWith("\nafter");
        assertThat(log.redactions()).isPositive();
    }

    @Test
    void redactsWellKnownTokenFormats() {
        // Assembled at runtime so that no literal looks like a real credential.
        String github = "ghp_" + "a".repeat(36);
        String aws = "AKIA" + "B".repeat(16);
        String jwt = "eyJ" + "a".repeat(20) + "." + "b".repeat(20) + "." + "c".repeat(20);

        SanitizedLog log = sanitize("x " + github + " y " + aws + " z " + jwt);

        assertThat(log.content()).isEqualTo("x [REDACTED] y [REDACTED] z [REDACTED]");
        assertThat(log.redactions()).isEqualTo(3);
    }

    @Test
    void redactsCompleteAndPartialPrivateKeys() {
        String begin = "-----BEGIN RSA " + "PRIVATE KEY-----\n";
        String end = "-----END RSA " + "PRIVATE KEY-----\n";
        String body = "MIIabc+/123\nMIIdef456==\n";

        assertThat(sanitize("a\n" + begin + body + end + "b").content()).doesNotContain("MII").endsWith("b");
        assertThat(sanitize("a\n" + begin + body).content()).doesNotContain("MII");
        assertThat(sanitize(body + end + "b").content()).doesNotContain("MII").endsWith("b");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Tests run: 12, Failures: 1",
            "java.lang.NullPointerException: Cannot invoke \"String.length()\"",
            "PWD=/workspace/source",
            "npm ERR! code ELIFECYCLE",
            "GET http://localhost:8080/api/v1/pipelines 200"})
    void keepsOrdinaryLogLines(String line) {
        assertThat(sanitize(line).content()).isEqualTo(line);
    }

    @Test
    void removesNulAndReplacesInvalidUtf8() {
        byte[] raw = {'a', 0, 'b', (byte) 0xC3, (byte) 0x28, (byte) 0xFF, 'c'};

        SanitizedLog log = sanitizer.sanitizeLog(raw, false, MAX);

        assertThat(log.content()).isEqualTo("ab\uFFFD(\uFFFDc");
        assertThat(log.storedBytes()).isEqualTo(log.content().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void sha256CoversTheStoredText() {
        assertThat(sanitize("abc").sha256())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(sanitize("a\0bc").sha256()).isEqualTo(sanitize("abc").sha256());
        assertThat(sanitize("password=one").sha256()).isEqualTo(sanitize("password=two").sha256());
    }

    @Test
    void keepsTheEndOfLongLogs() {
        SanitizedLog log = sanitize("start" + "x".repeat(MAX) + "the error");

        assertThat(log.storedBytes()).isEqualTo(MAX);
        assertThat(log.truncated()).isTrue();
        assertThat(log.content()).endsWith("the error").doesNotContain("start");
    }

    @Test
    void shortLogIsNotTruncated() {
        assertThat(sanitize("short").truncated()).isFalse();
    }

    @Test
    void truncationNeverSplitsACharacter() {
        SanitizedLog log = sanitizer.sanitizeLog("é€😀😀".getBytes(StandardCharsets.UTF_8), false, 6);

        assertThat(log.content()).isEqualTo("😀");
        assertThat(log.storedBytes()).isEqualTo(4);
    }

    @Test
    void secretCutByTheBufferIsNotStored() {
        // The buffer starts in the middle of a secret: the incomplete first line must be dropped.
        String raw = "retValue-remainder\nnext line\n" + "y".repeat(100);

        SanitizedLog log = sanitizer.sanitizeLog(raw.getBytes(StandardCharsets.UTF_8), true, MAX);

        assertThat(log.content()).startsWith("next line").doesNotContain("remainder");
        assertThat(log.truncated()).isTrue();
    }

    @Test
    void shortenKeepsTheEndAndUpdatesTheHash() {
        SanitizedLog log = sanitize("0123456789");
        SanitizedLog shortened = sanitizer.shorten(log, 4);

        assertThat(shortened.content()).isEqualTo("6789");
        assertThat(shortened.storedBytes()).isEqualTo(4);
        assertThat(shortened.truncated()).isTrue();
        assertThat(shortened.sha256()).isEqualTo(sanitize("6789").sha256()).isNotEqualTo(log.sha256());
        assertThat(sanitizer.shorten(log, 10)).isSameAs(log);
    }

    @Test
    void sanitizesMessages() {
        assertThat(sanitizer.sanitizeMessage("a\0b token=abc123", 100)).isEqualTo("ab token=[REDACTED]");
        assertThat(sanitizer.sanitizeMessage("x".repeat(50), 10)).hasSize(10);
        assertThat(sanitizer.sanitizeMessage("aaaa😀", 5)).isEqualTo("aaaa");
        assertThat(sanitizer.sanitizeMessage("  ", 10)).isNull();
        assertThat(sanitizer.sanitizeMessage(null, 10)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "-", "password", "token=", "--secret ", "eyJ", "-----BEGIN ", "x://", "\"", "\\\"", " "})
    void hostileInputIsProcessedQuickly(String unit) {
        byte[] raw = unit.repeat((MAX + LogSanitizer.CONTEXT_BYTES) / unit.length()).getBytes(StandardCharsets.UTF_8);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> sanitizer.sanitizeLog(raw, true, MAX));
    }

    private SanitizedLog sanitize(String text) {
        return sanitizer.sanitizeLog(text.getBytes(StandardCharsets.UTF_8), false, MAX);
    }
}
