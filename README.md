# CI/CD Reliability AI Agent — Backend (Phase 1)

Spring Boot service that monitors **Tekton Pipelines** on Kubernetes, stores the execution history of
every `PipelineRun` and `TaskRun` in PostgreSQL and exposes reliability metrics through a REST API for
the separate Angular dashboard.

Phase 1 is deliberately non-AI: it builds the reliable data foundation (collection, persistence,
metrics, API) that later phases use for failure analysis, trend detection and recommendations.

| Repository | Purpose |
|---|---|
| [hackathon-ai-agent](https://github.com/rafik790/hackathon-ai-agent) | This backend |
| [hackathon-ai-demo-app](https://github.com/rafik790/hackathon-ai-demo-app) | Demo application built by Tekton |
| [hackathon-ai-frontend](https://github.com/rafik790/hackathon-ai-frontend) | Angular + Angular Material dashboard |

---

## 1. Overview

* Polls the Kubernetes API every 15 s (configurable) for Tekton `PipelineRun`s and `TaskRun`s in the
  configured namespaces.
* Normalizes status (`RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`), timestamps, duration and failure
  reason/message.
* Upserts records by Kubernetes UID: re-discovering the same resource never creates duplicates, and a
  status change updates the existing row.
* Calculates success/failure rates, average and P95 durations and task failure rate over any time window
  from the **persisted** history (runs remain available after Tekton prunes them from the cluster).
* Serves paginated, filterable REST endpoints, OpenAPI docs and Actuator health.

## 2. Architecture

```
                 ┌───────────────────────── hackathon-ai-agent ─────────────────────────┐
                 │                                                                       │
 Kubernetes API  │  kubernetes/            collector/                 service/           │
 (Tekton CRDs) ◄─┼─ TektonResourceClient ◄─ PipelineRunCollector ──►  *UpsertService ──┐ │
   list (paged)  │   (Fabric8, read-only)   TaskRunCollector          (idempotent)     │ │
                 │                          TektonResourceNormalizer                   ▼ │
                 │  scheduler/              TektonStatusMapper         repository/  PostgreSQL
                 │  TektonCollectionScheduler ─► TektonCollectionService  (JPA +     (Flyway)
                 │   (@Scheduled fixedDelay)     (per-namespace error     metrics       ▲ │
                 │                                isolation, no overlap)  queries) ────┘ │
                 │                                                          ▲            │
 Angular UI ─────┼─► controller/ ──► service/ (DashboardService, ReliabilityMetricsService,│
   (CORS)        │   REST + DTOs        ExecutionTrendService, *QueryService) ───────────┘ │
                 │   GlobalExceptionHandler                                               │
                 └───────────────────────────────────────────────────────────────────────┘
```

Package layout (`com.hackathon.agent`):

| Package | Responsibility |
|---|---|
| `config` | Typed configuration properties, Kubernetes client, CORS, OpenAPI, scheduling, clock |
| `kubernetes` | The **only** code talking to Kubernetes: auth-mode config factory, paginated Tekton list calls, error wrapping |
| `collector` | Normalization of raw resources, status mapping, duration rules, `ResourceCollector` implementations, cycle orchestration, status tracking, health indicator |
| `scheduler` | `@Scheduled` trigger (`fixedDelay` ⇒ cycles never overlap) |
| `service` | Idempotent upserts, query services, time-range resolution, reliability metrics, trends |
| `repository` | Spring Data repositories, specifications, aggregate metric queries |
| `entity` | JPA entities (`PipelineRunEntity`, `TaskRunEntity`, shared `ExecutionEntity` columns) |
| `dto` / `mapper` | API response records and entity → DTO mapping (entities never leave the service layer) |
| `controller` | Thin REST controllers (no business logic, no Kubernetes access) |
| `exception` | Domain exceptions + `GlobalExceptionHandler` producing a uniform `ErrorResponse` |
| `enums` | `ExecutionStatus`, `KubernetesAuthMode`, `TimeRangePreset`, `TrendGranularity` |

### Key design decisions

* **Fabric8 generic resources** instead of Tekton model classes: no extra dependency, Tekton API
  group/version configurable (`v1` by default; `v1beta1` for old Tekton releases).
* **Polling, not informers** (Phase 1 requirement). List calls are paginated (`limit`/`continue`) and
  optionally narrowed with a label selector. The collector is a list of `ResourceCollector` beans, so
  new resource kinds (Pods, Events…) can be added without touching the cycle logic.
* **Idempotent upserts**: batch lookup by UID → create, update only changed columns, or skip. A unique
  constraint on the UID backs this up in the database.
* **No false failures**: missing/incomplete status maps to `RUNNING`/`UNKNOWN`, never `FAILED`; and a
  record that reached `SUCCEEDED`/`FAILED` is never downgraded by a later incomplete response.
* **Cancellations and timeouts count as `FAILED`** (Tekton reports them as `Succeeded=False`); the exact
  reason (`PipelineRunCancelled`, `PipelineRunTimeout`, …) is stored in `failureReason`.
* **Rates over completed runs**: success/failure rate = succeeded (failed) / (succeeded + failed), so
  in-flight runs don't dilute them. Runs are assigned to a time window by `startTime`.
* **P95** uses the nearest-rank method computed in the database (`ORDER BY duration OFFSET k LIMIT 1`).
* **Collector failure ≠ app failure**: a Kubernetes outage is logged, reported in
  `/api/v1/collector/status` and as `DEGRADED` (HTTP 200) in `/actuator/health`, while the API keeps
  serving history.

## 3. Technology stack

Java 21 · Spring Boot 4.1.3 (Web MVC, Data JPA, Validation, Actuator, Flyway) · Gradle 9.8 (Groovy DSL)
· PostgreSQL · Fabric8 Kubernetes Client 7.9 · springdoc-openapi 3.2 · JUnit 5, Mockito, AssertJ,
Fabric8 mock server, H2 (tests), Testcontainers.

## 4. Prerequisites

* JDK 17+ to run Gradle. The build uses a **Java 21 toolchain**; if no JDK 21 is installed Gradle
  downloads one automatically (foojay resolver).
* PostgreSQL 14+ (local install or container).
* Access to a Kubernetes cluster with Tekton Pipelines (kubeconfig for local development).
* Docker (optional) for building the image and running the Testcontainers test.

## 5. Local development setup

```bash
git clone https://github.com/rafik790/hackathon-ai-agent.git
cd hackathon-ai-agent
cp .env.example .env        # fill in values; .env is git-ignored
```

Export the variables (or configure them in your IDE run configuration), then run with the `local`
profile, which uses your kubeconfig:

```bash
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

* API: <http://localhost:8080/api/v1/dashboard/summary>
* Swagger UI: <http://localhost:8080/swagger-ui.html> · OpenAPI JSON: <http://localhost:8080/v3/api-docs>
* Health: <http://localhost:8080/actuator/health>

To run the API without touching Kubernetes, set `MONITORING_ENABLED=false`.

## 6. PostgreSQL setup

```sql
CREATE DATABASE cicd_agent;
CREATE USER cicd_agent WITH PASSWORD '<choose-a-password>';
GRANT ALL PRIVILEGES ON DATABASE cicd_agent TO cicd_agent;
\c cicd_agent
GRANT ALL ON SCHEMA public TO cicd_agent;
```

Or with Docker:

```bash
docker run -d --name cicd-agent-db -p 5432:5432 -e POSTGRES_DB=cicd_agent -e POSTGRES_USER=cicd_agent -e POSTGRES_PASSWORD=<choose-a-password> postgres:17-alpine
```

Flyway creates and migrates the schema at startup (`src/main/resources/db/migration`). Hibernate runs
with `ddl-auto=validate` and never changes the schema.

### Schema summary

`pipeline_run`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint identity | PK, used in API URLs |
| `pipeline_run_uid` | varchar(64) | Kubernetes UID, **unique** |
| `pipeline_run_name`, `namespace`, `pipeline_name` | varchar | `pipeline_name` from label `tekton.dev/pipeline`, fallback `spec.pipelineRef.name` |
| `status` | varchar(16) | `RUNNING` / `SUCCEEDED` / `FAILED` / `UNKNOWN` (check constraint) |
| `start_time`, `completion_time` | timestamptz | from `status.startTime` / `status.completionTime` |
| `duration_ms` | bigint | completion − start; null while running |
| `failure_reason`, `failure_message` | varchar(255) / varchar(4000) | `Succeeded` condition reason/message, only for failures |
| `resource_created_at` | timestamptz | Kubernetes `metadata.creationTimestamp` |
| `created_at`, `updated_at` | timestamptz | record bookkeeping (UTC) |

`task_run` has the same execution columns plus `task_run_uid` (**unique**), `task_run_name`,
`pipeline_run_uid` (owner reference), `pipeline_run_name` and `task_name` (label
`tekton.dev/pipelineTask`, fallback `tekton.dev/task` / `spec.taskRef.name`).

Indexes: namespace, pipeline name, status and start time on both tables; plus
`task_run(pipeline_run_uid)`, `task_run(namespace, pipeline_run_name)` and
`pipeline_run(namespace, pipeline_run_name)`. The unique constraints index the UIDs.

## 7. Kubernetes connectivity

`KUBERNETES_AUTH_MODE` selects how the client authenticates. No credentials are ever placed in
application configuration.

| Mode | Use | Behaviour |
|---|---|---|
| `AUTO` (default) | anywhere | Fabric8 auto-detection: kubeconfig if present, else the in-cluster ServiceAccount |
| `KUBECONFIG` | local dev | `KUBERNETES_KUBECONFIG_PATH` → `$KUBECONFIG` (first entry) → `~/.kube/config`; optional `KUBERNETES_CONTEXT`. Fails fast if the file is missing |
| `IN_CLUSTER` | K3s pod | Mounted ServiceAccount token at `/var/run/secrets/kubernetes.io/serviceaccount/`; kubeconfig lookup disabled. Fails fast if no token is mounted |

Timeouts: `KUBERNETES_CONNECTION_TIMEOUT_MS` (default 5000) and `KUBERNETES_REQUEST_TIMEOUT_MS`
(default 10000). The API server is contacted only by collection cycles, so an unreachable cluster never
blocks startup.

## 8. Required RBAC permissions

Minimum for Phase 1, in **every** monitored namespace:

| API group | Resources | Verbs |
|---|---|---|
| `tekton.dev` | `pipelineruns`, `taskruns` | `get`, `list` |

Nothing else is needed: the application never creates, updates, deletes or watches resources.
Recommended additions for Phase 2 (failure analysis): `get`/`list` on `tekton.dev` `pipelines`,
`tasks`, and core `pods`, `pods/log`, `events`.

A ready-to-adapt ServiceAccount / Role / RoleBinding is in
[docs/rbac/tekton-reader-rbac.yaml](docs/rbac/tekton-reader-rbac.yaml) (reference only; apply it yourself).
Verify with:

```bash
kubectl auth can-i list pipelineruns.tekton.dev -n <tekton-namespace> --as=system:serviceaccount:<agent-namespace>:cicd-reliability-agent
```

## 9. Environment variables

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/cicd_agent` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | — | Database credentials (inject from a Kubernetes Secret) |
| `DB_POOL_SIZE` | `10` | Hikari max pool size |
| `KUBERNETES_AUTH_MODE` | `AUTO` | `AUTO`, `KUBECONFIG`, `IN_CLUSTER` |
| `KUBERNETES_KUBECONFIG_PATH` | — | Kubeconfig file (KUBECONFIG mode) |
| `KUBERNETES_CONTEXT` | — | Kubeconfig context |
| `KUBERNETES_CONNECTION_TIMEOUT_MS` | `5000` | API server connect timeout |
| `KUBERNETES_REQUEST_TIMEOUT_MS` | `10000` | API request timeout |
| `MONITORING_ENABLED` | `true` | Run the scheduled collector |
| `MONITORING_NAMESPACES` | `default` | Comma-separated namespaces to monitor |
| `MONITORING_POLLING_INTERVAL_MS` | `15000` | Delay between cycles (min 1000) |
| `MONITORING_INITIAL_DELAY_MS` | `5000` | Delay before the first cycle |
| `MONITORING_LABEL_SELECTOR` | — | Optional label selector for list calls |
| `MONITORING_PAGE_SIZE` | `200` | List page size |
| `TEKTON_API_GROUP` / `TEKTON_API_VERSION` | `tekton.dev` / `v1` | Tekton API coordinates |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Comma-separated frontend origins |
| `SERVER_PORT` | `8080` | HTTP port |
| `SHUTDOWN_TIMEOUT` | `30s` | Graceful shutdown timeout |
| `LOG_LEVEL` | `INFO` | Level for `com.hackathon.agent` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | — | `ecs`, `logstash` or `gelf` for JSON logs |
| `HEALTH_SHOW_DETAILS` | `always` | Actuator health details (`never` to hide) |

## 10. Build and test

```bash
./gradlew clean build
```

```bash
./gradlew test
```

The standard suite needs **no Kubernetes cluster and no PostgreSQL**:

* Unit tests: status mapping, duration rules, normalization of real Tekton JSON, metric maths
  (rates, nearest-rank P95, empty datasets), time-range resolution, collection-cycle error isolation
  and overlap protection, Kubernetes auth-mode configuration.
* Kubernetes client tests against Fabric8's in-memory mock API server (namespace scoping, label
  selector, 403 handling).
* Repository, metric-query and collector-idempotency tests on H2 (PostgreSQL mode) using the real
  Flyway migrations and Hibernate schema validation.
* `@WebMvcTest` controller tests (pagination, filters, validation errors, 404/400/500, CORS).
* Full-context integration test (collector → DB → REST → Actuator → OpenAPI).
* `PostgresIntegrationTest` runs the same flow on real PostgreSQL via Testcontainers and is **skipped
  automatically when Docker is unavailable**.

Reports: `build/reports/tests/test/index.html`.

## 11. Docker

```bash
docker build -t ghcr.io/rafik790/hackathon-ai-agent:0.1.0 .
```

```bash
docker run --rm -p 8080:8080 --env-file .env -e KUBERNETES_AUTH_MODE=KUBECONFIG -e KUBERNETES_KUBECONFIG_PATH=/kube/config -v $HOME/.kube/config:/kube/config:ro ghcr.io/rafik790/hackathon-ai-agent:0.1.0
```

Push to GHCR:

```bash
echo <github-token> | docker login ghcr.io -u <github-user> --password-stdin
docker push ghcr.io/rafik790/hackathon-ai-agent:0.1.0
```

Image characteristics: multi-stage build, `eclipse-temurin:21-jre-alpine` runtime, Spring Boot layered
extraction (better layer caching), non-root UID 10001, port 8080, `HEALTHCHECK` on
`/actuator/health/liveness`, exec-form entrypoint so SIGTERM triggers graceful shutdown.

In K3s, use `KUBERNETES_AUTH_MODE=IN_CLUSTER`, the ServiceAccount from section 8, and probes on
`/actuator/health/liveness` and `/actuator/health/readiness`.

## 12. REST API

All endpoints are `GET`, return JSON, and use ISO-8601 UTC timestamps (`2026-11-21T10:00:00Z`).

| Endpoint | Description |
|---|---|
| `/api/v1/dashboard/summary` | Reliability summary |
| `/api/v1/dashboard/trends` | Time series grouped by hour/day |
| `/api/v1/pipelines` | Paginated PipelineRuns |
| `/api/v1/pipelines/{id}` | PipelineRun details |
| `/api/v1/pipelines/{id}/tasks` | TaskRuns of a PipelineRun (by start time) |
| `/api/v1/tasks` | Paginated TaskRuns |
| `/api/v1/tasks/{id}` | TaskRun details |
| `/api/v1/collector/status` | Last collection cycle outcome |
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` | Operations |
| `/swagger-ui.html`, `/v3/api-docs` | API documentation |

**Time window** (dashboard): `range=24h|7d|30d` (default `7d`) **or** `from` (inclusive) + optional `to`
(exclusive, default now). Max 366 days. Trends use hourly buckets up to 48 h and daily buckets above,
overridable with `granularity=hour|day` (hourly limited to 31 days). Both dashboard endpoints accept
optional `namespace` and `pipelineName`.

**Lists**: `page` (zero-based), `size` (default 20, max 100), `sort=<property>,asc|desc` (default
`startTime,desc`). Filters: `namespace`, `pipelineName`, `status` (case-insensitive), `from`, `to`
(on start time); tasks additionally `taskName`, `pipelineRunName`.
Sortable: `startTime`, `completionTime`, `durationMs`, `status`, `pipelineName`, `pipelineRunName`,
`namespace`, `createdAt`, `updatedAt`, `id` (+ `taskName`, `taskRunName` for tasks).

**Errors** always use one shape:

```json
{
  "timestamp": "2026-11-21T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value 'BROKEN' for parameter 'status'",
  "path": "/api/v1/pipelines",
  "fieldErrors": [{ "field": "status", "message": "Invalid value 'BROKEN' for parameter 'status'" }]
}
```

Status codes: 200, 400 (validation), 404 (unknown id/endpoint), 405, 503 (database unavailable), 500.

## 13. Examples

```bash
curl "http://localhost:8080/api/v1/dashboard/summary?range=7d&namespace=ci"
```

```json
{
  "from": "2026-11-14T10:00:00Z",
  "to": "2026-11-21T10:00:00Z",
  "totalExecutions": 120,
  "successfulExecutions": 102,
  "failedExecutions": 15,
  "runningExecutions": 3,
  "unknownExecutions": 0,
  "successRate": 87.18,
  "failureRate": 12.82,
  "averageDurationMs": 184250,
  "p95DurationMs": 402000,
  "totalTaskRuns": 560,
  "failedTaskRuns": 18,
  "taskFailureRate": 3.23
}
```

With no data in the window, counts and rates are `0` and durations are `null`.

```bash
curl "http://localhost:8080/api/v1/dashboard/trends?range=24h"
```

```json
{
  "from": "2026-11-20T10:00:00Z",
  "to": "2026-11-21T10:00:00Z",
  "granularity": "HOUR",
  "points": [
    { "bucketStart": "2026-11-20T10:00:00Z", "total": 0, "succeeded": 0, "failed": 0, "running": 0, "unknown": 0, "successRate": 0.0, "averageDurationMs": null },
    { "bucketStart": "2026-11-20T11:00:00Z", "total": 5, "succeeded": 4, "failed": 1, "running": 0, "unknown": 0, "successRate": 80.0, "averageDurationMs": 171000 }
  ]
}
```

```bash
curl "http://localhost:8080/api/v1/pipelines?status=failed&size=1&sort=startTime,desc"
```

```json
{
  "content": [{
    "id": 42,
    "pipelineRunUid": "6f1c2c9e-3f5b-4c1e-9a43-1b1f0f6f7a10",
    "pipelineRunName": "demo-app-build-x7k2p",
    "namespace": "ci",
    "pipelineName": "demo-app-build",
    "status": "FAILED",
    "startTime": "2026-11-21T09:58:02Z",
    "completionTime": "2026-11-21T10:01:17Z",
    "durationMs": 195000,
    "failureReason": "Failed",
    "failureMessage": "Tasks Completed: 3 (Failed: 1, Cancelled 0), Skipped: 1",
    "resourceCreatedAt": "2026-11-21T09:58:01Z",
    "createdAt": "2026-11-21T09:58:10Z",
    "updatedAt": "2026-11-21T10:01:25Z"
  }],
  "page": 0, "size": 1, "totalElements": 15, "totalPages": 15,
  "first": true, "last": false, "sort": "startTime: DESC"
}
```

```bash
curl "http://localhost:8080/api/v1/pipelines/42/tasks"
```

```json
[
  {
    "id": 311, "taskRunUid": "0b7e8f0e-8d0c-4a43-b1a4-5c3f0b6a1d22",
    "taskRunName": "demo-app-build-x7k2p-unit-tests", "namespace": "ci",
    "pipelineRunUid": "6f1c2c9e-3f5b-4c1e-9a43-1b1f0f6f7a10", "pipelineRunName": "demo-app-build-x7k2p",
    "pipelineName": "demo-app-build", "taskName": "unit-tests", "status": "FAILED",
    "startTime": "2026-11-21T09:59:40Z", "completionTime": "2026-11-21T10:01:12Z", "durationMs": 92000,
    "failureReason": "Failed", "failureMessage": "\"step-test\" exited with code 1",
    "resourceCreatedAt": "2026-11-21T09:59:39Z", "createdAt": "2026-11-21T09:59:45Z", "updatedAt": "2026-11-21T10:01:25Z"
  }
]
```

```bash
curl "http://localhost:8080/api/v1/collector/status"
```

```json
{
  "enabled": true,
  "namespaces": ["ci"],
  "pollingIntervalMs": 15000,
  "totalCycles": 240,
  "consecutiveFailedCycles": 0,
  "lastSuccessfulCycleAt": "2026-11-21T10:01:25Z",
  "lastCycle": {
    "startedAt": "2026-11-21T10:01:24Z", "finishedAt": "2026-11-21T10:01:25Z", "durationMs": 412,
    "successful": true, "discovered": { "PipelineRun": 12, "TaskRun": 48 },
    "created": 1, "updated": 3, "unchanged": 56, "skipped": 0, "errors": []
  }
}
```

## 14. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/actuator/health` shows `tektonCollector: DEGRADED` | Last cycle failed. See `errors` in the health details or `/api/v1/collector/status`, and the `Kubernetes API error` log lines |
| `HTTP 403` in collector errors | ServiceAccount/user lacks `list` on `pipelineruns`/`taskruns` in that namespace (section 8) |
| `HTTP 404` in collector errors | Tekton not installed, or wrong `TEKTON_API_VERSION` (use `v1beta1` for Tekton < 0.44) |
| `connect timed out` / `UnknownHost` | Wrong kubeconfig context or API server not reachable; check `KUBERNETES_CONTEXT`, VPN/security groups for the EC2 API port |
| `Kubeconfig file not found` at startup | `KUBECONFIG` mode without a file; set `KUBERNETES_KUBECONFIG_PATH` or use `AUTO` |
| `IN_CLUSTER auth mode selected but no ServiceAccount token` | Running outside a pod, or `automountServiceAccountToken: false` |
| `Schema-validation: missing table` | Flyway did not run against the same database; check `DB_URL` and the `flyway_schema_history` table |
| No data although runs exist | Check `MONITORING_NAMESPACES` and `MONITORING_LABEL_SELECTOR`; runs without `startTime` are excluded from metric windows |
| Browser CORS error | Add the frontend origin to `CORS_ALLOWED_ORIGINS` (exact scheme/host/port) |
| Gradle cannot download dependencies / toolchain (`PKIX path building failed`) | A TLS-intercepting proxy or antivirus is in the way. On Windows use the OS trust store: `./gradlew build -Djavax.net.ssl.trustStoreType=Windows-ROOT` |
| `PostgresIntegrationTest` skipped | Docker not available; expected, the rest of the suite still runs |

## Failure evidence (Phase 2.1)

A separate worker (package `evidence`) collects Pod, container and log details for failed TaskRuns into
`failure_evidence`, `container_evidence` and `container_log`. It is **disabled by default**
(`EVIDENCE_ENABLED=true` to enable; also requires `MONITORING_ENABLED=true`) and does not change Phase 1
collection, its schedule or its health. Nothing is exposed through the REST API yet.

* **Selection**: failed TaskRuns of the last 24 h in the monitored namespaces, at most 10 per cycle.
* **Identity**: the live TaskRun UID and the Pod's owner UID must match; every attempt (`retriesStatus`
  plus the current status) has its own row.
* **Logs**: at most 3 containers per TaskRun, the last 500 lines, 64 KB per log and 256 KB per TaskRun,
  2 requests at a time. NUL characters and invalid UTF-8 are removed and credentials redacted.
  **Redaction is best effort**: treat `container_log` as sensitive data.
* **Retries**: transient and permission errors back off exponentially (1, 2, 4, 8 minutes) and are given up
  after 5 attempts; permanent errors are final at once. Final evidence is never fetched again.
* **Not available**: logs of TaskRuns that Tekton timed out or cancelled, because Tekton deletes their Pods
  unless its `keep-pod-on-cancel` flag is enabled.
* **Retention**: `expires_at` is set to 14 days, but expired logs are not deleted yet.

Additional permissions in every monitored namespace (namespaced Role, no cluster-wide access):

| API group | Resources | Verbs |
|---|---|---|
| `tekton.dev` | `taskruns` | `get` |
| core | `pods`, `pods/log` | `get` |

| `failure_evidence.status` | Meaning |
|---|---|
| `PENDING` | Incomplete; retried at `next_attempt_at` |
| `COLLECTED` | Pod details and all relevant logs stored |
| `PARTIAL` | Pod details stored, at least one log unavailable |
| `NO_POD` / `POD_DELETED` / `RESOURCE_DELETED` | No Pod was created / Pod is gone / TaskRun is gone |
| `FORBIDDEN` / `FAILED` | Given up because of missing permissions / another error |

`/actuator/health` reports the worker as `evidenceCollector`. Its status is always `UP`, because any other
status would change the overall health; look at its `state` (`DISABLED`, `WAITING`, `OK`, `DEGRADED`) and
`permissionsMissing` details. All settings are listed in `.env.example`.

## AI failure analysis (Phase 2.2)

Explains failures by sending the collected, redacted evidence to a large language model. **Disabled by
default** (`AI_ENABLED=false`): no provider is created and nothing is sent. It only produces recommendations
for people; the application never executes them. Analyses can be wrong, so verify them before acting.

### Architecture

```
FailureAnalysisScheduler ─► FailureAnalysisService ─► FailureAnalysisProcessor (one analysis: claim → ask → check → store)
  (own thread)               (cycle, workers)         ├─► FailureContextBuilder        (database only, redacts again)
                                                      ├─► FailureAnalysisPromptBuilder (versioned prompt + JSON schema)
                                                      ├─► AiQuotaService               (reserves every invocation)
                                                      ├─► LlmProvider ◄── AnthropicLlmProvider | MockLlmProvider
                                                      ├─► FailureAnalysisParser, AnalysisEvidenceValidator
                                                      └─► AnalysisPersistenceService ─► failure_analysis (V3)
```

* **Claiming**: an analysis is claimed with one conditional `UPDATE`, so several instances never process the
  same one. A claim has a lease (`AI_ANALYSIS_LEASE_SECONDS`, keep it well above the request timeout); after a
  crash the analysis is claimed again and late results of the old worker are discarded.
* **Transactions** are short; none is open while the provider is called.
* **Retries**: rate limits, timeouts, server errors and invalid answers back off exponentially (1, 2, 4 minutes,
  or longer if the provider asks for it), at most `AI_ANALYSIS_MAX_RETRIES` times. Authentication, quota,
  invalid-request, refusal and truncation errors are final. Rate limit and account errors pause all requests.
* **Quota**: every invocation, including retries and forced analyses, is reserved in the database before the
  call (`ai_quota`, `ai_invocation`, V4). The reservation is atomic across workers and instances, counts per
  provider and is never given back, even if the call fails. The mock provider is exempt. When the quota is
  exhausted, analyses wait without losing attempts and manual requests get 429 with `Retry-After`.
* **Evidence validation**: every answer is compared with the context it was based on. Supporting evidence that is
  not found in its source, and facts with invented log lines, names, exit codes, timestamps or other identifiers,
  are removed; unverifiable facts become hypotheses (`Unverified: ...`). Without specific evidence the result is
  `UNKNOWN` with an "Insufficient evidence" root cause and a confidence of at most 0.3, and the proposed cause
  becomes a hypothesis. `CRITICAL` requires verified evidence of an impact beyond the run, and an uncertain cause
  is at most `MEDIUM`. Quotes in the root cause may differ from the log in punctuation, but not in their words.
  Each reduction is logged with its reason. If a failed container's log proves a compiler error, an unknown,
  generic or invented conclusion is replaced by that error (`COMPILATION`, confidence 0.8); a generic summary is
  never stored next to a confirmed root cause. The actions then correct the first error (`HIGH`), check the
  correction (`MEDIUM`) and prevent it (`LOW`); an investigation is only recommended if the cause is unknown. Whether a conclusion follows logically from its evidence cannot be checked.
* **Independence**: monitoring and evidence collection do not know about the analysis. Health is reported as
  `aiAnalysis` in `/actuator/health`; its status is always `UP`, its `state` is `DISABLED`, `WAITING`, `OK`,
  `DEGRADED`, `QUOTA_EXHAUSTED` or `PAUSED`.

### Providers

Business code only uses `LlmProvider` (`ai.llm`). Adding a provider means implementing this interface and adding
one line to `LlmProviderConfig`. An unsupported `AI_PROVIDER`, or `anthropic` without key or model, stops the
application at startup with a clear message.

| Provider | Description |
|---|---|
| `anthropic` | Claude through the official Java SDK. The answer is requested as structured output (JSON schema). |
| `mock` | No external calls; returns a fixed, valid answer marked `[MOCK]`. |

### Configuration

| Variable | Default | Description |
|---|---|---|
| `AI_ENABLED` | `false` | Enables the analysis |
| `AI_PROVIDER` | `anthropic` | `anthropic` or `mock` |
| `ANTHROPIC_API_KEY` | | Required for `anthropic`; inject it from a Kubernetes Secret |
| `ANTHROPIC_MODEL` | | Required for `anthropic`, e.g. `claude-opus-5-5`; must support structured outputs |
| `ANTHROPIC_MAX_TOKENS` | `8192` | Output limit per request, including the model's thinking |
| `ANTHROPIC_TIMEOUT_MS` | `120000` | Timeout of one request |
| `ANTHROPIC_EFFORT` | | Optional effort level (`low`, `medium`, `high`); not supported by every model |
| `AI_ANALYSIS_VERSION` | `1` | Increase it to allow a new analysis of evidence analysed before |
| `AI_ANALYSIS_REANALYSE_ON_VERSION_CHANGE` | `false` | Re-analyse recent evidence automatically after a version change |
| `AI_ANALYSIS_LOOKBACK_HOURS` | `24` | Only evidence collected within this window is analysed automatically |
| `AI_ANALYSIS_DAILY_LIMIT` | `100` | Provider invocations within any 24 hours |
| `AI_ANALYSIS_BATCH_SIZE` | `5` | Analyses per cycle |
| `AI_ANALYSIS_POLLING_INTERVAL_MS` | `30000` | Delay between cycles |
| `AI_ANALYSIS_INITIAL_DELAY_MS` | `15000` | Delay before the first cycle |
| `AI_ANALYSIS_MAX_CONCURRENT_REQUESTS` | `2` | Concurrent provider requests |
| `AI_ANALYSIS_MAX_RETRIES` | `3` | Retries after the first attempt |
| `AI_ANALYSIS_RETRY_BACKOFF_SECONDS` | `60` | First retry delay |
| `AI_ANALYSIS_LEASE_SECONDS` | `600` | Duration of a claim |
| `AI_ANALYSIS_MAX_LOG_CHARS` | `60000` | Log text per analysis |

Run with the mock provider (PowerShell; also set the `DB_*` variables):

```powershell
$env:AI_ENABLED="true"; $env:AI_PROVIDER="mock"; .\gradlew.bat bootRun
```

Run with Anthropic. Set the key in your own terminal or through a secret store, never in a file that is committed:

```powershell
$env:AI_ENABLED="true"; $env:AI_PROVIDER="anthropic"; $env:ANTHROPIC_MODEL="claude-opus-5-5"; .\gradlew.bat bootRun
```

### Database

`V3__create_failure_analysis_table.sql` adds `failure_analysis`, one row per evidence, analysis version, prompt
version and revision (unique). Lists are stored as `JSONB`. Lifecycle:

```
PENDING ─► PROCESSING ─┬─► COMPLETED
              ▲        ├─► RETRY_PENDING ─► PROCESSING
              └────────┘ (lease expired)  └─► FAILED
```

Check constraints guarantee that a `COMPLETED` row contains its result.

### REST API

| Endpoint | Description |
|---|---|
| `GET /api/v1/failure-analyses` | Paginated; filters `category`, `severity`, `status` (case-insensitive) |
| `GET /api/v1/failure-analyses/{id}` | One analysis |
| `GET /api/v1/tasks/{taskRunId}/analysis` | Newest analysis of the last attempt of a TaskRun |
| `POST /api/v1/failure-evidence/{evidenceId}/analyse` | Queues an analysis (202). `?force=true` analyses completed evidence again. 409 if AI is disabled, evidence is still being collected or it was already analysed; 429 if the daily quota is exhausted |

Sortable: `id`, `analysisStatus`, `failureCategory`, `severity`, `confidence`, `analysedAt`, `createdAt`, `updatedAt`.

Example (illustrative data):

```json
{
  "id": 7, "failureEvidenceId": 12, "taskRunId": 311, "taskRunName": "demo-app-build-x7k2p-build-and-push",
  "taskName": "build-and-push", "pipelineRunName": "demo-app-build-x7k2p", "namespace": "ci", "attempt": 0,
  "status": "COMPLETED", "failureCategory": "REGISTRY_AUTHENTICATION", "severity": "HIGH",
  "confidence": 0.92, "confidenceLevel": "HIGH",
  "summary": "Pushing the image failed because the registry rejected the credentials.",
  "rootCause": "The registry rejected the credentials used by step build-and-push.",
  "observedFacts": ["step-build-and-push exited with code 1", "The log contains 'denied: authentication required'"],
  "hypotheses": ["The registry credential may have expired"],
  "supportingEvidence": [
    {"source": "CONTAINER_LOG", "containerName": "step-build-and-push", "observation": "denied: authentication required"}
  ],
  "recommendedActions": [
    {"priority": "HIGH", "action": "Verify the registry credentials of the pipeline ServiceAccount", "reason": "Authentication was rejected"}
  ],
  "preventiveRecommendations": ["Validate registry credentials before the build"],
  "provider": "anthropic", "model": "claude-opus-5-5", "analysisVersion": 1, "promptVersion": "v4", "revision": 0,
  "inputTokens": 5120, "outputTokens": 870, "processingAttempts": 1, "lastError": null, "nextAttemptAt": null,
  "analysedAt": "2026-11-25T10:02:11Z", "createdAt": "2026-11-25T10:01:40Z", "updatedAt": "2026-11-25T10:02:11Z"
}
```

### Security and cost

* **Data leaves the cluster** when `anthropic` is enabled: names, messages and log excerpts are sent to the
  provider. All free text is redacted before, but redaction is best effort.
* **No authentication, open CORS**: everyone who can reach the API, and any website they visit, can read analyses
  and call the `POST` endpoint, which costs money. Add authentication and restrict CORS before exposing the
  application. Until then `AI_ANALYSIS_DAILY_LIMIT` bounds the damage.
* Logs are untrusted input. The prompt tells the model to ignore instructions in them and answers are validated,
  but a manipulated log can still mislead an analysis.
* Keys, prompts and answers are never logged. `last_error` contains only the kind of error.
* Every evidence is analysed once per version; context, output, concurrency, batch and daily volume are bounded;
  tokens of all attempts are recorded with provider, model and prompt version.

### Tests

```bash
./gradlew test --tests "com.hackathon.agent.ai.*"
```

The Anthropic provider is tested with the real SDK against a local HTTP server; no test calls the Anthropic API.
Database tests run on H2, on PostgreSQL 17 through Testcontainers when Docker is available, and on any PostgreSQL
given in `AI_TEST_DB_URL` / `AI_TEST_DB_USER` (use an empty throwaway database: the tests delete its data).

## Agent investigation (Phase 3.1)

An LLM agent investigates a failed PipelineRun or TaskRun on request: in every step it chooses one predefined,
read-only tool, reads its result and decides what to look at next, until it can report the cause (or a limit is
reached). It is disabled by default and independent of the automatic failure analysis, whose tables, prompt, APIs and
scheduler are unchanged. It uses the same LLM provider and daily quota (`ai.*`); every question to the model counts as
one invocation.

### How it works

```
POST /api/v1/agent-investigations ─► agent_investigation (PENDING)
                                        │  worker thread "agent-investigation" claims it (conditional UPDATE)
                                        ▼
   ┌─► prompt: known facts + earlier steps + budget ─► LLM ─► decision (JSON schema)
   │      CALL_TOOL: registry checks tool, name, namespace, duplicates ─► read-only tool ─► sanitized result
   └──────┘ FINAL_REPORT: evidence must be quoted from the cited step ─► COMPLETED (report)
            limits: steps, tool calls, timeout, prompt and output size ─► FAILED with the reason
```

* **Tools** (`investigation.tool`): `get_pipelinerun_details`, `get_taskrun_details`, `get_taskrun_logs` (uses the
  logs stored by evidence collection, else the end of the live log), `get_pod_events`, `get_deployment_status`,
  `get_serviceaccount_details` (RoleBindings, ClusterRoleBindings and the rules they grant; read only) and
  `get_previous_successful_pipelinerun`. Each takes a resource name and a namespace.
* **Safety**: only registered tools run, all of them through `AgentKubernetesReader`, which offers nothing but `get`
  and `list`. Names must be valid Kubernetes names and namespaces one of `MONITORING_NAMESPACES`. A repeated call,
  an unknown tool or invalid arguments is refused and the refusal is shown to the model. Tool results, arguments and
  report texts are redacted with the evidence sanitizer and bounded; sensitive-looking Tekton parameters are hidden.
* **Report**: evidence is accepted only if the cited step's result contains it; without verified evidence the
  confidence is at most 0.3. The category is one of the failure-analysis categories.
* **Database** (V5): `agent_investigation` (status, counters, report, tokens, claim) and `agent_investigation_step`
  (tool, sanitized arguments, reasoning, result summary, status, error, duration). Abandoned investigations are
  failed after `AGENT_INVESTIGATION_LEASE_SECONDS`.
* **RBAC**: read-only rules for the tools are in [docs/rbac/agent-investigation-rbac.yaml](docs/rbac/agent-investigation-rbac.yaml).
  Missing permissions do not break an investigation; the tool reports them to the agent.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `AGENT_INVESTIGATION_ENABLED` | `false` | Enables the feature; also needs `AI_ENABLED=true` |
| `AGENT_INVESTIGATION_MAX_STEPS` | `8` | Questions to the model per investigation, including the final report |
| `AGENT_INVESTIGATION_MAX_TOOL_CALLS` | `10` | Executed tools per investigation |
| `AGENT_INVESTIGATION_TIMEOUT_SECONDS` | `300` | Total duration |
| `AGENT_INVESTIGATION_MAX_TOOL_OUTPUT_CHARS` | `6000` | One tool result |
| `AGENT_INVESTIGATION_MAX_LOG_CHARS` | `8000` | Log text of the log tool, over all containers |
| `AGENT_INVESTIGATION_MAX_PROMPT_CHARS` | `60000` | One prompt (at least 10000); older results are shortened beyond it |
| `AGENT_INVESTIGATION_POLL_INTERVAL_MS` | `2000` | How often the worker looks for queued investigations |
| `AGENT_INVESTIGATION_LEASE_SECONDS` | `900` | Must exceed the timeout |

### REST API

| Endpoint | Description |
|---|---|
| `POST /api/v1/agent-investigations` | Body `{"taskRunName": "...", "namespace": "..."}` or `{"pipelineRunName": "...", "namespace": "..."}`. 202 with the queued investigation (or the one already queued or running for the run); 400 invalid reference or namespace, 404 unknown run, 409 run not failed or feature/AI disabled, 429 quota exhausted |
| `GET /api/v1/agent-investigations/{id}` | Status, counters, root cause, confidence and report |
| `GET /api/v1/agent-investigations/{id}/steps` | Timeline: tool, arguments, reasoning, result summary, status |
| `GET /api/v1/agent-investigations` | Paginated; filters `taskRunName`, `pipelineRunName`, `namespace`, `status`; sortable by `id`, `status`, `createdAt`, `startedAt`, `completedAt`, `namespace`, `targetName` |

While the feature is disabled every endpoint answers 409 with an explanation.

```bash
curl -X POST http://localhost:8080/api/v1/agent-investigations -H 'Content-Type: application/json'   -d '{"taskRunName": "demo-app-build-restricted-cwcrf-deploy", "namespace": "hackathon"}'
curl http://localhost:8080/api/v1/agent-investigations/1
curl http://localhost:8080/api/v1/agent-investigations/1/steps
curl 'http://localhost:8080/api/v1/agent-investigations?status=COMPLETED&namespace=hackathon'
```

Limitations: the agent runs one investigation at a time per instance and does not retry provider errors; the mock
provider cannot drive it (its answer is not a decision); tools read only the monitored namespaces.

## Resolution tracking

Tracks the work on failed TaskRuns, independently of their Tekton execution status and of AI analyses and
investigations: resolution needs no investigation, and an investigation can be COMPLETED while the fix is still
FIX_IN_PROGRESS. TaskRun resolutions are the source of truth; a PipelineRun resolution is derived from them. Only the
database is involved; no Kubernetes permission is needed.

### TaskRun lifecycle

| From | Allowed next statuses |
|---|---|
| `OPEN` (also a failed TaskRun without a record) | `INVESTIGATING`, `FIX_IN_PROGRESS` |
| `INVESTIGATING` | `FIX_IN_PROGRESS`, `RESOLVED` |
| `FIX_IN_PROGRESS` | `RESOLVED` |
| `RESOLVED` | `REOPENED` |
| `REOPENED` | `INVESTIGATING`, `FIX_IN_PROGRESS` |

Keeping the status (updating team, person, summary or notes) is always allowed; there is no way back to `OPEN`.
`resolvedAt` is set when a record becomes `RESOLVED` and cleared when it is reopened. Nothing changes a resolution
automatically: an AI investigation can only suggest a status and team, which a person applies in the UI.

### PipelineRun aggregation

Calculated on every read from the failed TaskRuns of the PipelineRun (never stored, so it cannot disagree with them;
see `ResolutionAggregation`):

* no failed TaskRuns: `status` is null (nothing to resolve);
* all failed TaskRuns `RESOLVED`: `RESOLVED`, as soon as the last one is resolved;
* otherwise the first of `REOPENED`, `FIX_IN_PROGRESS`, `INVESTIGATING`, `OPEN` found among them.

A failed TaskRun without a record counts as `OPEN`, so a newly collected failed TaskRun makes a resolved PipelineRun
unresolved again. TaskRuns whose rows were removed still count through their records.

### Database (V6)

`resolution_record` holds one row per TaskRun (`target_key` is unique, e.g. `TASK_RUN:52`), with a copy of the run's
name and namespace so it stays readable when the run row is removed (`task_run_id` is then set to null). `version`
implements optimistic locking. `resolution_history` is append-only: one entry per change with the previous and new
status, the values after the change, the comment, who and when. The schema also allows `PIPELINE_RUN` rows; the
application no longer writes or reads them.

### REST API

| Endpoint | Description |
|---|---|
| `GET /api/v1/tasks/{id}/resolution` | Resolution of a TaskRun; a failed TaskRun without a record is `OPEN` with `tracked: false`, a TaskRun that has not failed has `status: null` |
| `PUT /api/v1/tasks/{id}/resolution` | Body `{"status": "...", "version": <shown version or null>, "assignedTeam", "assignedPerson", "summary", "notes", "comment", "changedBy"}`; fields left out keep their value. 400 invalid value, 404 unknown run, 409 transition not allowed, stale version or TaskRun not failed |
| `GET /api/v1/tasks/{id}/resolution/history` | Changes, oldest first |
| `GET /api/v1/tasks/resolutions?ids=1,2` | Status of up to 100 TaskRuns |
| `GET /api/v1/pipelines/{id}/resolution` | Derived resolution of a PipelineRun with `taskRuns`: failed, resolved and unresolved counts, counts per status and each failed TaskRun's resolution. Read-only (`PUT` returns 405) |
| `GET /api/v1/pipelines/{id}/resolution/history` | Changes of its failed TaskRuns, oldest first, each with `taskRunId` and `taskRunName` |
| `GET /api/v1/pipelines/resolutions?ids=1,2` | Derived status of up to 100 PipelineRuns, with `failedTaskRuns` and `resolvedTaskRuns` |
| `GET /api/v1/dashboard/summary` | Also returns `failedTaskRunsByResolution`: the failed TaskRuns of the window (same filters as `failedTaskRuns`) per resolution status, counted in one query |

```bash
curl -X PUT http://localhost:8080/api/v1/tasks/52/resolution -H 'Content-Type: application/json'   -d '{"status": "INVESTIGATING", "version": null, "assignedTeam": "DEVOPS_PLATFORM", "changedBy": "priya"}'
curl http://localhost:8080/api/v1/pipelines/3/resolution
```

Limitations: the application has no user accounts, so `changedBy` is what the caller sends and anyone who can reach
the API can change a resolution.

## Known limitations (Phase 1)

* Polling only; changes are seen with up to one polling interval of delay. Runs created and pruned
  within one interval could be missed.
* Single-replica collector. Running several replicas is safe for data (UID unique constraint) but
  wasteful, and concurrent inserts of the same new run can make one replica's batch fail (it retries
  on the next cycle). Use leader election before scaling out.
* Executions without a `startTime` are listed but excluded from time-window metrics.
* Trends are bucketed in the application from a lightweight projection; very large windows with
  hundreds of thousands of runs would warrant SQL-side `date_trunc` aggregation.
* The API has no authentication yet; expose it only inside the cluster / behind Traefik with auth.
* Collector status is in memory and resets on restart.
