# WebhookHub

WebhookHub is a webhook ingestion and delivery system built on Kotlin JVM.
It receives signed HTTP webhook events, persists them, and delivers them asynchronously to configured HTTP destinations with automatic retries and a dead-letter queue for failed deliveries.

---

## Architecture

```
             ┌─────────────┐
HTTP POST ──>│     :api     │── publishes job ──> webhookhub.deliveries
             │  (Ktor/Netty)│                          │
             │  + Postgres  │                    ┌─────▼──────┐
             └─────────────┘                    │   :worker   │── HTTP POST ──> destination
                                                │  (consumer) │
                                                │  + Postgres │
                                                └─────────────┘

Retry topology (RabbitMQ):
  webhookhub.deliveries ──(TTL/NACK)──> webhookhub.dlx ──> webhookhub.dlq
  worker (on failure)   ──────────────> webhookhub.retry (per-msg TTL)
                                               │ TTL expires
                                               └──> webhookhub.deliveries (next attempt)
```

**Modules**

| Module | Responsibility |
|---|---|
| `:shared` | Queue message models (`DeliveryJob`), environment config (`EnvConfig`), shared JSON instance (`AppJson`), RabbitMQ topology declaration |
| `:api` | Ktor/Netty server — webhook ingestion (HMAC validation, idempotency, delivery scheduling), source and destination management, Flyway migrations, RabbitMQ topology init |
| `:worker` | RabbitMQ consumer — HTTP delivery to destination URLs, exponential-backoff retry via `webhookhub.retry`, delivery state persistence (DELIVERED / RETRYING / DEAD) |

**Persistence** — Postgres is the source of truth for all delivery state, idempotency keys, and audit history.
**Transport** — RabbitMQ is used exclusively as execution transport, not state storage.

---

## Prerequisites

- Docker and Docker Compose
- JDK 21 (auto-downloaded via Gradle Foojay toolchain if not present)
- Gradle (via wrapper — no global install needed)

---

## How to run locally

**1. Start infrastructure**

```bash
docker-compose up -d
```

| Service | URL | Credentials |
|---|---|---|
| Postgres | `localhost:5432` | `webhookhub` / `webhookhub` |
| RabbitMQ AMQP | `localhost:5672` | `webhookhub` / `webhookhub` |
| RabbitMQ Management UI | http://localhost:15672 | `webhookhub` / `webhookhub` |
| PGAdmin | http://localhost:5050 | `admin@webhookhub.com` / `admin` |

**2. Set environment variables**

```bash
export DB_URL=jdbc:postgresql://localhost:5432/webhookhub
export DB_USER=webhookhub
export DB_PASSWORD=webhookhub

export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5672
export RABBITMQ_USER=webhookhub
export RABBITMQ_PASSWORD=webhookhub
export RABBITMQ_VHOST=webhookhub
```

**3. Run the API**

```bash
./gradlew :api:run
```

The API starts on `http://localhost:8080`.

**4. Run the Worker**

```bash
./gradlew :worker:run
```

---

## API Endpoints

### Health

```
GET /health
```

Returns database connectivity status and HikariCP connection pool statistics.

**Response `200 OK`**
```json
{
  "status": "UP",
  "db": "UP",
  "pool": {
    "total": 10,
    "active": 1,
    "idle": 9,
    "pending": 0
  }
}
```

Returns `503 Service Unavailable` with `"status": "DOWN"` if the database is unreachable.

---

### Sources

Sources represent external systems that send webhook events. Each source has a unique name and a generated HMAC secret used to verify incoming payloads.

#### Create a source

```
POST /sources
Content-Type: application/json
```

```json
{ "name": "github" }
```

**Response `201 Created`** — includes `hmacSecret` (shown only at creation time)
```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "name": "github",
  "hmacSecret": "a3f1c2...",
  "active": true,
  "createdAt": "2026-02-18T10:00:00Z"
}
```

Returns `400 Bad Request` if `name` is blank or exceeds 100 characters.

#### List sources

```
GET /sources
```

**Response `200 OK`** — `hmacSecret` is not exposed in this endpoint
```json
[
  {
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "name": "github",
    "active": true,
    "createdAt": "2026-02-18T10:00:00Z"
  }
]
```

---

### Destinations

Destinations are HTTP endpoints that receive webhook events. Each destination is created with one or more routing rules that determine which source/event-type pairs are delivered to it.

#### Create a destination

```
POST /destinations
Content-Type: application/json
```

```json
{
  "name": "my-service",
  "targetUrl": "https://my-service.example.com/webhook",
  "rules": [
    { "sourceName": "github", "eventType": "push" }
  ]
}
```

At least one rule is required. Returns `400 Bad Request` if `name` is blank or exceeds 100 characters, `targetUrl` is not a valid HTTP/HTTPS URL, or `rules` is empty.

**Response `201 Created`**
```json
{
  "id": "a1b2c3d4-...",
  "name": "my-service",
  "targetUrl": "https://my-service.example.com/webhook",
  "active": true,
  "createdAt": "2026-02-19T10:00:00Z",
  "rules": [
    { "id": "e5f6...", "sourceName": "github", "eventType": "push" }
  ]
}
```

#### List destinations

```
GET /destinations
```

**Response `200 OK`** — each destination includes its rules

#### Get a destination

