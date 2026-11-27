package com.hackathon.agent.investigation.model;

/**
 * Value types of agent investigations.
 */
public final class InvestigationModel {

    private InvestigationModel() {
    }

    public enum TargetType {
        TASK_RUN, PIPELINE_RUN
    }

    public enum InvestigationStatus {
        PENDING, RUNNING, COMPLETED, FAILED;

        public boolean isActive() {
            return this == PENDING || this == RUNNING;
        }
    }

    /** What the model asked for in a step. */
    public enum StepAction {
        CALL_TOOL, FINAL_REPORT, INVALID
    }

    public enum StepStatus {
        /** The tool ran and returned a result. */
        SUCCEEDED,
        /** The tool ran but could not read what was asked for. */
        FAILED,
        /** The backend refused the request: unknown tool, invalid arguments, duplicate or exhausted budget. */
        REJECTED,
        /** The final report was accepted. */
        COMPLETED
    }
}
