package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.tenantguard.scan.RequestBodies.Body;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

/** The default body generator: Jackson introspection, no Spring, no database. */
class IntrospectedBodiesTest {

    enum Status { DRAFT, SENT }

    record Address(String city, Country country) {
    }

    record Country(String code) {
    }

    record Order(String tenantId, String number, int quantity, long total, BigDecimal amount, boolean paid,
                 Status status, UUID ref, LocalDate due, Optional<String> memo, List<String> tags,
                 Map<String, Integer> extras, Address address, Object anything) {
    }

    /** A classic JavaBean: setters, no creator. */
    public static final class Bean {

        private String invoiceNumber;
        private Double rate;

        public String getInvoiceNumber() {
            return invoiceNumber;
        }

        public void setInvoiceNumber(String invoiceNumber) {
            this.invoiceNumber = invoiceNumber;
        }

        public Double getRate() {
            return rate;
        }

        public void setRate(Double rate) {
            this.rate = rate;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private Body body(Type type) {
        return RequestBodies.introspecting(mapper).body(Endpoint.of(RequestMethod.PUT, "/x/{id}", "X#x", type), "B");
    }

    private JsonNode json(Body body) throws Exception {
        assertThat(body.isAvailable()).as(body.reason()).isTrue();
        assertThat(body.generated()).isTrue();
        return mapper.readTree(body.json());
    }

    @Test
    @DisplayName("Record: string, sayılar, boolean, enum'un ilk sabiti, UUID, tarih, boş koleksiyonlar, bir seviye iç içe nesne")
    void fillsEveryPropertyWithAPlausibleValue() throws Exception {
        JsonNode order = json(body(Order.class));

        assertThat(order.get("number").asText()).isEqualTo("tg-probe");
        assertThat(order.get("quantity").asInt()).isEqualTo(1);
        assertThat(order.get("total").asLong()).isEqualTo(1);
        assertThat(order.get("amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(order.get("paid").asBoolean()).isTrue();
        assertThat(order.get("status").asText()).isEqualTo("DRAFT");
        assertThat(UUID.fromString(order.get("ref").asText())).isNotNull();
        assertThat(LocalDate.parse(order.get("due").asText())).isNotNull();
        assertThat(order.get("memo").asText()).isEqualTo("tg-probe");
        assertThat(order.get("tags").isArray() && order.get("tags").isEmpty()).isTrue();
        assertThat(order.get("extras").isObject() && order.get("extras").isEmpty()).isTrue();
        assertThat(order.get("address").get("city").asText()).isEqualTo("tg-probe");
        assertThat(order.get("address").has("country")).as("two levels deep is not generated").isFalse();
        assertThat(order.has("anything")).as("Object: no plausible value").isFalse();
    }

    @Test
    @DisplayName("Tenant alanı da üretilir (placeholder); ayıklamak tarayıcının işi, kullanıcının gövdesi asla ayıklanmaz")
    void generatesTenantFieldsAsPlaceholders() throws Exception {
        assertThat(json(body(Order.class)).get("tenantId").asText()).isEqualTo("tg-probe");
    }

    @Test
    @DisplayName("JavaBean setter'ları ve ObjectMapper'ın isimlendirme stratejisi kullanılır")
    void usesSettersAndTheMappersNamingStrategy() throws Exception {
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        JsonNode bean = json(body(Bean.class));

        assertThat(bean.get("invoice_number").asText()).isEqualTo("tg-probe");
        assertThat(bean.get("rate").asDouble()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Üretilemeyen gövde: JsonNode, Object, liste, String, Map, skaler -> sebep ve .body(...) önerisi")
    void unknownShapesAreUnavailableWithAReason() {
        assertUnavailable(JsonNode.class, "JsonNode");
        assertUnavailable(Object.class, "Object");
        assertUnavailable(new TypeReference<List<Order>>() { }.getType(), "collection");
        assertUnavailable(String.class, "String");
        assertUnavailable(new TypeReference<Map<String, Object>>() { }.getType(), "map");
        assertUnavailable(Long.class, "Long");
    }

    @Test
    @DisplayName("@RequestBody yoksa gövde gönderilmez")
    void noBodyTypeMeansNoBody() {
        Body none = body(null);

        assertThat(none.isAvailable()).isTrue();
        assertThat(none.json()).isNull();
    }

    private void assertUnavailable(Type type, String mention) {
        Body body = body(type);
        assertThat(body.isAvailable()).as(type.getTypeName()).isFalse();
        assertThat(body.reason()).as(type.getTypeName())
                .contains(mention)
                .contains(".body(\"PUT /x/{id}\", tenant -> json)");
    }
}
