package com.hackathon.agent.collector;

import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.service.TaskRunUpsertService;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class TaskRunCollector implements ResourceCollector {

    private final TektonResourceClient tektonClient;
    private final TektonResourceNormalizer normalizer;
    private final TaskRunUpsertService upsertService;

    public TaskRunCollector(TektonResourceClient tektonClient, TektonResourceNormalizer normalizer,
                            TaskRunUpsertService upsertService) {
        this.tektonClient = tektonClient;
        this.normalizer = normalizer;
        this.upsertService = upsertService;
    }

    @Override
    public String resourceKind() {
        return TektonResourceClient.TASK_RUN_KIND;
    }

    @Override
    public CollectionResult collect(String namespace) {
        List<GenericKubernetesResource> resources = tektonClient.listTaskRuns(namespace);
        List<TaskRunSnapshot> snapshots = resources.stream()
                .map(normalizer::toTaskRun)
                .flatMap(Optional::stream)
                .toList();
        return new CollectionResult(resourceKind(), namespace, resources.size(),
                resources.size() - snapshots.size(), upsertService.upsert(snapshots));
    }
}
