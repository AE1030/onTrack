-- Flag accounts whose authorization the provider has revoked, instead of deleting them.
--
-- A revoked refresh token used to delete the row during a background sync and only log it, so the
-- student was never told and their calendar silently stopped updating. Keeping the row (and its
-- email) lets the app show which account needs reconnecting. Reconnecting replaces the row, which
-- clears the flag.

ALTER TABLE calendar_account ADD COLUMN needs_reconnect boolean NOT NULL DEFAULT false;
