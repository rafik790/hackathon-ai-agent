package com.hackathon.agent.resolution.model;

import java.util.Collection;
import java.util.List;

/**
 * The resolution of a PipelineRun, derived from the resolutions of its failed TaskRuns. It is never stored, so it
 * cannot disagree with them: every read calculates it from the TaskRun records.
 *
 * <ul>
 *   <li>No failed TaskRuns: there is nothing to resolve (null).</li>
 *   <li>All failed TaskRuns RESOLVED: RESOLVED, as soon as the last one is resolved.</li>
 *   <li>Otherwise the most pressing status among the unresolved ones, in this order: REOPENED, FIX_IN_PROGRESS,
 *       INVESTIGATING, OPEN. A failed TaskRun without a record counts as OPEN.</li>
 * </ul>
 * So a PipelineRun is never RESOLVED while one of its failed TaskRuns is unresolved or reopened.
 */
public final class ResolutionAggregation {

    /** Most pressing first; RESOLVED only applies when every failed TaskRun is resolved. */
    static final List<ResolutionStatus> PRIORITY = List.of(
            ResolutionStatus.REOPENED, ResolutionStatus.FIX_IN_PROGRESS, ResolutionStatus.INVESTIGATING, ResolutionStatus.OPEN);

    private ResolutionAggregation() {
    }

    /** @param taskRunStatuses resolution of each failed TaskRun, OPEN for one without a record */
    public static ResolutionStatus aggregate(Collection<ResolutionStatus> taskRunStatuses) {
        if (taskRunStatuses.isEmpty()) {
            return null;
        }
        for (ResolutionStatus status : PRIORITY) {
            if (taskRunStatuses.contains(status)) {
                return status;
            }
        }
        return ResolutionStatus.RESOLVED;
    }
}
