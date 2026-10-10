# Using Postgres row-level security as a leak detector for multi-tenant Spring apps

*Draft. Target: dev.to / Medium, then Show HN, r/java, r/PostgreSQL.*

Multi-tenant SaaS apps keep every customer's rows in the same tables and separate them with a
`tenant_id` column. Every query has to filter on it. Forget it once, in one native query, one
`JdbcTemplate` call or one report, and tenant B sees tenant A's numbers.

The usual way to test for this is from the outside: log in as B, replay A's requests, compare
responses (Burp Autorize, AuthProbe, BOLABuster all work like that). It misses a whole class of
leaks. Take a report endpoint that runs `SELECT sum(amount) FROM invoice` without a tenant
filter. B gets `{"total": 1300}`. That number already contains A's invoices, but there is no
field, id or string in the response that belongs to A. There is nothing to match. The leak is
real and invisible from HTTP.

I wanted to see the leak from inside the database instead. The question there is simpler: *which
tenants' rows did this statement read?*

## A policy that never filters

Postgres row-level security runs a policy expression for every row the executor reads. Normally
the expression decides whether the row is visible. Mine always says yes, but it has a side effect:

```sql
CREATE FUNCTION tg_observe(tbl text, row_tenant text) RETURNS boolean AS $$
BEGIN RAISE NOTICE 'tg|%|%', tbl, row_tenant; RETURN true; END $$ LANGUAGE plpgsql;

CREATE POLICY tg_observer ON invoice FOR ALL TO tg_app
  USING (tg_observe('invoice', tenant_id::text)) WITH CHECK (true);
```

Every row that reaches the policy produces a NOTICE naming the table and the row's tenant. The
test harness wraps the app's `DataSource`, switches each connection to `tg_app` while a test runs,
and collects the notices from the JDBC warning chain. If the test is acting as tenant B and a
notice says `invoice|A`, that is a finding: B's request made the executor read A's row.

Why NOTICE and not a log table? The policy runs inside the app's transaction. A
`@Transactional(readOnly = true)` method opens a `READ ONLY` transaction and the insert fails; a
rolled-back request rolls the log back with it. NOTICE is a protocol message, not data. It survives
both.

Why a separate role? Testcontainers' default user is a superuser, and superusers bypass RLS
silently. The harness refuses to run until a probe query produces a notice. Finding nothing because
the policy never applied looks exactly like finding nothing because the app is clean, and that is
the one failure mode this tool must not have.

## The observer sees reads, not results

The first surprise was a correct query raising an alarm:

```sql
SELECT l.id FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id WHERE i.tenant_id = 'B'
```

With small tables Postgres scans `invoice_line` whole, the observer reports A's lines, and the
join then discards them. The result is right; the executor still read the rows. So every observer
finding is "suspect" by itself, and a second signal confirms it: record B's responses while only
B's data exists, add A's data, call again, diff. If the response changed, A's data influenced it.
The unfiltered `SUM` changes; the correct join does not. (`MAX` does not change either unless A's
values sit outside B's range, so the victim is seeded with extreme values.)

The opposite surprise was Postgres being smarter than the observer. `=` on text and bigint is
*leakproof*, and the planner runs leakproof predicates before the policy. `WHERE tenant_id = 'B'`
rejects A's rows before the observer sees them, which is exactly right. It also means a correctly
written `findByIdAndTenantId` is invisible to the observer, and an `EXISTS` whose answer does not
depend on A's rows is a genuine blind spot.

## Where the JDBC driver fought back

The harness reads the warning chain through proxies around `Connection`, `Statement` and
`ResultSet`. I assumed the chain was a passive list. It is not. Writing one test per JDBC access
path (33 of them: plain statements, prepared statements reused, batches, cursors, `unwrap`,
Hibernate, `JdbcTemplate`) found five ways notices silently disappeared:

1. pgjdbc clears a statement's warning chain at the start of every `execute`, without calling
   `clearWarnings()`. A reused `PreparedStatement` lost the previous run's notices.
2. With `fetchSize` set inside a transaction, notices from later cursor fetches land on the
   `ResultSet`, not the `Statement`. Every row after the first batch went unseen.
3. `unwrap(Connection.class)` through HikariCP returned the raw driver connection; statements made
   from it were unobserved.
4. The chain cursor kept its offset after the driver replaced the chain, skipping new notices.
5. Hikari creates separate wrappers for `executeQuery` and `getResultSet`, so one result was
   proxied twice and fetch notices counted twice.

Then connection pools added three more. With a statement cache on (tomcat-jdbc, DBCP2, c3p0) a
cached `PreparedStatement` hands the next borrower the previous borrower's warning chain. Rows
were counted twice, and worse, tenant A's own legitimate read was charged to tenant B. None of this
showed up with HikariCP, which is why the CI matrix runs every pool, Postgres 13 to 17, three
pgjdbc versions and Spring Boot 3.2 to 3.5.

Each of these was found by a test going red, and each fix is pinned by one. Mutation testing (PIT)
on the core sits at 97%: a harvest that quietly stops working cannot stay green.

## What it found in the wild

On a production Spring Boot app with 231 GET endpoints and 63 tenant tables, the first run reported
16 leaks. One was real: a fallback query with `(select name from branches where id = ?)` and no
tenant filter, so branch names could be enumerated across tenants. Fifteen were the tool's fault:
eight `200 []` responses from correctly filtered queries, four platform-admin endpoints that list
every tenant by design, and so on. The empty-body rule and endpoint exclusion came out of that run.

On Spring Data's own Hibernate `@TenantId` example, `findAll`, JPQL and (on Hibernate 6.6)
`findById` are filtered and read nothing of the other tenant. The sample's native query is not
filtered. The sample knows: its test expects a wrong-result-size exception. The observer reports the
read itself, table and tenant included.

## What it cannot see

Views without `security_invoker` and `SECURITY DEFINER` functions run as their owner, who bypasses
RLS. `@Cacheable` results warmed during the baseline never reach Postgres again. Schema-per-tenant
designs have no tenant column at all. And everything here is Postgres-only, because the mechanism
is RLS plus NOTICE plus pgjdbc's warning chain.

The code, the 33-pattern leak corpus with its verified expectations, and the design notes are at
github.com/yavuzhankursun/tenant-guard.
