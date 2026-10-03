package com.familyfinance.accounting;

import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Global catalogue existence checks need current reads without cross-household exclusive lock cycles. */
@Component
class LedgerSecurityCatalog {
    private final JdbcTemplate jdbc;
    private final String product;

    LedgerSecurityCatalog(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        product=jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if(!"PostgreSQL".equals(product) && !"H2".equals(product))
            throw new IllegalStateException("Unsupported accounting database dialect");
    }

    Set<Long> lockCurrent(Set<Long> requestedIds) {
        if(requestedIds.isEmpty()) return Set.of();
        if("H2".equals(product)) {
            // H2 is test-only and has no shared current-read SQL syntax. Serializing its
            // entire catalogue keeps a stable transaction-wide lock order across posts.
            return new HashSet<>(jdbc.queryForList("select id from securities order by id for update",Long.class));
        }
        Set<Long> existing=new HashSet<>();
        for(long id:requestedIds) {
            // Shared locks protect catalogue rows without serializing independent households.
            existing.addAll(jdbc.queryForList("select id from securities where id=? for share",Long.class,id));
        }
        return existing;
    }
}
