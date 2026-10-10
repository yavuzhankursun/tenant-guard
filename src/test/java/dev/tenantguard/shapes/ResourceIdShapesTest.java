package dev.tenantguard.shapes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.scan.ResourceIds.Resolution;
import dev.tenantguard.scan.TableGuessingResourceIds;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriUtils;

/**
 * Victim id lookup for non-text keys and tables reached through the search_path. The scanner resolves
 * ids with the guard inactive, as the owner; mirror that.
 */
class ResourceIdShapesTest extends ShapesTestBase {

    private TableGuessingResourceIds ids;

    @BeforeEach
    void inactive() {
        guard.deactivate();
        ids = new TableGuessingResourceIds(dataSource, "tenant_id");
    }

    @Test
    void uuidPrimaryKeyAndUuidTenantResolveToTheSmallestIdInCanonicalForm() {
        seedVictim(UUID);
        String expected = jdbc.queryForObject(
                "SELECT id::text FROM t_uuid WHERE tenant_id = CAST(? AS uuid) ORDER BY id LIMIT 1", String.class, UUID_A);

        Resolution resolution = ids.resolve("/t-uuids/{id}", "id", UUID_A);

        assertThat(resolution.isResolved()).as(resolution.reason()).isTrue();
        assertThat(resolution.id()).isEqualTo(expected).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        assertThat(UriUtils.encodePathSegment(resolution.id(), StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    void integerTenantResolvesWithTheCanonicalActorOnly() {
        seedVictim(INT);

        assertThat(ids.resolve("/t-ints/{id}", "id", "1").id()).isEqualTo("3");
        assertThat(ids.resolve("/t-ints/{id}", "id", "01").reason()).contains("no rows of tenant 01");
    }

    @Test
    void bigintTenantUnderAnotherColumnName() {
        seedVictim(COMPANY);

        Resolution resolution = new TableGuessingResourceIds(dataSource, "company_id")
                .resolve("/t-companies/{companyId}", "companyId", COMPANY.victim());

        assertThat(resolution.id()).isEqualTo("3");
    }

    @Test
    void tablesInPublicAreFoundAlthoughTheCurrentSchemaIsTenancy() {
        seedVictim(TEXT);

        assertThat(ids.resolve("/t-texts/{id}", "id", "A").id()).isEqualTo("3");
    }

    @Test
    void theSearchPathDecidesWhichSameNamedTableIsMeant() {
        seedVictim(IN_SCHEMA);

        Resolution resolution = ids.resolve("/t-in-schemas/{id}", "id", "1");

        assertThat(resolution.id()).as("tenancy.t_in_schema shadows public.t_in_schema").isEqualTo("3");
        assertThat(ids.resolve("/t-in-schemas/{id}", "id", "2").id()).isEqualTo("1");
    }
}
