package com.hackathon.agent.evidence.sanitizer;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort removal of credentials from text. Repetition is bounded and only applied to character classes,
 * so untrusted log content cannot cause excessive backtracking or deep recursion.
 */
final class SecretRedactor {

    static final String MASK = "[REDACTED]";

    private static final String KEY = "(?:password|passwd|passphrase|[_-]pwd|secret|token|api[_-]?key"
            + "|access[_-]?key|private[_-]?key|account[_-]?key|credentials?)"
            + "(?:s|\\d{1,4}|key|value|id|[_-][\\w-]{1,32}+)?";
    private static final String VALUE = "(?!\\[REDACTED)"
            + "(?:\\\\?\"[^\"\\r\\n]{1,8192}+\"?|'[^'\\r\\n]{1,8192}+'?|[^\\s\"',;&]{1,8192}+)";
    private static final String KEY_LABEL = "[A-Z0-9 ]{0,40}PRIVATE KEY(?: BLOCK)?-----";

    private record Rule(Pattern pattern, String replacement) {
    }

    /** Order matters: specific formats first, generic key/value pairs last. */
    private static final List<Rule> RULES = List.of(
            // A complete private key, then keys cut off at the end or at the start of the retained text.
            rule("-----BEGIN " + KEY_LABEL + "[A-Za-z0-9+/=\\s\\\\:,.-]{0,16384}?-----END " + KEY_LABEL, MASK),
            rule("-----BEGIN " + KEY_LABEL + "[A-Za-z0-9+/=\\s\\\\:,.]{0,65536}+", MASK),
            rule("\\A[A-Za-z0-9+/=\\s]{0,65536}+-----END " + KEY_LABEL, MASK),
            rule("\\beyJ[\\w-]{8,8192}+\\.[\\w-]{8,8192}+\\.[\\w-]{0,8192}+", MASK),
            rule("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", MASK),
            rule("\\b(?:gh[pousr]_[A-Za-z0-9]{20,255}|github_pat_\\w{20,255}|glpat-[\\w-]{20,255})", MASK),
            rule("\\b(?:xox[abeoprs]-[\\w-]{10,255}|npm_[A-Za-z0-9]{30,255}|AIza[\\w-]{35}|sk-[\\w-]{20,255})", MASK),
            rule("(?i)(authorization\\\\?[\"']?\\s{0,4}[:=]\\s{0,4}\\\\?[\"']?"
                    + "(?:(?:basic|bearer|token|negotiate)\\s{1,4})?)(?!\\[REDACTED)[^\\s\"',;]{1,8192}+", "$1" + MASK),
            rule("(?i)\\b(bearer\\s{1,4})[A-Za-z0-9._~+/=-]{16,8192}+", "$1" + MASK),
            rule("(?i)\\b([a-z][a-z0-9+.-]{1,20}://[^\\s:/@\"']{0,255}:)[^\\s/@\"']{1,1024}+(?=@)", "$1" + MASK),
            // Docker registry configuration.
            rule("(?i)(\"auth\\\\?\"\\s{0,4}:\\s{0,4}\\\\?\")[^\"\\\\]{1,8192}+", "$1" + MASK),
            // Command line options such as "--password value".
            rule("(?i)(--?[\\w-]{0,40}?" + KEY + "[ \\t]{1,4})(?!-)" + VALUE, "$1" + MASK),
            rule("(?i)(" + KEY + "\\\\?[\"']?[ \\t]{0,4}[:=][ \\t]{0,4})" + VALUE, "$1" + MASK));

    record Result(String text, int redactions) {
    }

    private SecretRedactor() {
    }

    static Result redact(String text) {
        String current = text;
        int count = 0;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(current);
            StringBuilder out = null;
            while (matcher.find()) {
                if (out == null) {
                    out = new StringBuilder(current.length());
                }
                matcher.appendReplacement(out, rule.replacement());
                count++;
            }
            if (out != null) {
                current = matcher.appendTail(out).toString();
            }
        }
        return new Result(current, count);
    }

    private static Rule rule(String regex, String replacement) {
        return new Rule(Pattern.compile(regex), replacement);
    }
}
