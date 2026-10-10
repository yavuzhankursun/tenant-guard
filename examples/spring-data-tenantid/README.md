# Field run: Spring Data's Hibernate `@TenantId` example

The `jpa/multitenant/partition` example from
[spring-projects/spring-data-examples](https://github.com/spring-projects/spring-data-examples)
(Apache-2.0), copied unchanged except for the package name and the Boot 3.5 import of
`HibernatePropertiesCustomizer`, moved from H2 to Postgres 16 (Testcontainers), and scanned with
tenant-guard. Run with `mvn test` (needs Docker; tenant-guard installed with `mvn install` from the
repository root).

| Read in the sample | Hibernate 6.6 | Observer |
|---|---|---|
| `findAll()` | `tenant = ?` added | nothing read of the other tenant: CLEAN |
| JPQL `findJpqlByName` | filtered | CLEAN |
| `findById(other tenant's id)` | **filtered** (the sample's own `@Disabled` test saying otherwise is stale) | nothing read: CLEAN |
| native `findSqlByName` | **not filtered**; the sample expects a wrong-result-size exception | `ACTOR_MISMATCH` + `MULTI_TENANT_STATEMENT` on `person` |

The native-query row is the point of the whole tool: Hibernate's entity-level filter cannot reach
native SQL, and the only thing the sample can do about it is expect an exception. The observer
reports the read itself, with the table and the tenant whose rows were read.
