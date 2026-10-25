--liquibase formatted sql

--changeset minipolya:024-rename-cancellation-hours
--comment: Cancellation is all-or-nothing now, so full_refund_hours becomes the single cancellation_hours window. Default stays 24.
ALTER TABLE stadiums RENAME COLUMN full_refund_hours TO cancellation_hours;

--changeset minipolya:024-drop-partial-refund
--comment: Partial refunds are gone: cancelling at least cancellation_hours before the start refunds every card payment in full, later refunds nothing.
ALTER TABLE stadiums DROP COLUMN partial_refund_hours;
ALTER TABLE stadiums DROP COLUMN partial_refund_percent;

--changeset minipolya:024-cancellation-hours-check
--comment: A negative window is meaningless; zero means cancellation is free right up to kick-off.
ALTER TABLE stadiums
    ADD CONSTRAINT chk_stadiums_cancellation_hours CHECK (cancellation_hours >= 0);
