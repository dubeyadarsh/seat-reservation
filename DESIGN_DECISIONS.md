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