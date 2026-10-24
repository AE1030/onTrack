-- Allow Microsoft calendar accounts alongside Google ones.
--
-- The table already permits one account per provider per student (the unique constraint on
-- (student_id, provider)); only this CHECK, generated from the old single-value enum, stood in
-- the way.

ALTER TABLE calendar_account DROP CONSTRAINT calendar_account_provider_check;

ALTER TABLE calendar_account
    ADD CONSTRAINT calendar_account_provider_check
    CHECK (provider IN ('GOOGLE', 'MICROSOFT'));
