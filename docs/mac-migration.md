# Migrating the Mac's live database to the v2 app

Written for whoever actually runs this - you, or a Claude Code session with
real access to the Mac's terminal and database. That distinction matters: this
touches the live database the shop trades against every day, so it should be
run by whatever has direct, supervised access to that machine, not relayed
from somewhere else. Nothing here needs guessing at what the Mac's database
actually looks like - every step starts by asking it.

**What actually happened, on this Mac (2026-09-13):** the shop's owner asked,
partway through following this, to leave `poultry_db` and the old app running
completely untouched rather than migrate it in place, and stand the v2 app up
against a fresh copy instead. Steps 0-4 below still ran exactly as written - that's what caught a real bug
in `V8__remove_duplicate_trip_and_line.sql` on the dry-run copy, before it
ever touched anything real (see Step 3's note on what to do when a migration
fails there) - but Step 5 onward
became "apply to a new database called `poultry_db_v2`, leave `poultry_db`
alone" rather than "apply to `poultry_db` itself." Both are valid ways to use
this document; which one is right depends on whether the business wants the
old app kept as a running fallback (this Mac's choice) or retired outright.
The steps below are written for the in-place case; read "the real database"
as "wherever you're actually applying this" if you've made the other choice.

The ledger backfill (`POST /admin/migration/ledger`, Step 7) doesn't have to
go through the HTTP endpoint - it's one call to
`LedgerMigrationService.migrateExistingSalesToLedger()`, and a throwaway
Spring `ApplicationRunner` that calls it directly, then gets deleted before
the real build, avoids needing a real admin JWT for what is a one-time,
locally-run operation. That's how it was done here: 488 customers, 56,123
sales, 56,418 ledger entries, verified afterward by checking that
`customer.balance_amount` summed across everyone matches the sum of each
customer's own latest ledger running balance exactly - not just that the
backfill "ran without error."

## Why this isn't just "copy the jar over"

The old app's database was never under Flyway - no `flyway_schema_history`
table, no versioned migrations, just whatever `ALTER TABLE`s happened by hand
over however long it's been running. `V1__baseline_schema.sql` in this repo is
a snapshot of what the schema looked like at the point this project started
tracking it with Flyway, and `spring.jpa.flyway.baseline-on-migrate=true`
means: point the v2 app at an existing database with no Flyway history, and
it marks that database "already at V1" without trying to recreate anything,
then applies V2 through V14 on top as ordinary `ALTER TABLE`s.

That mechanism is exactly right *if* the Mac's actual schema matches what V1
assumes. The real risk here is that it might not - the Mac's database kept
evolving on its own after whatever point this V1 snapshot was taken from, and
any drift means V2 or later could fail against a column that doesn't have the
shape it expects. That is precisely why every step below happens on a
throwaway copy first, and the real database is touched only once the dry run
is clean.

## Step 0 - find out what you're actually working with

```bash
mysql -u root -p -e "SHOW DATABASES;"
mysql -u root -p <the_real_database_name> -e "SHOW TABLES LIKE 'flyway_schema_history';"
```

The second command should return nothing - confirming there is no existing
Flyway history to conflict with baselining. If it returns a row, stop and
work out what put it there before going any further; that changes everything
below.

Also note the database's real name here. `ENV=prod` does not assume a name -
it reads `DATASOURCE_URL` from `.env`, so whatever the Mac's database is
actually called, that is what goes in the connection string. Nothing needs
renaming.

## Step 1 - back up everything, now, before touching anything

```bash
mkdir -p ~/scc-migration-backup
mysqldump -u root -p --routines --triggers --single-transaction \
  <the_real_database_name> > ~/scc-migration-backup/pre-v2-$(date +%Y%m%d-%H%M%S).sql
ls -la ~/scc-migration-backup/
```

Confirm the file's size looks like a real dump - a suspiciously small file
(a few KB) usually means the credentials were wrong and it dumped nothing.

## Step 2 - restore that backup into a scratch database

This is what makes the whole thing safe: everything from here through Step 4
happens on a copy nobody trades against.

```bash
mysql -u root -p -e "CREATE DATABASE scc_migration_check;"
mysql -u root -p scc_migration_check < ~/scc-migration-backup/pre-v2-*.sql
```

## Step 3 - dry run: point the v2 app at the scratch copy

`DATASOURCE_URL` on the command line overrides whatever `.env` says for this
one run, without touching the real `.env` file:

```bash
cd ~/SOHEL/backend   # wherever the v2 jar/build lives
ENV=prod \
DATASOURCE_URL="jdbc:mysql://localhost:3306/scc_migration_check?useSSL=false&serverTimezone=UTC" \
DATASOURCE_USER=root \
DATASOURCE_PASSWORD=<the real password> \
FRONTEND_URL=http://localhost:3000 \
java -jar backend-0.0.1-SNAPSHOT.jar
```

Watch the startup log for the Flyway lines - something like:

```
Successfully baselined schema with version: 1
Migrating schema `scc_migration_check` to version "2 - ..."
...
Migrating schema `scc_migration_check` to version "14 - supplier ledger"
Successfully applied 13 migrations
```

**If a migration fails here**, that is this step doing its job - it caught a
real problem before it could touch production. The error will name the exact
table and statement; note it, stop this run, and it becomes a new migration
(`V15__...`) written to reconcile the specific drift, rather than a change
to V2-V14, which are already applied everywhere else and must not change
their content once written (Flyway checksums them).

If it starts cleanly, `Ctrl+C` it - the point was the migration, not leaving
it running against a database nobody uses.

## Step 4 - check the numbers, not just that it started

Compare a few load-bearing figures between the backup and the migrated
scratch copy - these should be unchanged by any of V2-V14 as far as *totals*
go, even though rows moved between tables (V12, V13 in particular restructure
where things live):

```bash
mysql -u root -p scc_migration_check -e "
SELECT (SELECT COUNT(*) FROM sale) sales,
       (SELECT SUM(balance_amount) FROM customer) receivable,
       (SELECT COUNT(*) FROM customer_ledger) ledger_rows;"
```

Compare that against the same query run on the **original** backup restored
separately (or against the live database directly, read-only, before
touching it - a `SELECT` changes nothing). If receivable and sale count do
not match, do not go on to Step 5 until you know why.

## Step 5 - the real thing

Only once Step 4 looks right. Same command as Step 3, but against the real
database and without the override - `.env` already has the real values:

```bash
cd ~/SOHEL/backend
ENV=prod java -jar backend-0.0.1-SNAPSHOT.jar
```

Watch the same Flyway lines. This is the one irreversible step in this whole
document - which is exactly why everything before it existed.

## Step 6 - verify production, the same way

```bash
mysql -u root -p <the_real_database_name> -e "
SELECT (SELECT COUNT(*) FROM sale) sales,
       (SELECT SUM(balance_amount) FROM customer) receivable,
       (SELECT COUNT(*) FROM customer_ledger) ledger_rows;"
```

Same numbers as the live database had before Step 5 (sales and receivable are
untouched by any of V2-V14; only the schema changed, not the money).

## Step 7 - bring up the v2 app for real

- Copy `.env.example` to `.env` if it is not already there, and fill in the
  real values - the real database name, a fresh `FAST2SMS_API_KEY` (rotate
  it; it is not the old one), a generated `JWT_SECRET`
  (`openssl rand -base64 48`).
- Stop whatever the old app's launcher was running.
- Start the v2 app the same way - `mac-launcher/Start SCC.command` if that
  folder came over with the frontend, or `ENV=prod java -jar ...` directly.
- Open it and check a real screen - the dashboard, a customer's ledger -
  before calling this done.

## Step 8 - clean up

```bash
mysql -u root -p -e "DROP DATABASE scc_migration_check;"
```

Keep the backup from Step 1 somewhere that is not this Mac's own disk - a
migration is the one moment a backup is worth having twice.
