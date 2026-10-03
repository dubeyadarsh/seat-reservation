CREATE TABLE shows (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(200) NOT NULL,
    price_paise      BIGINT       NOT NULL CHECK (price_paise > 0),
    per_user_limit   INT          NOT NULL DEFAULT 4   CHECK (per_user_limit > 0),
    hold_ttl_seconds INT          NOT NULL DEFAULT 120 CHECK (hold_ttl_seconds > 0),
    total_seats      INT          NOT NULL CHECK (total_seats > 0),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Supports newest-first pagination of GET /shows.
CREATE INDEX idx_shows_created_at ON shows (created_at DESC, id);

CREATE TABLE seats (
    show_id    UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    seat_label VARCHAR(16) NOT NULL,
    position   INT         NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
               CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One row per physical seat: a seat label cannot exist twice in a show.
    PRIMARY KEY (show_id, seat_label),
    CONSTRAINT uq_seats_show_position UNIQUE (show_id, position)
);

-- Per-status counts for GET /shows/{id} and the seats gauge.
CREATE INDEX idx_seats_show_status ON seats (show_id, status);
