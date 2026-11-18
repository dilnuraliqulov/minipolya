--liquibase formatted sql

--changeset minipolya:053-stadium-weekly-hours
--comment: A stadium's weekly opening schedule, one row per day of the week (0 = Monday). A closing time at or before the opening time means the next morning (06:00 at the latest); 00:00 means midnight. The hours after midnight belong to the working day that opened them. The legacy stadiums.open_time / close_time stay and keep being written from the first open day, so older readers and clients keep working.
CREATE TABLE stadium_hours
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id  UUID     NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,
    closed      BOOLEAN  NOT NULL DEFAULT FALSE,
    open_time   TIME,
    close_time  TIME,
    CONSTRAINT uq_stadium_hours_day UNIQUE (stadium_id, day_of_week),
    CONSTRAINT chk_stadium_hours_day CHECK (day_of_week BETWEEN 0 AND 6),
    CONSTRAINT chk_stadium_hours_times CHECK (
        (closed AND open_time IS NULL AND close_time IS NULL)
            OR (NOT closed AND open_time IS NOT NULL AND close_time IS NOT NULL))
);
CREATE INDEX idx_stadium_hours_stadium ON stadium_hours (stadium_id);

--changeset minipolya:053-stadium-weekly-hours-backfill
--comment: Existing stadiums keep the hours they already have, the same every day.
INSERT INTO stadium_hours (stadium_id, day_of_week, closed, open_time, close_time)
SELECT s.id, d.day_of_week, FALSE, s.open_time, s.close_time
FROM stadiums s
         CROSS JOIN generate_series(0, 6) AS d(day_of_week);

--changeset minipolya:053-stadium-weekly-hours-day-type
--comment: day_of_week was created SMALLINT above; every other day_of_week in this schema (pricing_rules, booking_series) is INTEGER, and the JPA entity maps a Java int, which Hibernate's ddl-auto=validate checks. The changeset above had already been applied, so the type is corrected here rather than edited there.
ALTER TABLE stadium_hours
    ALTER COLUMN day_of_week TYPE INTEGER;
