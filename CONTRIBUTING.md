# Contributing

Issues and pull requests are welcome. For a security problem, do not open an issue: see
[SECURITY.md](SECURITY.md).

## Requirements

- JDK 21 (the build uses `release 21`; run Maven itself on 21 too, e.g.
  `JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS)
- Maven 3.9+
- Docker: every test talks to a real PostgreSQL started by Testcontainers

## Running the tests

```sh
mvn -B verify                 # the default build CI runs on every push and PR
mvn -B test -Dtest=PoolMatrixTest   # one class
```

The default build uses Spring Boot 3.5 (the `boot.version` property), the pgjdbc version from the
Boot BOM, HikariCP and `postgres:16-alpine`.

### Environment matrix

Each flag changes one dimension; CI's `matrix` job runs them on pushes to `main` and nightly.

| Flag | Example | Effect |
|---|---|---|
| `-Dtenantguard.pg.image` (or env `TENANTGUARD_PG_IMAGE`) | `-Dtenantguard.pg.image=postgres:13-alpine` | Postgres image for every test container |
| `-Dboot.version` | `-Dboot.version=3.2.12` | Spring Boot BOM the build resolves against (3.2.12 also switches the test fixture to Flyway 10) |
| `-Dpostgresql.version` | `-Dpostgresql.version=42.6.2` | pgjdbc driver version |
| `-Dtenantguard.pool` | `-Dtenantguard.pool=c3p0` | connection pool: `tomcat`, `dbcp2`, `c3p0`, `vibur`, `none` (default HikariCP) |
| `-Dtenantguard.pool.cache` | `-Dtenantguard.pool.cache=true` | turn the pool's statement cache on |

### Mutation testing

PIT on the JDBC core, in the `mutation` profile; the default build does not run it:

```sh
mvn -B -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
open target/pit-reports/index.html
```

Every PIT minion starts its own Postgres container and Spring context, so a full run takes a
while (CI runs it only on manual dispatch). A surviving mutant in new core code needs a test that
kills it, or a note on why it is equivalent.

### Examples

The examples consume the library as a downstream project would, from the local Maven repository:

```sh
mvn -B -DskipTests install
(cd examples/gradle-kotlin-consumer && ./gradlew test)
(cd examples/spring-data-tenantid && mvn -B test)
```

CI's `examples` job runs the same after the main test job.

### Release build, locally

```sh
mvn -B -Prelease -Dgpg.skip -DskipTests package
```

builds the main, `-sources` and `-javadoc` jars without signing. Javadoc runs with doclint (all
groups except `missing`) and fails on any warning, so broken `{@link}`s and HTML fail here before
they fail a release. Publishing is done only by `.github/workflows/release.yml` from a tag.

## Pull requests

- A bug fix comes with a test that fails without it. Tests run against Postgres, not mocks.
- New observer or JDBC-proxy behaviour should hold across the access paths in `JdbcPathMatrixTest`
  and, if it touches pooling, the pool matrix.
- Commit messages follow `<type>: <description>` with `feat`, `fix`, `refactor`, `docs`, `test`,
  `build`, `chore`, `perf` or `ci`.
- Add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
