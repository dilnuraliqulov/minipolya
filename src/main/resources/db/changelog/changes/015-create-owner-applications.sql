--liquibase formatted sql

--changeset minipolya:015-create-owner-applications
--comment: A user's application to become a stadium owner; an admin reviews and approves/rejects.
CREATE TABLE owner_applications (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users (id),
    business_name    VARCHAR(255) NOT NULL,
    business_address TEXT NOT NULL,
    phone            VARCHAR(20) NOT NULL,
    description      TEXT,
    stadium_count    INTEGER NOT NULL DEFAULT 1 CHECK (stadium_count >= 1),
    status           VARCHAR(20) NOT NULL DEFAULT 'pending'
                         CHECK (status IN ('pending', 'under_review', 'approved', 'rejected')),
    reviewed_by      UUID REFERENCES users (id),
    review_note      TEXT,
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_owner_applications_user_id ON owner_applications (user_id);
CREATE INDEX idx_owner_applications_status ON owner_applications (status);
