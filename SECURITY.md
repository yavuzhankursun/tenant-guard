# Security policy

tenant-guard is a test-time tool: it creates a database role and row-level-security policies in the
database its tests run against, and proxies that database's JDBC connections. A bug can therefore
hide a cross-tenant leak (a false negative), or weaken a database it should never have been pointed
at. Both are security issues for this project.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
**[Report a vulnerability](https://github.com/yavuzhankursun/tenant-guard/security/advisories/new)**
(repository *Security* tab, *Advisories*, *Report a vulnerability*).

Do not open a public issue, pull request or discussion for it. Include:

- the tenant-guard version (or commit), Java, Spring Boot, PostgreSQL and pgjdbc versions;
- what happens and what you expected, ideally with a minimal schema and test that reproduces it;
- the impact as you see it (e.g. a leak the observer does not report, a privilege the observer role
  keeps after the test).

You should get an acknowledgement within 7 days. Fixes are released as a new version and
disclosed in a GitHub security advisory, crediting the reporter unless you prefer otherwise.

## Supported versions

Only the latest released version receives security fixes. Before 1.0.0 there are no maintenance
branches.

## Scope

In scope: the library (`dev.tenantguard.*`), its Maven artifacts and the release workflow.
Out of scope: vulnerabilities in the example applications under `examples/` (they contain
deliberate tenant leaks), and in third-party dependencies unless tenant-guard's use of them makes
them exploitable.

tenant-guard is meant for test databases only. Running it against a production database is not a
supported configuration.
