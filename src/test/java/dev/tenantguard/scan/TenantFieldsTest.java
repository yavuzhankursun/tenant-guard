package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantFieldsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("Üretilen gövdeden tenant'a benzeyen alanlar çıkar: tenantId, tenant_id, kolonun kendi adı (company_id/companyId)")
    void stripsTenantLikeFields() throws Exception {
        TenantFields fields = new TenantFields(mapper, "company_id", List.of(), false);

        String stripped = fields.strip("{\"tenantId\":\"x\",\"tenant_id\":\"x\",\"companyId\":\"x\",\"COMPANY_ID\":\"x\","
                + "\"number\":\"n\",\"tenantName\":\"t\"}");

        assertThat(tree(stripped)).isEqualTo(tree("{\"number\":\"n\",\"tenantName\":\"t\"}"));
    }

    @Test
    @DisplayName("Spoof: var olan tenant alanları kurbanın id'siyle doldurulur, alan yoksa probe yok")
    void spoofSetsPresentFieldsToTheVictim() throws Exception {
        TenantFields fields = new TenantFields(mapper, "tenant_id", List.of("orgRef"), true);

        TenantFields.Spoof spoof = fields.spoof("{\"tenantId\":\"x\",\"org_ref\":\"x\",\"number\":\"n\"}", "A").orElseThrow();

        assertThat(tree(spoof.json())).isEqualTo(tree("{\"tenantId\":\"A\",\"org_ref\":\"A\",\"number\":\"n\"}"));
        assertThat(spoof.describe()).isEqualTo("tenantId=A, org_ref=A");
        assertThat(fields.spoof("{\"number\":\"n\"}", "A")).isEmpty();
        assertThat(fields.spoof("[1]", "A")).isEmpty();
        assertThat(fields.spoof(null, "A")).isEmpty();
    }

    @Test
    @DisplayName("Spoof kapalıyken hiç probe üretilmez")
    void spoofIsOptIn() {
        TenantFields fields = new TenantFields(mapper, "tenant_id", List.of(), false);

        assertThat(fields.spoof("{\"tenantId\":\"x\"}", "A")).isEmpty();
    }
}
