# Seat Reservation at Scale

A JSON API that sells assigned seats for a show and stays correct when thousands of buyers hit the same
seat in the same second: one winner per seat, clean `409` declines for everyone else, no double charge on
retries, and no `5xx`.

**Live:** https://seat-reservation-67bc.onrender.com

| What | Where |
|---|---|
| API docs (Swagger UI) | [`/swagger-ui.html`](https://seat-reservation-67bc.onrender.com/swagger-ui.html) |
| Liveness / readiness | [`/health/liveness`](https://seat-reservation-67bc.onrender.com/health/liveness), [`/health/readiness`](https://seat-reservation-67bc.onrender.com/health/readiness) |
| Prometheus metrics | [`/metrics`](https://seat-reservation-67bc.onrender.com/metrics) |
| Design write-up | [`WRITEUP.md`](WRITEUP.md), [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md) |

The service runs on Render's free tier and sleeps when idle; the first request after a pause can take
about a minute while it cold-starts. `/health/readiness` returns `200` once it can reach the database.

Stack: Java 21 (virtual threads), Spring Boot 3.3, PostgreSQL 16, Flyway, JdbcClient, Spring Security
(JWT), Micrometer/Prometheus, structured JSON logs.

---

## One-command burst

Reproduces the on-sale stampede against any deployment and prints the outcome distribution and the final
reconciliation.

```bash
./burst.sh https://seat-reservation-67bc.onrender.com            # macOS / Linux / Git Bash
node burst/burst.mjs https://seat-reservation-67bc.onrender.com  # any OS, incl. Windows PowerShell
```

Requirements: Node 18+ (no `npm install`, zero dependencies) and `ADMIN_SECRET` to create the test show,
either as an environment variable or in `./.env`.

**Admin secret for the live deployment:** `demo-admin-doFb4TyqogkfK2aGxa1agyFR`. It is published on purpose so
reviewers can create shows and run bursts; it only grants the admin role, whose sole power is `POST /shows`.

```bash
export ADMIN_SECRET=demo-admin-doFb4TyqogkfK2aGxa1agyFR      # PowerShell: $env:ADMIN_SECRET="demo-admin-doFb4TyqogkfK2aGxa1agyFR"
./burst.sh https://seat-reservation-67bc.onrender.com --requests 20000 --concurrency 1000 --hot-seats 10 --users 2000
```

| Option | Default | Meaning |
|---|---|---|
| `--requests` | 20000 | Total reserve requests in the wave |
| `--concurrency` | 1000 | Requests kept in flight at once |
| `--hot-seats` | 10 | Seats everyone fights over (`H1..Hn`) |
| `--users` | 2000 | Distinct buyers (each with its own token) |
| `--retry-rate` | 0.1 | Share of requests re-sent with the same idempotency key |
| `--greedy` | 20 | Parallel requests from one user on a limit-4 show |
| `--timeout-ms` | 60000 | Per-request client timeout |

What one run does:

1. Creates a fresh show (hot seats plus greedy seats, `per_user_limit` 4) and mints one token per user.
2. Fires the wave: a hot-seat storm, same-key retries, and one greedy user firing 20 parallel reserves.
   It behaves like a well-behaved client: `429` waits for `Retry-After`; a dropped connection or proxy
   error is retried with the **same** idempotency key, so a lost `201` comes back as a `200` replay.
3. Prints the send rate, status and decline-reason distribution, latency percentiles, then checks:
   - exactly one winning reservation per hot seat, everyone else `409 seat_taken`;
   - zero `5xx` from the application on any attempt, and every request answered;
   - a retried key never creates a second reservation, every replay returns the original;
   - same key with different seats returns `409 idempotency_key_reused`;
   - the greedy user ends with exactly 4 seats;
   - `available + held + confirmed == total_seats`, and confirmed seats equal seats sold in responses;
   - `reservations_confirmed_total` grew by exactly the number of reservations, and the server did not restart.
4. Writes every response to `burst/last-run.json` (git-ignored). Exit code `0` only if every check passes.

---

## API

Identity always comes from the bearer token; a `user_id` in a request body is ignored. All money is
integer paise. Errors share one shape: `{"error": "seat_taken", "message": "...", "request_id": "..."}`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/auth/token` | none (`X-Admin-Secret` for admin) | Dev token issuer standing in for a real identity provider |
| `POST` | `/shows` | admin | Create a show; every seat starts `available` |
| `GET` | `/shows/{id}` | none | Per-seat status and counts |
| `POST` | `/shows/{id}/reserve` | user | Reserve seats, idempotent |
| `POST` | `/reservations/{id}/cancel` | owner | Release seats; idempotent |
| `GET` | `/auth/me` | user | Echo the identity derived from the token |

### Reserve outcomes

| Status | Meaning |
|---|---|
| `201` | Reserved. Body: `reservation_id, show_id, user_id, seats, amount_paise, status: "confirmed"` |
| `200` | Same idempotency key and same seats as an earlier success: the original reservation, nothing new |
| `409 seat_taken` | At least one requested seat belongs to someone else; nothing was reserved (all-or-nothing) |
| `409 per_user_limit` | Would exceed the show's per-user limit |
| `409 idempotency_key_reused` | Key already used for different seats |
| `400` | Validation error (`missing_idempotency_key`, `duplicate_seats`, field errors) |
| `429 server_busy` | Back-pressure under overload, with `Retry-After`; safe to retry with the same key |

### Walkthrough

```bash
BASE=https://seat-reservation-67bc.onrender.com
ADMIN_SECRET=demo-admin-doFb4TyqogkfK2aGxa1agyFR

# Admin token and a show
ADMIN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -H "X-Admin-Secret: $ADMIN_SECRET" -d '{"user_id":"ops","role":"ADMIN"}' | jq -r .access_token)
SHOW=$(curl -s -X POST $BASE/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A12","A13"],"price_paise":25000}' | jq -r .id)

# User token and a reservation (the key may also be sent as "idempotency_key" in the body)
ALICE=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}' | jq -r .access_token)
curl -s -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1' -d '{"seats":["A12"]}'

# Show state: per-seat status and counts
curl -s $BASE/shows/$SHOW
```

---

## Run locally

**Docker (same image as production):**

```bash
cp .env.example .env      # set DB_PASSWORD, JWT_SECRET (32+ chars), ADMIN_SECRET
docker compose up --build
curl localhost:8080/health/readiness
```

**Without Docker:** Java 21 and a PostgreSQL 16 database, configured through `.env`:

```bash
./mvnw spring-boot:run    # Windows: .\mvnw.cmd spring-boot:run
```

Flyway creates the schema on startup.

### Tests

```bash
./mvnw clean verify
```

Integration tests start a real embedded PostgreSQL process, so no Docker or database setup is needed. They
cover concurrent races for the same seat, the per-user limit under parallel requests, idempotent replays,
spoofed identity, owner-only cancel, database timeouts, readiness failing closed, and the metrics.

---

## Observability

**Metrics** (`GET /metrics`, Prometheus text format):

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | Reservations created |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `key_reused` |
| `seats_available`, `seats_held`, `seats_confirmed` | gauge | Current seat counts, read from the database |
| `http_server_requests_seconds` | histogram | Request count, latency and status per endpoint |
| `http_requests_shed_total`, `http_requests_queued` | counter / gauge | Admission control under overload |

**Logs:** one JSON object per line on stdout. Every line carries `request_id` (taken from a well-formed
`X-Request-Id` header or generated, and echoed back in the response), and `user_id` once authenticated.
Tokens and secrets are never logged.

```json
{"timestamp":"2026-10-04T14:51:02.114Z","level":"INFO","logger_name":"c.s.service.ReservationService","message":"reservation confirmed","request_id":"4b0c...","user_id":"alice","reservation_id":"9f1e...","show_id":"2d7a...","seats":["A12"],"service":"seat-booking"}
```

Render does not offer public log access, so the logs under a live burst are shown in a screen recording:
_link to be added_.

---

## Configuration

All configuration comes from environment variables (see [`.env.example`](.env.example)); no secrets live in
the repository.

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SSL_MODE` | local values | PostgreSQL connection |
| `DB_POOL_SIZE` | 20 | Fixed connection pool size |
| `JWT_SECRET`, `JWT_TTL` | none, `1h` | Token signing (32+ characters) |
| `AUTH_DEV_TOKEN_ENABLED` | `false` | Enables `POST /auth/token` |
| `ADMIN_SECRET` | none | Required to mint admin tokens |
| `MAX_CONCURRENT_REQUESTS`, `REQUEST_QUEUE_TIMEOUT` | 32, `30s` | Admission control |
| `DB_STATEMENT_TIMEOUT_MS`, `DB_LOCK_TIMEOUT_MS` | 5000, 3000 | Enforced by PostgreSQL on every connection |
| `SOLD_SEAT_CACHE_TTL` | `10s` | How long a sold seat is declined from memory |

## Project layout

```text
src/main/java/com/seatbooking/
  controller/     HTTP endpoints
  service/        Reservation, show and auth logic
  repository/     All SQL (JdbcClient)
  cache/          Show, sold-seat, used-key caches (decline-only fast paths)
  resilience/     Admission control (load shedding)
  security/       JWT issue/verify, auth filter
  observability/  Correlation id, request logging, metrics
  exception/      Error model and global handler
src/main/resources/db/migration/   Flyway schema
burst/burst.mjs, burst.sh          One-command burst
```
