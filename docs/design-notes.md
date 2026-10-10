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
and report leaks that do not exist. On such tables the observer is created `AS RESTRICTIVE`, which
Postgres ANDs with the permissive ones: the app's policy keeps filtering and rows it rejects are
never observed (`ObserverScenariosTest` 6a). A table whose policies never grant `tg_app` anything
is refused, because restrictive-only means deny-all and the observer would see nothing (6b).

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

## 8. What the field run taught

The first run on a real app (231 GET endpoints, 63 tenant tables, 98 migrations) found one
confirmed leak and fifteen false ones. Every false positive was the tool's fault:

- Eight were `200 []` / `200 0.00` / `200 null` on by-id endpoints whose query was correctly
  filtered. "2xx on the victim's id = LEAK" was too crude. Now an empty body is never a LEAK by
  itself; it is SUSPECT if the observer saw the victim's rows, CLEAN otherwise. The rule is flat
  so a wrapper cannot hide data, and `Extremes` seeding keeps a real `0` from looking empty.
- Four were platform-admin endpoints that list every tenant by design, reachable because the
  attacker token carried every permission. `EndpointScanner.exclude(...)` with the rule in the
  report note; excluding everything fails.
- The app's `@Scheduled` jobs would have read every tenant under the global actor. The extension
  now warns, and `tenantguard.scheduling=off` installs a scheduler that runs nothing.
- Setup failures were cryptic; they now say which bean, role or property to check.

The leak itself was a `(select name from branches where id = ?)` subquery in a fallback branch,
the kind of thing no entity-level filter covers.

## 9. What the leak corpus proved

Thirty-three patterns, each asserting observer, passive rule and diff (`LeakCorpusTest`). Two
results were not what we expected. Views without `security_invoker` and `SECURITY DEFINER`
functions run as their owner, who bypasses RLS, so the observer is blind to them; the diff still
catches both. And a `@Cacheable` result warmed during the baseline never reaches Postgres again,
so even the observer is blind; clearing the cache after seeding the victim fixes it. The
leakproof `EXISTS` blind spot turned out narrow: it only exists while the answer does not depend
on the victim's rows. `CorpusMechanismTest` shows each mechanism (planner filter order from
`EXPLAIN`, owner catalogs, re-owning the view to `tg_app` and watching it become observed).

## 10. Mutation testing

PIT with the STRONGER mutators runs on the JDBC core (`mvn -Pmutation ...`). The first run
killed 74%; the survivors were test gaps, not bugs, the worst being that the self-check could
stop looking for the probe NOTICE without any test noticing. After unit tests with fake JDBC
chains and a few Postgres-backed blindness tests, 96% are killed and the remaining 8 are
equivalent mutants. The point is the same as the self-check's: a harvest that silently stops
working must turn something red.

## 11. What the environment matrix found

Five dimensions, one agent each, every cell run locally before it went into CI:

- **Postgres 13–17 and pgjdbc 42.6/42.7:** no core change. `security_invoker` views exist from
  PG 15 only (the corpus row is version-gated). pgjdbc 42.7.14 attaches cursor-fetch notices one by
  one instead of per batch; same chain by the time `next()` returns. `PgjdbcContractTest` pins the
  three driver behaviours the observer depends on.
- **Spring Boot 3.2–3.5:** no code change; only the build needed work (Testcontainers pin for
  Docker 29, Flyway 10 for Boot < 3.3). Minimum supported Boot is 3.2.
- **Connection pools:** three real observer gaps, all in the "silent under-reporting or
  misattribution" class. A pool statement cache hands the next borrower the previous warning chain
  (double count, and A's own read charged to B); DBCP2's result wrapper unwraps to itself (two
  proxies per result); c3p0 reports a driver-closed result as open but throws on `getWarnings`.
  The statement proxy now skips leftovers at construction, there is one result proxy per current
  statement result, and the close-time harvest tolerates a closed result.
- **Tenant column shapes and schemas:** grants were hard-coded to `public`, discovery looked only
  at `current_schema()`, the same table name in two schemas could not be told apart, and the probe
  policy read the tenant column (a `company_id` install broke a later `tenant_id` one). Identifiers
  are now quoted from an allowlist that excludes the double quote, so mixed case and reserved
  words work. The actor is compared with `tenant_column::text` exactly; no numeric normalization,
  because `"02"` and `"2"` are different text tenants and a wrong actor should fail loudly.
- **App wiring:** the guard must sit directly on the pool, under Spring's
  `LazyConnectionDataSourceProxy` and `TransactionAwareDataSourceProxy`; above the latter, raw
  connections silently leave the transaction. The lazy proxy's read-only replica was a blind path
  and is guarded now. Routing DataSources are installed and self-checked per target; unlisted
  DataSources are switched off with a warning rather than observed-but-never-installed.

The matrix's first CI runs then failed where the local runs never had: a dirty `search_path` and
an autocommit left off on connections returned to the pool. Hikari repairs both on return,
tomcat-jdbc does not, and an observer install blocked on a table lock for the job's whole timeout.
The installer now uses a `lock_timeout` and reports the lock holders; a hang is the one failure
mode worse than a wrong answer.

## 12. Things deliberately kept simple

- The actor is a global, not a `ThreadLocal`: the scanner issues one request at a time, and a
  global survives `@Async` and thread pools. Cost: no parallel test execution.
- `{id}` → table mapping is a guess from the path segment, with per-template overrides. Guessing
  wrong yields `SKIPPED` with the names tried, never a silent `CLEAN`.
- Only `GET` is scanned. Generating valid `POST`/`PUT` bodies is a separate problem; `DELETE`
  destroys the seed and must run last with re-seeding.
