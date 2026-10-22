--liquibase formatted sql

--changeset minipolya:013-create-stadium-closures
--comment: Owner-defined closures (full day when times are null, else a window) that block availability.
CREATE TABLE stadium_closures (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id UUID NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date   DATE NOT NULL,
    start_time TIME,
    end_time   TIME,
    reason     VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_closure_date_order CHECK (end_date >= start_date)
);

CREATE INDEX idx_stadium_closures_stadium_id ON stadium_closures (stadium_id);

--changeset minipolya:013-create-promotions
--comment: Owner discounts applied in availability/pricing. day_of_week 0=Monday..6=Sunday; null fields widen the match.
CREATE TABLE promotions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id       UUID NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    discount_percent INTEGER NOT NULL CHECK (discount_percent > 0 AND discount_percent <= 100),
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ NOT NULL,
    day_of_week      INTEGER CHECK (day_of_week BETWEEN 0 AND 6),
    start_time       TIME,
    end_time         TIME,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_promotion_time_order CHECK (ends_at > starts_at)
);

CREATE INDEX idx_promotions_stadium_id ON promotions (stadium_id);
CREATE INDEX idx_promotions_is_active ON promotions (is_active);
