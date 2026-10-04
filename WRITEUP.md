# Write-up

## 1. The atomic decision

**Mechanism:** a conditional `UPDATE` guarded on current state, over rows locked in a deterministic order,
inside one PostgreSQL transaction.

```sql
WITH locked AS (
    SELECT seat_label FROM seats
    WHERE show_id = ? AND seat_label = ANY (?) AND status = 'AVAILABLE'
    ORDER BY seat_label
    FOR UPDATE
)
UPDATE seats s SET status = 'CONFIRMED', reservation_id = ?
FROM locked l WHERE s.show_id = ? AND s.seat_label = l.seat_label
RETURNING s.seat_label
```

**Why it is race-free.** Every seat is exactly one row (primary key `(show_id, seat_label)`), so there is
exactly one place a sale can be recorded. `FOR UPDATE` makes concurrent buyers of the same seat queue on
that row lock. When the winner commits, PostgreSQL re-evaluates `status = 'AVAILABLE'` for every waiter
against the committed row (READ COMMITTED re-check), finds it false, and the waiter's `UPDATE` touches zero
rows for that seat. There is no read-then-write in application code: "is it free?" and "take it" are the
same statement. A seat row can hold only one `reservation_id`, so two confirmations for one seat cannot
even be represented.

**Multi-seat requests (all-or-nothing).** The service compares the number of rows returned with the
number requested. If any seat was missed, it throws and the whole transaction rolls back, including the
reservation row, so a request for `["A12","A13"]` with A13 taken leaves A12 available. Details and the
reasoning for all-or-nothing over best-effort are in [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md).

**Deadlock avoidance.** Locks are always acquired in the same global order:
1. a per-user advisory lock, `pg_advisory_xact_lock(hash(show_id:user_id))`, taken first;
2. seat rows, in `seat_label` order, inside one statement.

Two requests for `{A1, A2}` and `{A2, A1}` both lock A1 first, so neither can hold A2 while waiting for A1.
The advisory lock is per user, so different users never contend on it; a hot-seat storm from many users
stays parallel. `lock_timeout` (3 s) is a backstop: a lock wait that somehow exceeds it is answered `409`,
never a hung request.

**Per-user limit.** The advisory lock serialises one user's own reserves for a show, so "count my seats,
then claim" cannot interleave with another of the same user's requests. Ten parallel requests from one
user on a limit-4 show run one after another inside the database and end with exactly 4 seats.

**Doing less work for losers.** In a storm almost every request loses, so the expensive path is reserved
for requests that can still win. A seat already confirmed to someone else is declined from an in-memory
cache (no database connection at all), then by a plain lock-free read. Both shortcuts can only *decline*;
every sale is still decided by the conditional update above, so a stale cache can never cause a
double-sell. See "Behaviour Under Overload" in `DESIGN_DECISIONS.md`.

## 2. Idempotency

**Where the key is stored:** on the `reservations` row, with `UNIQUE (user_id, idempotency_key)`. Keys
are scoped per user (taken from the token), so one user cannot collide with or probe another's keys.

**How exactly-once is enforced:** the reserve transaction starts with
`INSERT ... ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id`. The unique index, not
application logic, decides which attempt owns the key. If two retries of the same key race, they also
serialise on the per-user advisory lock; the second one finds the committed row and replays it.

**Same key, different body:** alongside the key we store `request_hash`, a SHA-256 of the show id plus the
sorted seat labels. A retry with a matching hash returns the original reservation with `200` (no new
row, no new charge, counted as `idempotent_replay`). A different hash returns
`409 idempotency_key_reused`.

**Declines are not remembered.** A declined attempt rolls back its reservation row, so the key is free
to be retried later. Nothing was reserved, so nothing can be double-charged.

**Lost responses.** If a `201` is lost on the network, the client retries with the same key and receives
the same reservation as a `200`. The burst script does exactly this and counts winners by distinct
reservation id.

## 3. Holds and expiry

