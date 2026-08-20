# Production cutover to Flyway

Flyway currently runs on **dev and CI only**. Production is untouched: it still boots with
`spring.jpa.hibernate.ddl-auto=update` and `spring.flyway.enabled=false`, exactly as it did before
Flyway was introduced. Deploys to `main` are automatic, and remain a no-op with respect to the
schema.

This document is the procedure for putting production under Flyway. It was written while GCP
billing was suspended and the Cloud SQL instance was unreachable, which is why nothing here has
been executed and why step 3 exists.

## What production is assumed to be

`V1__baseline_prod_schema.sql` reproduces the schema Hibernate generates from the entity classes at
commit `db97797` (`main`). The reasoning: production's database was created empty at `4bb803f`
("First Production commit"), and the only commit since changed no entity — `db97797` touches
`SecurityConfig`, `UserController` and `UserService` only. So production's schema should be
whatever `ddl-auto=update` built from those mappings on an empty database.

**That is an inference, not an observation.** Step 3 is where it gets checked. Everything else
depends on it.

Known to be true of the baseline, and worth confirming on the real database:

- `calendar_account` has `user_id NOT NULL` and no `student_id`
- `users.created_by` and `users.last_modified_by` are `varchar`, not `bigint`
- `users` has `created_date` / `last_modified_date`, and **no** `role_id`
- `due_date_override` and `due_date_consensus` have `created_at` as `timestamp` without time zone
- no table has `updated_at`
- exactly 11 tables — in particular **no** `calendar_event` and no `d2l_session` (those exist only
  on the local dev database, left by entities that lived on branch `f288505` and never reached
  `main`)

## Procedure

### 1. Restore access

Settle the outstanding charges, confirm the Cloud SQL instance is running, and confirm you can
connect with `psql` or the Cloud SQL proxy. Do not proceed from the Cloud Run logs alone — you need
an interactive connection for steps 2 and 3.

### 2. Back up first

Take an on-demand Cloud SQL backup **and** a logical dump you can restore locally:

```bash
pg_dump -h <host> -U <user> -d <db> --schema-only > prod_schema_actual.sql
pg_dump -h <host> -U <user> -d <db>               > prod_full_backup.sql
```

Nothing below is attempted without both. Note that migrations V2–V5 drop columns
(`created_date`, `last_modified_date`, `last_modified_at`) after copying their contents — there is
no in-database undo.

Use a `pg_dump` whose version is >= the server's, or it will refuse to run.

### 3. Verify the baseline against reality — the step this whole plan rests on

```bash
# Build the baseline into a scratch database, then compare catalogs rather than raw SQL text.
createdb baseline_check
psql -d baseline_check -f backend/src/main/resources/db/migration/V1__baseline_prod_schema.sql

Q="select table_name||'.'||column_name||' '||data_type||' null='||is_nullable
   from information_schema.columns where table_schema='public' order by 1;"

psql -d baseline_check -tAc "$Q" > baseline.cols
psql -h <prod-host> -U <user> -d <db> -tAc "$Q" > prod.cols
diff baseline.cols prod.cols
```

Expect no differences.

**If they differ**, fix `V1__baseline_prod_schema.sql` so it matches production. That file has never
run against any long-lived database — dev and CI build from it, and both are disposable — so
correcting it is safe and is much better than writing compensating logic into V2–V5. Re-run the
test suite afterwards; it rebuilds from V1 and will catch a baseline that no longer reaches the
expected end state.

Extra columns that production has and the baseline does not are the likely finding, since
`ddl-auto=update` never drops anything. They are harmless to Hibernate `validate`, but add them to
the baseline anyway so the file stays an accurate record.

### 4. Rehearse on a restored copy

```bash
createdb prod_rehearsal
psql -d prod_rehearsal -f prod_full_backup.sql
```

Point a local run of the application at `prod_rehearsal` with `spring.flyway.enabled=true` and
`ddl-auto=validate`. This is the first time the migrations meet real production data — the V4
back-fill in particular resolves every `calendar_account` row through `student.user_id`, and raises
rather than proceeding if any row fails to map.

If V4 raises, some calendar account has no corresponding student. Inspect before deciding:

```sql
SELECT * FROM calendar_account WHERE student_id IS NULL;
```

Deleting those rows silently unlinks someone's calendar, which is why the migration refuses to
choose for you.

### 5. Deploy with Flyway enabled

Set on the Cloud Run service:

```
SPRING_FLYWAY_ENABLED=true
```

On first boot Flyway finds a non-empty schema with no history table, stamps it at version 1
(`baseline-on-migrate=true`), skips V1, and applies V2 → V5.

### 6. Confirm

```sql
SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;
```

Expect one `BASELINE` row at version 1, then four successful migrations. Then spot-check the
back-fills:

```sql
SELECT count(*) FROM calendar_account WHERE student_id IS NULL;   -- 0
SELECT count(*) FROM users WHERE created_at IS NULL AND id IN (SELECT id FROM users LIMIT 5);
\d users            -- created_by is bigint; created_date is gone
\d calendar_account -- student_id NOT NULL; user_id still present but nullable
```

Then exercise the app: link a Google Calendar account and load a GPA page.

### 7. Only then, switch to validate

As a **separate deploy**, change `spring.jpa.hibernate.ddl-auto` from `update` to `validate` in
`application.properties`.

Keep it separate. Done in the same deploy, a validation failure and a migration failure are
indistinguishable in the logs, and you would be diagnosing a boot loop on a live database. Once
this lands, `update` never runs against production again and the schema is Flyway's alone.

### 8. Mongo, whenever convenient

`docs/db/runbooks/mongo_user_syllabi_extraction_id_reshape.md` is a MongoDB cleanup that Flyway
does not manage. It documents itself as optional — nothing breaks if it is never run. Read it and
decide.

## Afterwards

- **Adding a table no longer happens by adding an `@Entity`.** With `validate` in force, a new
  entity without a matching migration fails startup. Write the migration in the same commit.
- `calendar_account.user_id` is deliberately retained one release as a rollback path. Once
  `student_id` is proven in production, drop it in a follow-up migration:
  `ALTER TABLE calendar_account DROP COLUMN user_id;`
- The foreign keys `past_course.student_id → student` and `due_date_override.student_id → student`
  are kept even though the entities no longer declare them as `@ManyToOne`. Hibernate does not
  validate foreign keys, and they still enforce that an owner id refers to a real student.
