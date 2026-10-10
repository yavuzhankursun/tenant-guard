package dev.tenantguard;

import java.sql.SQLWarning;

/**
 * A warning chain the way pgjdbc keeps one: notices are appended to the tail, a clear drops the
 * head. Lets unit tests stand in for the driver without a database.
 */
final class WarningChain {

    /** The nonce the scripted "observer" signs with; {@link #trustingGuard()} accepts it. */
    static final String NONCE = "0123456789abcdef0123456789abcdef";

    private SQLWarning head;

    /** A guard that trusts this chain's notices, as one would after {@code ObserverInstaller.install}. */
    static TenantGuard trustingGuard() {
        TenantGuard guard = new TenantGuard();
        guard.trust(new ObserverInstaller.Installation("scripted", NONCE));
        return guard;
    }

    SQLWarning head() {
        return head;
    }

    void notice(String table, String tenant) {
        add(new SQLWarning(RowRead.PREFIX + NONCE + "|r|" + table + "|" + tenant));
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
