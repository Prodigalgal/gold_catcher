package com.goldcatcher.external;

import com.goldcatcher.information.NewsService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.*;

/** Keyset reads over a fixed allowlist. No caller-provided SQL, table names or document kinds. */
@Repository
public class ExternalRecords {
    private record Cursor(String kind, Instant asOf, Instant at, String id, String query) {}
    private record Row(String id, Instant at, JsonNode value) {}
    public record Page(List<JsonNode> items, String nextCursor, int scanned, Instant asOf, String coverage) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public ExternalRecords(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; }
    public Page page(String kind, String after, int limit, String query, Function<JsonNode, JsonNode> select) {
        if (!List.of("news", "calendar", "research-summary").contains(kind)) throw new IllegalArgumentException("UNKNOWN_COLLECTION");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("INVALID_LIMIT");
        String fingerprint = NewsService.hash(query);
        Cursor cursor = new Cursor(kind, clock.instant(), null, null, fingerprint);
        if (after != null && !after.isBlank()) {
            try {
                if (after.length() > 1024) throw new IllegalArgumentException();
                cursor = mapper.readValue(Base64.getUrlDecoder().decode(after), Cursor.class);
                if (!kind.equals(cursor.kind()) || !fingerprint.equals(cursor.query()) || cursor.asOf() == null || cursor.at() == null
                    || cursor.asOf().isAfter(clock.instant().plusSeconds(2)) || cursor.at().isAfter(cursor.asOf())
                    || cursor.id() == null || cursor.id().length() > 120) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_CURSOR"); }
        }
        var args = new ArrayList<Object>(); args.add(kind); args.add(Timestamp.from(cursor.asOf()));
        String sql = "SELECT id,updated_at,body FROM gc_document WHERE kind=? AND updated_at<=?";
        if (cursor.at() != null) { sql += " AND (updated_at<? OR (updated_at=? AND id<?))"; args.add(Timestamp.from(cursor.at())); args.add(Timestamp.from(cursor.at())); args.add(cursor.id()); }
        sql += " ORDER BY updated_at DESC,id DESC LIMIT 501";
        var rows = jdbc.query(sql, (rs, index) -> new Row(rs.getString(1), rs.getTimestamp(2).toInstant(), mapper.readTree(rs.getString(3))), args.toArray());
        var result = new ArrayList<JsonNode>(); Row last = null; int scanned = 0;
        for (var row : rows) {
            if (scanned == 500 || result.size() == limit) break;
            last = row; scanned++;
            JsonNode selected = select.apply(row.value()); if (selected != null) result.add(selected);
        }
        String next = scanned < rows.size() && last != null ? Base64.getUrlEncoder().withoutPadding().encodeToString(
            mapper.writeValueAsString(new Cursor(kind, cursor.asOf(), last.at(), last.id(), fingerprint)).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(List.copyOf(result), next, scanned, cursor.asOf(), "STORED_RECORDS_BY_UPDATED_AT_DESC; MAX_500_SCANNED_PER_PAGE; FOLLOW_CURSOR_EVEN_IF_ITEMS_EMPTY; UPDATES_AFTER_ASOF_REQUIRE_NEW_QUERY");
    }
}
