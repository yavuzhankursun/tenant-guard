package dev.tenantguard;

import java.sql.SQLWarning;

/**
 * A warning chain the way pgjdbc keeps one: notices are appended to the tail, a clear drops the
 * head. Lets unit tests stand in for the driver without a database.
 */
final class WarningChain {

    private SQLWarning head;

    SQLWarning head() {
        return head;
    }

    void notice(String table, String tenant) {
        add(new SQLWarning(RowRead.PREFIX + table + "|" + tenant));
    }

    void add(SQLWarning warning) {
        if (head == null) {
            head = warning;
        } else {
            head.setNextWarning(warning);
        }
    }

    void clear() {
        head = null;
    }
}
