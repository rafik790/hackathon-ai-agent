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
