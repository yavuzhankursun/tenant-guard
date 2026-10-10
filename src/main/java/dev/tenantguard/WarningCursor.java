package dev.tenantguard;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC warning chains only grow until cleared. Remembers how far a chain was read so each observer
 * notice is counted once, without clearing the chain the application may still want to see.
 * A cleared chain restarts with a new head object, so a changed head means "start over". That also
 * covers clears we never see: pgjdbc clearing on execute, or an execute on an unwrapped driver statement.
 */
final class WarningCursor {

    private SQLWarning head;
    private int consumed;

    List<RowRead> drain(SQLWarning current) {
        if (current != head) {
            head = current;
            consumed = 0;
        }
        List<RowRead> rows = new ArrayList<>();
        int index = 0;
        for (SQLWarning w = current; w != null; w = w.getNextWarning(), index++) {
            if (index >= consumed) {
                RowRead.parse(w).ifPresent(rows::add);
            }
        }
        consumed = index;
        return rows;
    }
}
