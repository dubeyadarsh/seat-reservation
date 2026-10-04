# Design Decisions

## Booking Behaviour: All-or-Nothing

A multi-seat request is treated as one unit: either every requested seat is held, or none is.

**Why:**
- Movie tickets are usually booked by groups such as families, friends, or office teams who want to attend together. Leaving one member without a seat defeats the purpose of the booking.
- A partial result would force the user to decide again, or cancel and rebook, while the held seats stay blocked for others.
- Atomic behaviour is simpler to reason about and to prove correct under concurrency.

**Alternative considered:** best-effort (partial success). Rejected because an incomplete group booking has no value to the user.

**How it holds under concurrency:** one database transaction with ordered row locks and a conditional update. If the updated row count differs from the requested seat count, the transaction is rolled back.

**Trade-off:** one unavailable seat rejects the whole request. The `409` response lists which seats failed so the user can retry with alternatives.

**Example:** request `["A12", "A13"]` where A13 is taken returns `409 Conflict`, and A12 stays available.

## Release Model: Immediate Confirm + Owner-Only Cancel

The brief allows either an explicit cancel or a time-boxed hold that auto-expires. Reserve confirms
immediately, and seats are released only by `POST /reservations/{id}/cancel`.

**Why:**
- The `201` example in the brief returns `"status": "confirmed"`, so the response matches it exactly.
- No background expiry sweeper, no separate confirm endpoint, and no "held but expired" case to get
  right inside the atomic claim. Less machinery means fewer ways to break the correctness bar.
- Release is still proven re-bookable: cancelling returns the seats to `available` immediately.

**Trade-off:** `counts.held` in `GET /shows/{id}` is always `0`. The bucket stays in the response
because the reconciliation invariant is stated in terms of all three states.

**Safety:** a release updates only seats whose `reservation_id` matches, so it can never resurrect a
seat already confirmed to someone else, and the cancel itself is guarded on `status = 'CONFIRMED'`
so concurrent cancels release exactly once.

## Idempotency

The key is stored on `reservations` with `UNIQUE (user_id, idempotency_key)`, so exactly-once is
decided by the database rather than by application logic. Alongside it we store a SHA-256
`request_hash` of the show id plus the sorted seat labels: a replay with a matching hash returns the
original reservation with `200`, while the same key with different seats is rejected with `409`.
Keys are scoped per user, so two users may independently use the same key.

A declined request rolls back, which also removes its reservation row. That is intentional: nothing
was reserved, so retrying the same key should be allowed to try again.

## Behaviour Under Overload

A 20k burst on one small instance is far more traffic than it can serve at once. The goal is that
every request still gets a correct, fast-failing answer (`201`, `200` replay, `409`, or `429` with
`Retry-After`) and never a `5xx`, a dropped connection or a restart.

**Cheapest answer first.** In a hot-seat storm almost every request loses, so the reserve path is
ordered by cost:
1. **Sold-seat cache:** a seat already confirmed to someone else is declined from memory, with no
   database connection at all.
2. **Plain reads:** an idempotent retry is answered from the reservation row; a seat the database
   reports as taken is declined, and its owner is written to the cache for the next loser.
3. **Transaction:** only a request that can still win takes the per-user lock, inserts and claims.

**Admission control.** At most `MAX_CONCURRENT_REQUESTS` requests do work at once; the rest wait in
a fair queue as parked virtual threads, before authentication or body parsing. Health probes and
`/metrics` bypass the queue, so the platform never restarts a healthy instance because its health
check was stuck behind the burst. A request still waiting after `REQUEST_QUEUE_TIMEOUT` gets `429`.

**Bounded everything.** Fixed-size connection pool with a 5 s acquisition timeout; PostgreSQL
`statement_timeout` and `lock_timeout` on every connection; size-bounded caches; JVM memory regions
capped to fit a 512 MB container. Pool exhaustion and statement timeouts answer `429`, lock timeouts
`409`; only a database that is actually unreachable answers `503`.

**Why the cache cannot break correctness:** the database's conditional update still decides every
win. The cache only holds seats whose confirmation is already committed, so it can wrongly decline a
seat only within `SOLD_SEAT_CACHE_TTL` (default 10 s) after a cancel on a *different* instance;
cancels on the same instance evict immediately.

**Trade-off:** a user retrying an old idempotency key for a seat that was cancelled and then booked by
someone else gets `409 seat_taken` instead of a `200` replay of the cancelled reservation. Both are
correct statements about the seat; the fast path is worth this one edge case.