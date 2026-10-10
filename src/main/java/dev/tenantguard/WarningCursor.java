package dev.tenantguard;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC warning chains only grow until cleared. Remembers how far a chain was read so each observer
 * notice is counted once, without clearing the chain the application may still want to see.
 */
final class WarningCursor {

    private int consumed;

    List<RowRead> drain(SQLWarning head) {
        List<RowRead> rows = new ArrayList<>();
        int index = 0;
        for (SQLWarning w = head; w != null; w = w.getNextWarning(), index++) {
            if (index >= consumed) {
                RowRead.parse(w).ifPresent(rows::add);
            }
        }
        consumed = index;
        return rows;
    }

    /** Call whenever the chain was emptied: by clearWarnings, or by the driver itself. */
    void reset() {
        consumed = 0;
    }
}
