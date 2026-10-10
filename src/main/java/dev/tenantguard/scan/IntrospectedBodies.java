package dev.tenantguard.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.util.ClassUtils;

/**
 * Best-effort JSON for a {@code @RequestBody} type: every settable property gets a plausible value
 * (text, 1, true, the enum's first constant, a fixed UUID and date), collections and maps stay
 * empty, nested objects are filled one level deep. Properties it cannot fill are left out. Why
 * Jackson's introspection rather than reflection: it sees the same properties the application's
 * deserializer will, under the same names. A body type whose shape says nothing (JsonNode, Object,
 * a bare list, map or scalar) is refused with the reason: guessing there would mostly produce 400s,
 * and a 400 verifies nothing. Tenant fields are generated like any other; the scanner strips them.
 */
final class IntrospectedBodies implements RequestBodies {

    /** The top-level object is depth 0; its nested objects are depth 1 and filled; deeper ones are not. */
    private static final int MAX_NESTING = 1;
    static final String TEXT = "tg-probe";

    private static final Map<Class<?>, JsonNode> SCALARS = Map.ofEntries(
            Map.entry(String.class, TextNode.valueOf(TEXT)),
            Map.entry(Character.class, TextNode.valueOf("x")),
            Map.entry(Boolean.class, BooleanNode.TRUE),
            Map.entry(Byte.class, IntNode.valueOf(1)),
            Map.entry(Short.class, IntNode.valueOf(1)),
            Map.entry(Integer.class, IntNode.valueOf(1)),
            Map.entry(Long.class, IntNode.valueOf(1)),
            Map.entry(Float.class, IntNode.valueOf(1)),
            Map.entry(Double.class, IntNode.valueOf(1)),
            Map.entry(BigInteger.class, IntNode.valueOf(1)),
            Map.entry(BigDecimal.class, IntNode.valueOf(1)),
            Map.entry(UUID.class, TextNode.valueOf("00000000-0000-4000-8000-000000000001")),
            Map.entry(LocalDate.class, TextNode.valueOf("2024-01-02")),
            Map.entry(LocalDateTime.class, TextNode.valueOf("2024-01-02T03:04:05")),
            Map.entry(LocalTime.class, TextNode.valueOf("03:04:05")),
            Map.entry(YearMonth.class, TextNode.valueOf("2024-01")),
            Map.entry(Instant.class, TextNode.valueOf("2024-01-02T03:04:05Z")),
            Map.entry(OffsetDateTime.class, TextNode.valueOf("2024-01-02T03:04:05Z")),
            Map.entry(ZonedDateTime.class, TextNode.valueOf("2024-01-02T03:04:05Z")));

    private final ObjectMapper mapper;

    IntrospectedBodies(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Body body(Endpoint endpoint, String tenant) {
        Type type = endpoint.requestBodyType();
        if (type == null) {
            return Body.none();
        }
        JavaType javaType = mapper.constructType(type);
        String problem = shapeProblem(javaType);
        ObjectNode json = problem == null ? bean(javaType, 0) : null;
        if (problem == null && json.isEmpty()) {
            problem = javaType.getRawClass().getSimpleName() + " has no property Jackson could set";
        }
        if (problem != null) {
            return Body.unavailable("cannot build a JSON body: %s; supply .body(\"%s\", tenant -> json)"
                    .formatted(problem, endpoint.label()));
        }
        try {
            return Body.generated(mapper.writeValueAsString(json));
        } catch (JsonProcessingException e) {
            return Body.unavailable("cannot serialize the generated body: " + e.getOriginalMessage());
        }
    }

    /** Null when the type is an object with properties we can try to fill. */
    private static String shapeProblem(JavaType type) {
        Class<?> raw = type.getRawClass();
        String name = raw.getSimpleName();
        if (type.isArrayType() || type.isCollectionLikeType()) {
            return "a top-level collection (%s): which elements to send is unknown".formatted(type.getGenericSignature());
        }
        if (type.isMapLikeType()) {
            return "a top-level map (%s): its keys are unknown".formatted(name);
        }
        if (scalar(raw) != null || raw.isEnum() || raw.isPrimitive()) {
            return "a bare %s value: its meaning is unknown".formatted(name);
        }
        if (raw == Object.class) {
            return "Object says nothing about the expected shape";
        }
        if (!type.isConcrete()) {
            return name + " is abstract: the concrete shape is unknown";
        }
        return isJdkType(raw) ? name + " is a JDK type without a known JSON form" : null;
    }

    private ObjectNode bean(JavaType type, int depth) {
        ObjectNode node = mapper.createObjectNode();
        for (BeanPropertyDefinition property : mapper.getDeserializationConfig().introspect(type).findProperties()) {
            JsonNode value = property.couldDeserialize() && property.getPrimaryType() != null
                    ? value(property.getPrimaryType(), depth + 1) : null;
            if (value != null) {
                node.set(property.getName(), value);
            }
        }
        return node;
    }

    /** Null means "leave the property out". */
    private JsonNode value(JavaType type, int depth) {
        Class<?> raw = type.getRawClass();
        if (type.isReferenceType() || raw == Optional.class) {
            return type.containedTypeCount() == 1 ? value(type.containedType(0), depth) : null;
        }
        if (type.isArrayType() || type.isCollectionLikeType()) {
            return mapper.createArrayNode();
        }
        if (type.isMapLikeType()) {
            return mapper.createObjectNode();
        }
        JsonNode scalar = scalar(raw);
        if (scalar != null) {
            return scalar;
        }
        if (raw.isEnum()) {
            return firstConstant(raw);
        }
        if (depth > MAX_NESTING || !type.isConcrete() || raw == Object.class || isJdkType(raw)) {
            return null;
        }
        ObjectNode nested = bean(type, depth);
        return nested.isEmpty() ? null : nested;
    }

    /** Through the mapper, so {@code @JsonValue} and {@code @JsonProperty} on constants are honoured. */
    private JsonNode firstConstant(Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null || constants.length == 0) {
            return null;
        }
        try {
            return mapper.valueToTree(constants[0]);
        } catch (IllegalArgumentException e) {
            return TextNode.valueOf(((Enum<?>) constants[0]).name());
        }
    }

    private static JsonNode scalar(Class<?> raw) {
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(raw);
        return CharSequence.class.isAssignableFrom(boxed) ? TextNode.valueOf(TEXT) : SCALARS.get(boxed);
    }

    private static boolean isJdkType(Class<?> raw) {
        return raw.getName().startsWith("java.") || raw.getName().startsWith("javax.");
    }
}
