-- due_date_override and due_date_consensus: convert their pre-existing audit timestamps.
--
-- These two tables are the only ones that already owned a created_at before BaseEntity existed.
-- They declared it as LocalDateTime, so the column is `timestamp without time zone`; BaseEntity
-- maps Instant, which is `timestamp with time zone`. That is a type change, not a column
-- addition, which is exactly what ddl-auto=update cannot do -- it would have left both tables
-- silently mismatched against the mapping.
--
-- Every other table gets these columns fresh in V5.
--
-- AT TIME ZONE 'UTC' is doing real work in each statement below. The existing values were written
-- by LocalDateTime.now() on a UTC container, so they are UTC wall-clock readings with no zone
-- attached. Reinterpreting them as UTC preserves the instant. A bare cast would apply the
-- session's timezone and shift every historical row by the local offset.

-- ---------------------------------------------------------------- due_date_override
ALTER TABLE due_date_override
    ALTER COLUMN created_at TYPE timestamp(6) with time zone
    USING created_at AT TIME ZONE 'UTC';

ALTER TABLE due_date_override ADD COLUMN updated_at timestamp(6) with time zone;

-- last_modified_at is BaseEntity's updated_at under the old name.
UPDATE due_date_override
SET    updated_at = last_modified_at AT TIME ZONE 'UTC'
WHERE  last_modified_at IS NOT NULL;

ALTER TABLE due_date_override DROP COLUMN last_modified_at;

-- ---------------------------------------------------------------- due_date_consensus
ALTER TABLE due_date_consensus
    ALTER COLUMN created_at TYPE timestamp(6) with time zone
    USING created_at AT TIME ZONE 'UTC';

-- This table never had a modified-timestamp to carry over, so the column starts empty.
ALTER TABLE due_date_consensus ADD COLUMN updated_at timestamp(6) with time zone;
