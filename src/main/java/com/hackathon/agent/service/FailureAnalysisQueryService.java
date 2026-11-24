package com.hackathon.agent.service;

import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.repository.FailureAnalysisRepository;
import com.hackathon.agent.ai.service.AnalysisPersistenceService;
import com.hackathon.agent.dto.FailureAnalysisResponse;
import com.hackathon.agent.dto.FailureAnalysisSummaryResponse;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.exception.ConflictException;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.mapper.FailureAnalysisMapper;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Serves analyses to the REST API and accepts manual analysis requests. Requests are only queued; the scheduler
 * processes them within its usual limits.
 */
@Service
public class FailureAnalysisQueryService {

    static final Set<String> SORTABLE = Set.of("id", "analysisStatus", "failureCategory", "severity", "confidence",
            "analysedAt", "createdAt", "updatedAt");

    private final FailureAnalysisRepository repository;
    private final AnalysisPersistenceService persistence;

    public FailureAnalysisQueryService(FailureAnalysisRepository repository, AnalysisPersistenceService persistence) {
        this.repository = repository;
        this.persistence = persistence;
    }

    public PageResponse<FailureAnalysisSummaryResponse> find(FailureCategory category, Severity severity,
                                                             AnalysisStatus status, Pageable pageable) {
        SortValidator.validate(pageable, SORTABLE);
        return PageResponse.from(repository.search(category, severity, status, pageable),
                FailureAnalysisMapper::toSummary);
    }

    public FailureAnalysisResponse get(long id) {
        return repository.findView(id).map(FailureAnalysisMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("FailureAnalysis", id));
    }

    /** Newest analysis of the last attempt of a TaskRun. */
    public FailureAnalysisResponse latestOfTaskRun(long taskRunId) {
        return repository.findViewsByTaskRun(taskRunId, Limit.of(1)).stream().findFirst()
                .map(FailureAnalysisMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("FailureAnalysis for TaskRun", taskRunId));
    }

    public FailureAnalysisResponse request(long evidenceId, boolean force) {
        try {
            return get(persistence.request(evidenceId, force));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An analysis of evidence " + evidenceId + " was requested at the same time");
        }
    }
}
