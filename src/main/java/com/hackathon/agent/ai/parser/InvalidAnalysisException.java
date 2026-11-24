package com.hackathon.agent.ai.parser;

import java.util.List;

/**
 * An answer that is not a valid analysis. The problems name fields only and never repeat the answer.
 */
public class InvalidAnalysisException extends RuntimeException {

    public InvalidAnalysisException(List<String> problems) {
        super("The model returned an invalid analysis: " + String.join("; ", problems));
    }
}
