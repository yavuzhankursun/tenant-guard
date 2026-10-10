package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tenantguard.sample.WriteController;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;

@TestPropertySource(properties = WriteController.ENABLED + "=true")
class WriteEndpointDiscoveryTest extends ScanTestBase {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("Yazma endpoint'leri method'larıyla listelenir: template sırası, aynı template'te POST, PUT, PATCH, DELETE")
    void listsWriteEndpointsWithTheirMethod() {
        assertThat(EndpointDiscovery.discoverWrites(context))
                .extracting(Endpoint::label)
                .containsExactly("POST /w/invoices", "PUT /w/invoices/{id}", "PATCH /w/invoices/{id}",
                        "DELETE /w/invoices/{id}", "POST /w/my/invoices", "PUT /w/my/invoices/{id}",
                        "DELETE /w/my/invoices/{id}");
    }

    @Test
    @DisplayName("@RequestBody tipi handler'dan alınır; DELETE'in gövdesi yok")
    void capturesTheRequestBodyType() {
        assertThat(write("PUT /w/invoices/{id}").requestBodyType()).isEqualTo(WriteController.InvoiceUpdate.class);
        assertThat(write("PATCH /w/invoices/{id}").requestBodyType()).isEqualTo(JsonNode.class);
        assertThat(write("DELETE /w/invoices/{id}").requestBodyType()).isNull();
        assertThat(write("PUT /w/invoices/{id}").handler()).isEqualTo("WriteController#update");
        assertThat(write("PUT /w/invoices/{id}").pathVariables()).isEqualTo(Set.of("id"));
    }

    @Test
    @DisplayName("GET keşfi değişmez: yazma controller'ı açıkken de aynı beş GET")
    void getDiscoveryIsUnchanged() {
        assertThat(EndpointDiscovery.discover(context))
                .extracting(Endpoint::pathTemplate)
                .containsExactly("/invoices", "/invoices/{id}", "/my/invoices", "/my/invoices/{id}", "/reports/total");
        assertThat(EndpointDiscovery.discover(context)).allMatch(e -> e.method() == RequestMethod.GET && !e.isWrite());
    }

    @Test
    @DisplayName("Endpoint: eski 3 argümanlı constructor GET'tir; HEAD/OPTIONS gibi method'lar reddedilir")
    void endpointRecordStaysBackwardCompatible() {
        Endpoint legacy = new Endpoint("/a/{id}", Set.of("id"), "X#a");
        assertThat(legacy.method()).isEqualTo(RequestMethod.GET);
        assertThat(legacy.label()).isEqualTo("GET /a/{id}");
        assertThat(legacy.requestBodyType()).isNull();
        assertThat(Endpoint.of(RequestMethod.DELETE, "/a/{id}", "X#d", null).isWrite()).isTrue();
        assertThatThrownBy(() -> Endpoint.of(RequestMethod.HEAD, "/a", "X#h", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HEAD");
    }

    private Endpoint write(String label) {
        return EndpointDiscovery.discoverWrites(context).stream()
                .filter(e -> e.label().equals(label)).findFirst().orElseThrow();
    }
}
