-- calendar_account: repoint ownership from user_id to student_id.
--
-- Derived from docs/db/runbooks/calendar_account_user_to_student.sql, which was written to be
-- pasted into psql by hand before this project had a migration tool. Its logic is preserved;
-- see step 5 for the one thing it was missing.
--
-- This table keyed off user_id, a different id space from every other owned table, which is why
-- it could not join the @Filter tenancy contract. GoogleCalendarAccount now extends
-- UserOwnedEntity, whose column is student_id.

-- 1. Add the new column nullable, so existing rows survive the statement.
ALTER TABLE calendar_account ADD COLUMN student_id BIGINT;

-- 2. Back-fill by walking user_id -> student.user_id -> student.id.
UPDATE calendar_account ca
SET    student_id = s.id
FROM   student s
WHERE  s.user_id = ca.user_id
  AND  ca.student_id IS NULL;

-- 3. Refuse to continue if anything failed to map. A row with no owner cannot satisfy the NOT NULL
--    below, and silently deleting a user's calendar link is not an acceptable way to make a
--    migration pass. If this fires, inspect the rows before deciding to repair or delete them:
--        SELECT * FROM calendar_account WHERE student_id IS NULL;
DO $$
DECLARE orphans BIGINT;
BEGIN
    SELECT count(*) INTO orphans FROM calendar_account WHERE student_id IS NULL;
    IF orphans > 0 THEN
        RAISE EXCEPTION 'calendar_account: % row(s) have no matching student; resolve before proceeding', orphans;
    END IF;
END $$;

ALTER TABLE calendar_account ALTER COLUMN student_id SET NOT NULL;

-- 4. Move uniqueness onto the new owner. Leaving it on user_id would keep a constraint pointed at
--    a column the application no longer writes.
ALTER TABLE calendar_account DROP CONSTRAINT IF EXISTS idx_calendar_account_user_provider;
DROP INDEX IF EXISTS idx_calendar_account_user_provider;
ALTER TABLE calendar_account
    ADD CONSTRAINT idx_calendar_account_student_provider UNIQUE (student_id, provider);

-- 5. Release the NOT NULL on user_id. NOT in the original runbook, and without it this migration
--    breaks calendar linking outright: user_id is retained below as a rollback path, but
--    GoogleCalendarAccount no longer maps that column at all, so every INSERT the application
--    issues from here on omits it and dies on the constraint. The column has to become optional
--    at the same moment it stops being written.
ALTER TABLE calendar_account ALTER COLUMN user_id DROP NOT NULL;

-- 6. user_id is deliberately kept for one release as a rollback path, per the original runbook.
--    Drop it in a follow-up migration once the new column has been proven in production:
--        ALTER TABLE calendar_account DROP COLUMN user_id;
