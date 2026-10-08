package com.goldcatcher.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DocumentStore {
    public record Event(long sequence,String aggregateId,String type,tools.jackson.databind.JsonNode payload,java.time.Instant occurredAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public DocumentStore(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions, Clock clock) {
        this.jdbc=jdbc; this.mapper=mapper; this.transactions=transactions; this.clock=clock;
    }
    public <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    public void save(String kind, String id, Object document) {
        var body=mapper.writeValueAsString(document);
        int updated=jdbc.update("UPDATE gc_document SET body=?,updated_at=? WHERE kind=? AND id=?",
            body,java.sql.Timestamp.from(clock.instant()),kind,id);
        if(updated==0) jdbc.update("INSERT INTO gc_document(kind,id,body,updated_at) VALUES(?,?,?,?)",
            kind,id,body,java.sql.Timestamp.from(clock.instant()));
    }
    public <T> Optional<T> find(String kind, String id, Class<T> type) {
        return jdbc.query("SELECT body FROM gc_document WHERE kind=? AND id=?",
            (rs,row)->mapper.readValue(rs.getString(1),type),kind,id).stream().findFirst();
    }
    public <T> List<T> list(String kind, Class<T> type, int limit) {
        return jdbc.query("SELECT body FROM gc_document WHERE kind=? ORDER BY updated_at DESC LIMIT ?",
            (rs,row)->mapper.readValue(rs.getString(1),type),kind,limit);
    }
    public void event(String aggregate, String type, Object payload) {
        jdbc.update("INSERT INTO gc_event(aggregate_id,event_type,body,occurred_at) VALUES(?,?,?,?)",
            aggregate,type,mapper.writeValueAsString(payload),java.sql.Timestamp.from(clock.instant()));
    }
    public List<Event> events(String aggregate,int limit) {
        return jdbc.query("SELECT sequence,aggregate_id,event_type,body,occurred_at FROM gc_event WHERE aggregate_id=? ORDER BY sequence DESC LIMIT ?",
            (rs,row)->new Event(rs.getLong(1),rs.getString(2),rs.getString(3),mapper.readTree(rs.getString(4)),rs.getTimestamp(5).toInstant()),aggregate,Math.min(limit,200));
    }
}
