-- Generalise google_calendar_export into calendar_export: one snapshot stream per connected
-- account rather than one per student.
--
-- With Google and Microsoft both connected, a single per-student snapshot would let one
-- provider's sync record events as pushed that the other never received. Snapshots are now keyed
-- by (student_id, provider, account_email); see CalendarExport for why the email is part of it.

ALTER TABLE google_calendar_export RENAME TO calendar_export;
ALTER TABLE calendar_export RENAME CONSTRAINT google_calendar_export_pkey TO calendar_export_pkey;

ALTER TABLE calendar_export ADD COLUMN provider character varying(255);
ALTER TABLE calendar_export ADD COLUMN account_email character varying(255);

-- Every existing snapshot was a Google one.
UPDATE calendar_export SET provider = 'GOOGLE';

-- Attribute existing snapshots to the Google account currently connected, so that account's next
-- sync diffs against them instead of re-creating every event. A student with no account connected
-- keeps a null email; if they later connect one, it starts from an empty snapshot, which is right
-- because nothing can be known to be in that calendar.
UPDATE calendar_export ce
SET    account_email = ca.email
FROM   calendar_account ca
WHERE  ca.student_id = ce.student_id
  AND  ca.provider = 'GOOGLE';

ALTER TABLE calendar_export ALTER COLUMN provider SET NOT NULL;
ALTER TABLE calendar_export
    ADD CONSTRAINT calendar_export_provider_check
    CHECK (provider IN ('GOOGLE', 'MICROSOFT'));

-- Rename the Google-specific keys inside each stored event:
--   googleEventId    -> externalEventId
--   googleCalendarId -> dropped (it was always "primary"; each strategy now targets the
--                       account's default calendar itself)
-- WITH ORDINALITY keeps the array order. COALESCE because jsonb_agg over zero rows is NULL, not [].
UPDATE calendar_export ce
SET    events = COALESCE((
           SELECT jsonb_agg(
                      (e.value - 'googleEventId' - 'googleCalendarId')
                          || jsonb_build_object('externalEventId', e.value -> 'googleEventId')
                      ORDER BY e.ordinality)
           FROM   jsonb_array_elements(ce.events) WITH ORDINALITY AS e(value, ordinality)
       ), '[]'::jsonb)
WHERE  jsonb_typeof(ce.events) = 'array';

DROP INDEX idx_calendar_export_student_time;
CREATE INDEX idx_calendar_export_owner_account_time
    ON calendar_export USING btree (student_id, provider, account_email, exported_at DESC);
