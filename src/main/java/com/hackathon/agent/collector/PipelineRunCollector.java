package com.hackathon.agent.collector;

import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.service.PipelineRunUpsertService;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class PipelineRunCollector implements ResourceCollector {

    private final TektonResourceClient tektonClient;
    private final TektonResourceNormalizer normalizer;
    private final PipelineRunUpsertService upsertService;

    public PipelineRunCollector(TektonResourceClient tektonClient, TektonResourceNormalizer normalizer,
                                PipelineRunUpsertService upsertService) {
        this.tektonClient = tektonClient;
        this.normalizer = normalizer;
        this.upsertService = upsertService;
    }

    @Override
    public String resourceKind() {
        return TektonResourceClient.PIPELINE_RUN_KIND;
    }

    @Override
    public CollectionResult collect(String namespace) {
        List<GenericKubernetesResource> resources = tektonClient.listPipelineRuns(namespace);
        List<PipelineRunSnapshot> snapshots = resources.stream()
                .map(normalizer::toPipelineRun)
                .flatMap(Optional::stream)
                .toList();
        return new CollectionResult(resourceKind(), namespace, resources.size(),
                resources.size() - snapshots.size(), upsertService.upsert(snapshots));
    }
}
