package dev.tenantguard.scan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;

/**
 * Bodies that carry no data: {@code []}, {@code {}}, {@code null}, a blank string, zero, {@code false}.
 * Why: a by-id endpoint whose query is correctly tenant-filtered finds nothing for the victim's id
 * and often still answers 2xx with one of these. In a field run that was 8 of 15 false LEAKs. The
 * list is deliberately flat: {@code {"items": [], "total": 0}} counts as data, so a wrapper can
 * never hide the victim's values.
 */
final class EmptyBody {

    private EmptyBody() {
    }

    /** {@code body} as parsed by {@link HttpCaller}: Map, List, scalar, null, or the raw string. */
    static boolean isEmpty(Object body) {
        return switch (body) {
            case null -> true;
            case String text -> text.isBlank();
            case Collection<?> items -> items.isEmpty();
            case Map<?, ?> fields -> fields.isEmpty();
            case Boolean flag -> !flag;
            case BigDecimal decimal -> decimal.signum() == 0;
            case BigInteger integer -> integer.signum() == 0;
            case Number number -> number.doubleValue() == 0;
            default -> false;
        };
    }

    /** How the note shows it: {@code []}, {@code {}}, {@code null}, {@code ""}, {@code 0.00}, {@code false}. */
    static String render(Object body) {
        return switch (body) {
            case null -> "null";
            case String text -> "\"" + text.strip() + "\"";
            case BigDecimal decimal -> decimal.toPlainString();
            default -> String.valueOf(body);
        };
    }
}
