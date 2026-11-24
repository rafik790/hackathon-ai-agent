package com.hackathon.agent.ai.model;

/**
 * Exclusive right of one worker to process an analysis until its lease ends.
 *
 * @param token    identifies this claim; results of an outdated claim are discarded
 * @param attempts processing attempts including this one
 */
public record AnalysisClaim(Long analysisId, Long evidenceId, String token, int attempts) {
}
