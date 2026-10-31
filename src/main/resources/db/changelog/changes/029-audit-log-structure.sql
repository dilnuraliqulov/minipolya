--liquibase formatted sql

--changeset minipolya:029-audit-log-structure
--comment: Who did what and exactly what changed. actor_type says in which role the actor acted (SYSTEM for scheduled jobs and automatic consequences, with no user). actor_name and entity_label are snapshots taken at the time, so an entry stays readable after a rename. changes is a JSON array of {field, oldValue, newValue}; reason is the free text an action carries (reject, block, cancel). Rows written before this changeset keep their details and get NULL here.
ALTER TABLE audit_log ADD COLUMN actor_type   VARCHAR(10)
    CHECK (actor_type IN ('ADMIN', 'OWNER', 'PLAYER', 'SYSTEM'));
ALTER TABLE audit_log ADD COLUMN actor_name   VARCHAR(120);
ALTER TABLE audit_log ADD COLUMN entity_label VARCHAR(255);
ALTER TABLE audit_log ADD COLUMN changes      TEXT;
ALTER TABLE audit_log ADD COLUMN reason       TEXT;

CREATE INDEX idx_audit_log_actor_type ON audit_log (actor_type);
CREATE INDEX idx_audit_log_entity     ON audit_log (entity_type, entity_id);
