--liquibase formatted sql

--changeset minipolya:038-stadiums-rejected-status
--comment: A stadium an admin turns down at review is now 'rejected', no longer 'blocked' (which stays for a stadium taken off after approval). Like blocked, it is invisible to players. status_reason is the admin's reason for the current rejected or blocked status, which the owner sees; it is cleared when the stadium is approved or set back to pending.
ALTER TABLE stadiums DROP CONSTRAINT stadiums_status_check;
ALTER TABLE stadiums ADD CONSTRAINT stadiums_status_check
    CHECK (status IN ('pending', 'approved', 'rejected', 'blocked'));
ALTER TABLE stadiums ADD COLUMN status_reason TEXT;

--changeset minipolya:038-stadiums-rejected-backfill
--comment: Rejections made before the rejected status existed were stored as 'blocked', told apart only by the audit action. A blocked stadium whose latest status action is STADIUM_REJECTED becomes 'rejected'. Every blocked or rejected stadium takes its status_reason from that latest action's reason. Older free-form audit rows (before changeset 029) say nothing either way, so those stadiums stay blocked.
CREATE TEMPORARY TABLE latest_stadium_status_action ON COMMIT DROP AS
SELECT DISTINCT ON (a.entity_id) a.entity_id AS stadium_id, a.action, a.reason
FROM audit_log a
WHERE a.entity_type = 'stadium'
  AND a.entity_id IS NOT NULL
  AND a.action IN ('STADIUM_APPROVED', 'STADIUM_REJECTED', 'STADIUM_BLOCKED',
                   'STADIUM_UNBLOCKED', 'STADIUM_STATUS_CHANGED')
ORDER BY a.entity_id, a.created_at DESC, a.id DESC;

UPDATE stadiums s
SET status = 'rejected'
FROM latest_stadium_status_action l
WHERE l.stadium_id = s.id
  AND s.status = 'blocked'
  AND l.action = 'STADIUM_REJECTED';

UPDATE stadiums s
SET status_reason = l.reason
FROM latest_stadium_status_action l
WHERE l.stadium_id = s.id
  AND s.status IN ('blocked', 'rejected')
  AND l.action IN ('STADIUM_REJECTED', 'STADIUM_BLOCKED');