Reserve confirms immediately (the brief's `201` example returns `"status": "confirmed"`), and seats are
released by an explicit, owner-only `POST /reservations/{id}/cancel`.

- **Owner only:** the reservation is looked up by id *and* the token's user id; anyone else gets `404`,
  which also avoids revealing that the id exists.
- **Release exactly once:** the cancel `UPDATE` is guarded on `status = 'CONFIRMED'`, so concurrent or
  repeated cancels release the seats once and later calls just return the cancelled reservation.
- **Never resurrects someone else's seat:** the release is `UPDATE seats ... WHERE reservation_id = ?`,
  scoped to this reservation's own seats.
- **Cleanly re-bookable:** released seats return to `available` in the same transaction, and the
  in-memory sold-seat cache is evicted right after commit.

`counts.held` is therefore always 0; it stays in `GET /shows/{id}` because the reconciliation invariant
`available + held + confirmed == total_seats` is defined over all three states. A time-boxed hold
(hold, pay, confirm, with a sweeper or lazy expiry) is the first thing I would add for a real payment
flow; see section 7.

## 4. Consistency vs availability under a partition

The system chooses **consistency**. PostgreSQL is the single source of truth and the only place a sale is
decided.

- **App cannot reach the database:** readiness reports `DOWN` (the DB check is part of the readiness
  group), so the platform stops routing traffic to that instance. Liveness stays `UP`, so the instance is
  not restarted in a loop and recovers by itself when the database returns. Any request that still arrives
  gets `503 database_unavailable` or `429` (pool timeout); the service never "sells optimistically" and
  reconciles later.
- **Multiple app instances:** they share nothing except the database. The per-instance caches only
  decline, and only seats whose sale is already committed, so instances can disagree for a few seconds
  without ever selling a seat twice. The worst case is a seat cancelled on instance A that instance B
  still declines from cache for up to `SOLD_SEAT_CACHE_TTL` (10 s). That is a brief availability loss,
  never a correctness loss.
- **Client cannot reach the app (or loses the response):** idempotency keys make retries safe, so the
  client can keep retrying until it gets an answer without risking a second charge.

## 5. Observability: what would page me at 2am

Signals come from `/metrics` (Prometheus), structured JSON logs with `request_id`, and the health probes.

| Alert | Signal | Why it matters |
|---|---|---|
| Any 5xx | `http_server_requests_seconds_count{status=~"5.."}` rate > 0 | Declines must be 4xx; a 5xx means a bug or an outage |
| Readiness failing | `/health/readiness` not 200 for 1 min | Database unreachable: we are refusing all sales |
| Reconciliation drift | `seats_available + seats_held + seats_confirmed` != total seats | The invariant broke; stop sales and investigate |
| Restarts | `process_start_time_seconds` changed | OOM or crash loop mid on-sale |
| Sales stalled | `reservations_confirmed_total` flat while reserve traffic is high and seats remain | Something blocks the claim path (locks, pool) |
| Sustained shedding | `http_requests_shed_total` rate high for 5+ min | Capacity: scale out or raise limits |
| Latency | p99 of `/shows/{id}/reserve` above SLO | Pool or lock contention building up |

Not paged, but watched on a dashboard: declines by reason (a jump in `key_reused` hints at a buggy client
SDK, a jump in `per_user_limit` at scalpers), queue depth (`http_requests_queued`), and the pool
metrics. Logs answer "what happened to this request": the `request_id` is echoed in every response and
error body, so a user's complaint maps to one grep.

## 6. AI usage

I used an AI coding agent (Cursor) as a pair programmer for implementation speed. The engineering
direction, the review and the verification were mine.

**How I ran the work:**
- **Standards first.** Before any code, I set an engineering-standards rule set for the agent: layered
  architecture, one error model, structured logging with correlation ids, health and metrics, env-based
  config, tests. Everything generated had to meet it.
- **Plan, then one step at a time.** I reviewed the plan before allowing implementation, then sequenced
  the build myself: infrastructure and database, auth, create show and health, and finally the reserve
  path with an explicit review of every bottleneck. Each step was reviewed before the next and committed
  per feature.
- **Pushing back on over-engineering.** I cut what didn't earn its place: a path-constants class, separate
  401/403 handlers (folded into the global handler), versioned auth paths. I kept flat response bodies and
  fixed the API contract to snake_case.
- **Infrastructure calls.** Render with Render PostgreSQL, a Dockerfile for the platform plus
  docker-compose for a one-command local stack, and all configuration through environment variables.
- **Measured, not assumed.** I insisted on a baseline burst against the live deployment with the
  unchanged code before any tuning. It fell over at 20k requests, and that evidence drove the hardening
  (admission control, cheap declines for losers, bounded memory and timeouts, keep-alive tuning). Each
  change was re-measured on the live service, and I chose which fixes to make after every run.

**What the AI produced:** most of the code, SQL, tests, the burst script and first drafts of these
documents. The core concurrency design (conditional update over rows locked in a fixed order, unique-index
idempotency, a per-user advisory lock) is the established approach for this problem. I validated it
against the race scenarios in the brief with concurrency integration tests and live 20k bursts before
accepting it.

## 7. What I would do next

1. **Time-boxed holds with payment:** `HELD` with an expiry, a confirm endpoint, and lazy expiry inside
   the claim (`status = 'AVAILABLE' OR (status = 'HELD' AND held_until < now())`) so no sweeper is
   needed for correctness.
2. **Per-show gauges and an invariant check job** that alerts on drift per show, not only globally.
3. **Horizontal scale:** several instances behind the load balancer, a shared cache (Redis) for the
   decline fast path, and PgBouncer in front of PostgreSQL so connections do not grow with instances.
4. **Real identity:** an OIDC provider instead of the dev token endpoint; per-user rate limits.
5. **Events:** a transactional outbox for "reservation confirmed / cancelled" so payments and
   notifications are driven reliably from the same commit.
6. **Load testing in CI:** a smaller burst against an ephemeral environment on every merge, with the
   burst checks as the pass/fail gate.
