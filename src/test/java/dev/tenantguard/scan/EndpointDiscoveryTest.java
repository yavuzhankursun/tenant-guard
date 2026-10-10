package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class EndpointDiscoveryTest extends ScanTestBase {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("Sample app'in beş GET endpoint'ini bulur, Boot'un /error'unu atlar")
    void findsTheFiveGetEndpointsButNotError() {
        assertThat(EndpointDiscovery.discover(context))
                .extracting(Endpoint::pathTemplate)
                .containsExactly("/invoices", "/invoices/{id}", "/my/invoices", "/my/invoices/{id}", "/reports/total");
    }

    @Test
    @DisplayName("Path değişkenleri ve handler adı çıkarılır")
    void extractsPathVariablesAndHandler() {
        Endpoint byId = EndpointDiscovery.discover(context).stream()
                .filter(e -> e.pathTemplate().equals("/invoices/{id}"))
                .findFirst().orElseThrow();

        assertThat(byId.pathVariables()).isEqualTo(Set.of("id"));
        assertThat(byId.handler()).isEqualTo("InvoiceController#byId");
    }

    @Test
    @DisplayName("Değişken adları regex ve catch-all sözdiziminden ayıklanır")
    void parsesVariableSyntaxVariants() {
        assertThat(EndpointDiscovery.pathVariables("/a/{id:\\d+}/b/{slug}/{*rest}"))
                .containsExactly("id", "slug", "rest");
        assertThat(EndpointDiscovery.pathVariables("/reports/total")).isEmpty();
    }
}
