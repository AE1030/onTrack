-- users: move onto BaseEntity's audit columns, and correct the auditor column type.
--
-- Three separate problems, all created by ddl-auto=update's refusal to do anything but ADD:
--
--  1. BaseEntity renames the audit timestamps created_date/last_modified_date -> created_at/
--     updated_at. `update` would have added the new pair and left the old pair populated and
--     orphaned, so the history would appear to start at the deploy.
--  2. created_by/last_modified_by are varchar, but AuditorAwareImpl has always been an
--     AuditorAware<Long> -- Spring Data was coercing the id to a String on every audited write.
--     The new mapping declares Long. `update` never alters a column type, so without this the
--     column and the mapping disagree permanently.
--  3. The new columns are timestamptz while the old ones are timestamp; the values must be
--     reinterpreted, not merely copied.

-- 1. Add the new pair. Nullable on purpose: rows written before this migration have no audit
--    values, and BaseEntity documents that a NOT NULL constraint could not be applied to them.
ALTER TABLE users ADD COLUMN created_at timestamp(6) with time zone;
ALTER TABLE users ADD COLUMN updated_at timestamp(6) with time zone;

-- 2. Carry the history across. The old columns are `timestamp without time zone`, written by
--    LocalDateTime.now() on a UTC container, so AT TIME ZONE 'UTC' reinterprets them as the
--    instant they actually represent. Using the session timezone instead would shift every row.
UPDATE users SET created_at = created_date        AT TIME ZONE 'UTC' WHERE created_date IS NOT NULL;
UPDATE users SET updated_at = last_modified_date  AT TIME ZONE 'UTC' WHERE last_modified_date IS NOT NULL;

-- 3. Refuse to continue if the auditor columns hold anything that is not a Users id. The cast
--    below would fail anyway, but with a Postgres type error rather than a statement of what is
--    actually wrong. If this fires, inspect before deciding:
--        SELECT id, created_by, last_modified_by FROM users
--        WHERE created_by ~ '[^0-9-]' OR last_modified_by ~ '[^0-9-]';
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM users
    WHERE (created_by       IS NOT NULL AND created_by       !~ '^-?[0-9]+$')
       OR (last_modified_by IS NOT NULL AND last_modified_by !~ '^-?[0-9]+$');
    IF bad > 0 THEN
        RAISE EXCEPTION 'users: % row(s) have a non-numeric auditor value; cannot convert to bigint', bad;
    END IF;
END $$;

ALTER TABLE users ALTER COLUMN created_by       TYPE bigint USING created_by::bigint;
ALTER TABLE users ALTER COLUMN last_modified_by TYPE bigint USING last_modified_by::bigint;

-- 4. Now the old columns carry nothing the new ones do not.
ALTER TABLE users DROP COLUMN created_date;
ALTER TABLE users DROP COLUMN last_modified_date;