```
GET /destinations/{id}
```

**Response `200 OK`** — destination with its rules.
Returns `404 Not Found` if the destination does not exist.

#### Add a rule to a destination

```
POST /destinations/{id}/rules
Content-Type: application/json
```

```json
{ "sourceName": "stripe", "eventType": "charge.created" }
```

**Response `201 Created`**
```json
{ "id": "f7a8...", "sourceName": "stripe", "eventType": "charge.created" }
```

Returns `404 Not Found` if the destination does not exist. Returns `400 Bad Request` if `sourceName` or `eventType` is blank.

---

### Ingest

Receives a webhook event from a registered source, validates its HMAC-SHA256 signature, persists the event, creates a `PENDING` delivery record for each matching destination, and publishes a `DeliveryJob` to RabbitMQ for asynchronous delivery.

#### Ingest a webhook event

```
POST /ingest/{sourceName}?type={eventType}
X-Signature: <hex-encoded HMAC-SHA256(secret, raw-request-body)>
Content-Type: application/json
```

The raw request body is forwarded as-is to the destination. The `type` query parameter identifies the event type and is used to match destination routing rules.

**Response `202 Accepted`** — returned for both new and duplicate events (idempotent)

| Error | Condition |
|---|---|
| `400 Bad Request` | `type` query parameter is missing or blank |
| `401 Unauthorized` | `X-Signature` header is missing, blank, or does not match `HMAC-SHA256(secret, body)` |
| `401 Unauthorized` | Source exists but is inactive |
| `404 Not Found` | No source registered under `{sourceName}` |

---

## How to test

**Unit tests** (no Docker required)

```bash
./gradlew :api:test --tests "*UseCaseTest" --tests "*RoutesTest"
```

**Integration tests** (requires Docker for Testcontainers)

```bash
./gradlew :api:test
```

Testcontainers automatically pulls and manages isolated PostgreSQL containers. No running infrastructure needed.

**Full test suite**

```bash
./gradlew test
```

**Full clean build**

```bash
./gradlew clean build
```

---

## RabbitMQ Topology

Declared idempotently on every startup by `RabbitMQTopology.declare()` in `:shared`.

| Resource | Type | Configuration |
|---|---|---|
| `webhookhub` | direct exchange | main producer target |
| `webhookhub.dlx` | fanout exchange | receives expired / nacked messages |
| `webhookhub.deliveries` | durable queue | `x-message-ttl=30min`, `x-dead-letter-exchange=webhookhub.dlx` |
| `webhookhub.retry` | durable queue | no consumer; `x-dead-letter-exchange=webhookhub`, `x-dead-letter-routing-key=delivery` — worker publishes failed jobs here with a per-message `expiration` equal to the backoff delay; on expiry the message is forwarded back to `webhookhub.deliveries` |
| `webhookhub.dlq` | durable queue | bound to DLX — requires manual replay |

---

## Webhook signing (HMAC-SHA256)

Every webhook source has a 64-character hex secret generated at creation time (`POST /sources`). The sender must include a signature header computed as:

```
X-Signature: <hex-encoded HMAC-SHA256(secret, raw-request-body)>
```

The API validates the signature using constant-time comparison to prevent timing attacks.
Requests with a missing or invalid signature are rejected with `401 Unauthorized`.

---

## Operational notes

**Idempotency**

Duplicate deliveries are detected via a unique DB constraint on `(source_name, idempotency_key)`.
Re-sending the same event returns a successful no-op — no new records are created and no jobs are re-published.

**Retry policy**

Failed deliveries are retried with exponential backoff up to 5 attempts total.
On each failure the worker updates the delivery record (`last_error`, `last_attempt_at`, `status=RETRYING`) and publishes the next attempt to `webhookhub.retry` with a per-message TTL; the broker forwards it back to the main queue once the delay expires.

| Failed attempt | Delay before retry |
|---|---|
| 1 | 30 s |
| 2 | 2 min |
| 3 | 10 min |
| 4 | 30 min |
| 5 (final) | no retry — status set to `DEAD` |

Any non-2xx HTTP response or network/timeout error is treated as a failure.
Once all attempts are exhausted the delivery record is marked `DEAD` and the message is acked without requeuing.

**Dead Letter Queue (DLQ)**

Messages in the DLQ require manual inspection and replay. Monitor via the RabbitMQ Management UI at http://localhost:15672.

**Concurrency**

The worker sets `basicQos(5)` (prefetch count) so the broker delivers at most 5 unacknowledged messages at a time, matching the HikariCP pool size and preventing connection starvation.
A message is only acknowledged after its delivery status has been durably written to the database; on any infrastructure failure the message is nacked (requeue=false) and dead-lettered to `webhookhub.dlq` for manual inspection.

---

## Troubleshooting

**Postgres connection refused**
Verify the container is healthy: `docker-compose ps`. Wait for the health check to pass before starting the API.

**RabbitMQ exchanges/queues not found**
The API declares all topology on startup. Check the Management UI at http://localhost:15672.

**Flyway migration errors**
Check that `DB_URL`, `DB_USER`, and `DB_PASSWORD` point to the correct database. Migrations run automatically on API startup.

**Stop all infrastructure**

```bash
docker-compose down
```

To also remove persistent volumes:

```bash
docker-compose down -v
```
