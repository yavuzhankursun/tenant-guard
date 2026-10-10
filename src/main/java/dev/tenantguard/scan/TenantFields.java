package dev.tenantguard.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Top-level body fields that name a tenant: {@code tenantId}, the tenant column itself in any case
 * or spelling ({@code company_id}, {@code companyId}), and whatever the user adds. Generated bodies
 * lose them, so the plain probe never claims a tenant (not even the attacker's own: whether an
 * endpoint honours a body tenant is the spoof probe's question, not the plain one's). The opt-in
 * spoof probe sets the ones present to the victim's id instead.
 */
final class TenantFields {

    /** The spoofed body and the fields that now carry the victim's id. */
    record Spoof(String json, List<String> fields, String victim) {

        String describe() {
            return String.join(", ", fields.stream().map(f -> f + "=" + victim).toList());
        }
    }

    private final ObjectMapper mapper;
    private final Set<String> names;
    private final boolean spoofing;

    TenantFields(ObjectMapper mapper, String tenantColumn, List<String> extraNames, boolean spoofing) {
        this.mapper = mapper;
        Set<String> normalized = new LinkedHashSet<>(List.of(normalize("tenantId"), normalize(tenantColumn)));
        extraNames.forEach(name -> normalized.add(normalize(name)));
        this.names = Set.copyOf(normalized);
        this.spoofing = spoofing;
    }

    /** {@code tenant_id}, {@code tenantId} and {@code TENANT-ID} are the same field to an attacker. */
    static String normalize(String name) {
        return name.replaceAll("[_\\-]", "").toLowerCase(Locale.ROOT);
    }

    /** The generated body without tenant fields; non-object or unparsable JSON is returned unchanged. */
    String strip(String json) {
        return object(json).map(node -> {
            ObjectNode copy = node.deepCopy();
            tenantFieldsOf(copy).forEach(copy::remove);
            return write(copy);
        }).orElse(json);
    }

    /** Empty when spoofing is off or the body names no tenant field: then there is nothing to probe. */
    Optional<Spoof> spoof(String json, String victim) {
        if (!spoofing) {
            return Optional.empty();
        }
        return object(json).flatMap(node -> {
            List<String> fields = tenantFieldsOf(node);
            if (fields.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode copy = node.deepCopy();
            fields.forEach(field -> copy.set(field, TextNode.valueOf(victim)));
            return Optional.of(new Spoof(write(copy), fields, victim));
        });
    }

    private List<String> tenantFieldsOf(ObjectNode node) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(field -> {
            if (names.contains(normalize(field))) {
                fields.add(field);
            }
        });
        return List.copyOf(fields);
    }

    private Optional<ObjectNode> object(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (JsonProcessingException notJson) {
            return Optional.empty();
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize a parsed JSON tree", e);
        }
    }
}
