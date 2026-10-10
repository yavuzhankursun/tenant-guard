# Gradle + Kotlin consumer

A minimal Spring Boot 3.5 app in Kotlin, built with the Gradle Kotlin DSL, that uses tenant-guard the
way a downstream project would: one `testImplementation` dependency, nothing else.

- `V1__invoice.sql` (Flyway) creates `invoice (tenant_id, number, amount)`.
- `InvoiceRepository.searchByNumberPrefix` is the bug: a JPQL search that forgot the tenant.
- `InvoiceIsolationTest` (`@TenantIsolationTest`, Postgres 16 via Testcontainers) seeds tenants A and
  B, acts as B, and shows that the scoped lookup is clean while the search raises an
  `ACTOR_MISMATCH` finding on `invoice` for tenant A's rows.

Run it (needs Docker and JDK 21):

```sh
mvn -B -DskipTests install          # at the repository root: puts 0.2.0-SNAPSHOT into ~/.m2
cd examples/gradle-kotlin-consumer
./gradlew test
```

`mavenLocal()` is restricted to the `io.github.yavuzhankursun` group, so only tenant-guard is taken
from `~/.m2`. Against a released version, drop `mavenLocal()` and run
`./gradlew test -PtenantGuardVersion=<version>`.

The Gradle wrapper is pinned to 8.14.6 (Spring Boot 3.5 supports Gradle 7.6.4+ and 8.4+, not 9)
with a `distributionSha256Sum`; Kotlin 2.2.21, whose version the Spring Boot plugin also applies
to the Kotlin libraries it manages.
