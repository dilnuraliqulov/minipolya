--liquibase formatted sql

--changeset minipolya:012-create-reviews
--comment: Stadium reviews — one per user per stadium, with an optional owner reply.
CREATE TABLE reviews (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id     UUID NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES users (id),
    booking_id     UUID REFERENCES bookings (id),
    rating         INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment        TEXT,
    owner_reply    TEXT,
    owner_reply_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_review_user_stadium UNIQUE (stadium_id, user_id)
);

CREATE INDEX idx_reviews_stadium_id ON reviews (stadium_id);
CREATE INDEX idx_reviews_user_id ON reviews (user_id);

--changeset minipolya:012-create-favorites
--comment: A user's favorited stadiums.
CREATE TABLE favorites (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id),
    stadium_id UUID NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorite_user_stadium UNIQUE (user_id, stadium_id)
);

CREATE INDEX idx_favorites_user_id ON favorites (user_id);
CREATE INDEX idx_favorites_stadium_id ON favorites (stadium_id);
