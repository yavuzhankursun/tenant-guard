# Design notes

How tenant-guard ended up with an RLS observer, a differential probe and a JDBC proxy, in the
order the decisions were made. Each step records what was tried, why it was not enough, and what
replaced it. The tests in `src/test` are the evidence for every claim here.

## 1. Outside-in scanning is not enough

Tools like Burp Autorize, AuthProbe and BOLABuster replay tenant A's requests with tenant B's
credentials and compare responses. They see only HTTP. A report endpoint that computes
`SELECT sum(amount) FROM invoice` without a tenant filter returns a single number; nothing in the
response belongs to A, so nothing can be matched. The leak is real and invisible.

Decision: look from inside the database, where the question is "which tenants' rows did this
statement read?" rather than "does the response contain A's data?".

## 2. Inspecting returned rows does not work either

First idea: wrap the `DataSource`, look at every row that comes back, flag rows whose
`tenant_id` is not the actor's. Rejected before implementation:

- Aggregates return one number. There is no row to look at.
- Projections often do not select `tenant_id` at all (DTOs, native queries).
- Rows filtered in memory after an unfiltered query would be caught, but only if the column is
  selected.

## 3. RLS as an observer, not a filter

Postgres row-level security evaluates a policy expression for every row the executor reads. A
policy that always returns `true` but has a side effect reports every row without changing the
application's behaviour:

```sql
CREATE FUNCTION tg_observe(tbl text, row_tenant text) RETURNS boolean AS $$
BEGIN RAISE NOTICE 'tg|%|%', tbl, row_tenant; RETURN true; END $$ LANGUAGE plpgsql;

CREATE POLICY tg_observer ON invoice FOR ALL TO tg_app
  USING (tg_observe('invoice', tenant_id::text)) WITH CHECK (true);
```

Why NOTICE and not a log table: the policy runs inside the application's transaction. A
`@Transactional(readOnly = true)` method opens a `READ ONLY` transaction on the server and an
`INSERT` from the policy fails; a rolled-back request would also roll the log back. NOTICE is a
protocol message, not data; it reaches the client regardless (`ObserverScenariosTest` 4a/4b).

Why a separate role: Testcontainers' default user is a superuser and superusers bypass RLS. The
guarded `DataSource` runs `SET ROLE tg_app` on every borrowed connection while active and
`RESET ROLE` while inactive, so Flyway and seeders keep the owner's credentials and are never
observed. No `FORCE ROW LEVEL SECURITY` for the same reason.

Why refuse tables with existing policies: Postgres ORs permissive policies. An always-true
observer next to the app's own policy would switch the app's isolation off in the test database
and report leaks that do not exist. A RESTRICTIVE observer is the eventual answer; for now the
installer refuses (`ObserverScenariosTest` 6) and auto-discovery skips with a reason.

Why a self-check: if RLS silently does not apply (superuser, `BYPASSRLS`, `client_min_messages`
above NOTICE, proxy not in the path), a leaky app produces zero findings, exactly like a clean
one. That is the worst failure mode this tool can have, so before each test it fails loudly
unless a probe query produces a NOTICE and the effective role cannot bypass RLS
(`ObserverScenariosTest` 5).

## 4. The observer sees reads, not results

A correctly written join still reads foreign rows:

```sql
SELECT l.id FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id WHERE i.tenant_id = 'B'
```

With small tables Postgres seq-scans `invoice_line`, the observer reports A's lines, and the
join then discards them. The result is correct; the finding is a false positive
(`ObserverScenariosTest` 3). A per-table rule ("more than one tenant read from the same table")
does not help: the scan of `invoice_line` alone reads both tenants.

Conversely, Postgres runs *leakproof* predicates (`=` on `text`/`bigint`) before the policy, so
rows they reject are never observed. `WHERE tenant_id = 'B'` is silent, and so is a correct
`findByIdAndTenantId`. Non-leakproof predicates (`lower(number) = ...`) run after the policy.

Decision: every observer finding is SUSPECT-level. Confirmation needs a second signal.

## 5. The differential probe: confirmed leaks

Rule: A's data must not influence B's responses. Record B's responses while only B's data
exists, add A's data, call again, diff. A changed response is a confirmed LEAK; the unfiltered
`SUM` changes, the correct join does not. Fields that already differ between two baseline calls
(timestamps, request ids) are masked as noise (`DifferentialScenarioTest`).

Two blind spots found by tests:

- `MAX` does not change when A's values sit inside B's range. Fix: seed the victim with extreme
  values (`Extremes`: smallest and largest amount, oldest and newest date, first and last string).
- Booleans and early exits (`EXISTS`, `count(*) > 0`, `LIMIT 1`) may never read A's rows at all,
  so neither the diff nor the observer sees them. Documented, not solved.

## 6. Active vs passive mode

In the scanner the library knows which tenant it is acting as, so "B read a row of A" is
decidable (`ACTOR_MISMATCH`). This catches the most common bug, `findById(id)` without a tenant
check, from a single row.

In a user's existing integration tests the actor is unknown; the only actor-free rule is "one
statement read rows of more than one tenant". It cannot catch the single-row IDOR, and it fires on
joins and on legitimately cross-tenant queries (login by email, admin screens, schedulers). Passive
mode is therefore documented as a weaker, noisier mode rather than hidden behind the same API.

## 7. Where the JDBC proxy had to be fixed

Five "silent under-reporting" bugs were found by writing a test per access path
(`JdbcPathMatrixTest`, 33 paths) and reading pgjdbc's bytecode with `javap` when a path failed:

1. pgjdbc clears the statement's warning chain at the start of every `execute`, not through
   `clearWarnings()`. A reused `PreparedStatement` lost the previous run's notices.
2. With `fetchSize` set inside a transaction, notices from later cursor fetches are attached to
   the `ResultSet` (`PgResultSet.addWarning` via `CursorResultHandler`), not the `Statement`.
   Rows after the first batch were invisible until the `ResultSet` was proxied too.
3. `unwrap(Connection.class)` through Hikari returned the raw driver connection; statements made
   from it were unobserved.
4. The chain cursor kept its offset after the driver replaced the chain, skipping new notices.
   A new chain head now restarts the count.
5. Hikari creates separate wrappers for `executeQuery` and `getResultSet`, so one driver
   `ResultSet` was proxied twice and fetch notices double-counted.

Each fix was made only after a test showed the loss (expected N rows, observed fewer).

## 8. Things deliberately kept simple

- The actor is a global, not a `ThreadLocal`: the scanner issues one request at a time, and a
  global survives `@Async` and thread pools. Cost: no parallel test execution.
- `{id}` → table mapping is a guess from the path segment, with per-template overrides. Guessing
  wrong yields `SKIPPED` with the names tried, never a silent `CLEAN`.
- Only `GET` is scanned. Generating valid `POST`/`PUT` bodies is a separate problem; `DELETE`
  destroys the seed and must run last with re-seeding.
