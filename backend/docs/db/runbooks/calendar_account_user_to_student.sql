-- calendar_account: repoint ownership from user_id to student_id
--
-- RUN THIS BEFORE DEPLOYING THE CODE THAT EXPECTS student_id.
--
-- There is no Flyway/Liquibase in this project and spring.jpa.hibernate.ddl-auto=update only
-- ever ADDS columns -- it never renames, never back-fills, and never drops. It will also fail to
-- add a NOT NULL column to a populated table, log that failure, and continue booting onto a
-- broken schema. So none of the below happens automatically.
--
-- Rehearse against a restored snapshot first. H2 cannot validate this; it needs real Postgres.

BEGIN;

-- 1. Add the new column, nullable for now so existing rows survive the statement.
ALTER TABLE calendar_account ADD COLUMN IF NOT EXISTS student_id BIGINT;

-- 2. Back-fill by walking user_id -> student.user_id -> student.id.
UPDATE calendar_account ca
SET    student_id = s.id
FROM   student s
WHERE  s.user_id = ca.user_id
  AND  ca.student_id IS NULL;

-- 3. Refuse to continue if anything failed to map. A row with no owner cannot satisfy the
--    NOT NULL constraint below, and silently deleting calendar links is not acceptable.
--    If this fires, inspect the offending rows before deciding whether to delete or repair them:
--        SELECT * FROM calendar_account WHERE student_id IS NULL;
DO $$
DECLARE orphans BIGINT;
BEGIN
    SELECT count(*) INTO orphans FROM calendar_account WHERE student_id IS NULL;
    IF orphans > 0 THEN
        RAISE EXCEPTION 'calendar_account: % row(s) have no matching student; resolve before proceeding', orphans;
    END IF;
END $$;

-- 4. Now the constraint can be applied.
ALTER TABLE calendar_account ALTER COLUMN student_id SET NOT NULL;

-- 5. Move uniqueness onto the new owner. Leaving it on user_id would keep a constraint pointed
--    at a column that is about to be dropped.
DROP INDEX IF EXISTS idx_calendar_account_user_provider;
CREATE UNIQUE INDEX IF NOT EXISTS idx_calendar_account_student_provider
    ON calendar_account (student_id, provider);

COMMIT;

-- 6. Deliberately NOT dropped yet. Keep user_id for one release as a rollback path; drop it in a
--    follow-up once the new column has been proven in production:
--        ALTER TABLE calendar_account DROP COLUMN user_id;
