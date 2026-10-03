-- Reserve confirms immediately and seats are released by an explicit owner-only cancel,
-- so the time-boxed hold column from V1 is never used.
ALTER TABLE shows DROP COLUMN hold_ttl_seconds;

CREATE TABLE reservations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id         UUID         NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id         VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    -- Hash of show id + sorted seat labels: detects the same key replayed with a different body.
    request_hash    CHAR(64)     NOT NULL,
    seat_labels     TEXT[]       NOT NULL,
    amount_paise    BIGINT       NOT NULL CHECK (amount_paise > 0),
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Exactly-once is enforced by the database, not by application logic.
    CONSTRAINT uq_reservations_user_key UNIQUE (user_id, idempotency_key)
);

-- Makes the per-user-limit count a single index lookup inside the reserve transaction.
CREATE INDEX idx_reservations_show_user_status ON reservations (show_id, user_id, status);

ALTER TABLE seats ADD COLUMN reservation_id UUID REFERENCES reservations (id) ON DELETE SET NULL;

-- Cancel releases seats by reservation id.
CREATE INDEX idx_seats_reservation ON seats (reservation_id);
