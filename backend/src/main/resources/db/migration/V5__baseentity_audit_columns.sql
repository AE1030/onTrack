-- Add BaseEntity's audit columns to every remaining table.
--
-- These tables had no audit timestamps at all before BaseEntity, so this is a plain addition
-- with nothing to carry over -- unlike users (V2) and the two due_date tables (V3), which owned
-- earlier, differently-named or differently-typed columns and needed conversion instead.
--
-- Both columns are nullable, as BaseEntity documents: rows written before this migration have no
-- value, so a NOT NULL constraint could not be applied without inventing timestamps for them.
--
-- user_role is absent on purpose. It is a @JoinTable, not an entity, so nothing maps audit
-- columns onto it and Hibernate would flag them as unmapped.

ALTER TABLE student            ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

ALTER TABLE role               ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

ALTER TABLE secure_token       ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

ALTER TABLE course             ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

ALTER TABLE course_enrollement ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

ALTER TABLE past_course        ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

ALTER TABLE calendar_account   ADD COLUMN created_at timestamp(6) with time zone,
                               ADD COLUMN updated_at timestamp(6) with time zone;

-- Not dropped: the foreign keys past_course.student_id -> student and
-- due_date_override.student_id -> student. UserOwnedEntity maps student_id as a plain column
-- rather than a @ManyToOne, so Hibernate would no longer create these -- but it does not validate
-- foreign keys either, so keeping them costs nothing and they still enforce that an owner id
-- refers to a student that exists. Removing real referential integrity to match a generated
-- script would be the wrong trade.
