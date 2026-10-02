# Production cutover to Flyway (retired)

This runbook is no longer needed. It described how to bring a live, populated production database
(still running `ddl-auto=update`, with Flyway disabled) under Flyway's control without losing data:
back up, verify a generated baseline against the real schema, rehearse the full migration chain
against a restored copy, then flip `SPRING_FLYWAY_ENABLED=true` on the real thing.

That production database was reset, and nothing has been deployed to the cloud since. There is no
live schema or data left to protect, so the careful stamp-and-skip, back-fill-then-drop procedure
this document walked through no longer applies to anything.

`V1__baseline_schema.sql` now captures the current schema directly (see its own header for how),
and every profile, dev, test and prod, runs `spring.flyway.enabled=true` with
`spring.jpa.hibernate.ddl-auto=validate`. A fresh deploy just runs V1 like any other environment.

If production ever again needs to be reconciled with a schema that predates its migrations, the
technique this document used (generate a baseline, diff it against the real catalog with
`information_schema`, rehearse against a restored copy before touching the real database) is still
the right one. Write a new runbook for that situation rather than reviving this one; the specifics
here (which migrations existed, what they dropped, what production was assumed to be) are all
stale.
