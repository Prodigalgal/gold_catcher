package com.goldcatcher.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DocumentStore {
    public record MarketFrame(long sequence,String symbol,String channel,long connectionEpoch,java.time.Instant sourceAt,java.time.Instant receivedAt,String body) {}
    public record Event(long sequence,String aggregateId,String type,tools.jackson.databind.JsonNode payload,java.time.Instant occurredAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String processId=java.util.UUID.randomUUID().toString();
    private final java.util.Map<String,java.util.concurrent.atomic.AtomicLong> revisions=new java.util.concurrent.ConcurrentHashMap<>();
    public DocumentStore(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions, Clock clock) {
        this.jdbc=jdbc; this.mapper=mapper; this.transactions=transactions; this.clock=clock;
    }
    public <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    /** Archive a canceled operation without passing its interrupt flag to JDBC. Never used for order execution. */
    public <T> T journal(Supplier<T> work){boolean interrupted=Thread.interrupted();try{return transaction(work);}finally{if(interrupted)Thread.currentThread().interrupt();}}
    public void save(String kind, String id, Object document) {
        var body=mapper.writeValueAsString(document);
        int updated=jdbc.update("UPDATE gc_document SET body=?,updated_at=? WHERE kind=? AND id=?",
            body,java.sql.Timestamp.from(clock.instant()),kind,id);
        if(updated==0) jdbc.update("INSERT INTO gc_document(kind,id,body,updated_at) VALUES(?,?,?,?)",
            kind,id,body,java.sql.Timestamp.from(clock.instant()));
        Runnable committed=()->revisions.computeIfAbsent(kind,key->new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){committed.run();}});
        else committed.run();
    }
    public String revision(String kind){return processId+":"+revisions.computeIfAbsent(kind,key->new java.util.concurrent.atomic.AtomicLong()).get();}
    public List<String> ids(String kind,int limit){return jdbc.query("SELECT id FROM gc_document WHERE kind=? ORDER BY updated_at DESC LIMIT ?",(rs,row)->rs.getString(1),kind,Math.max(1,Math.min(1000,limit)));}
    public <T> Optional<T> find(String kind, String id, Class<T> type) {
        return jdbc.query("SELECT body FROM gc_document WHERE kind=? AND id=?",
            (rs,row)->mapper.readValue(rs.getString(1),type),kind,id).stream().findFirst();
    }
    public <T> Optional<T> find(String kind,String id,tools.jackson.core.type.TypeReference<T> type){return jdbc.query("SELECT body FROM gc_document WHERE kind=? AND id=?",
        (rs,row)->mapper.readValue(rs.getString(1),type),kind,id).stream().findFirst();}
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
    public void marketFrames(List<MarketFrame> frames) {
        if(frames.isEmpty())return;
        transaction(()->{
        jdbc.batchUpdate("INSERT INTO gc_market_frame(symbol,channel,connection_epoch,source_at,received_at,body) VALUES(?,?,?,?,?,?)",
            frames.stream().map(f->new Object[]{f.symbol(),f.channel(),f.connectionEpoch(),f.sourceAt()==null?null:java.sql.Timestamp.from(f.sourceAt()),java.sql.Timestamp.from(f.receivedAt()),f.body()}).toList());
        var groups=new java.util.TreeMap<String,java.util.List<MarketFrame>>();frames.forEach(f->groups.computeIfAbsent(f.symbol()+"\0"+f.channel(),key->new java.util.ArrayList<>()).add(f));
        groups.forEach((key,rows)->{var group=rows.getFirst();
            var first=java.sql.Timestamp.from(rows.stream().map(MarketFrame::receivedAt).min(java.time.Instant::compareTo).orElseThrow());
            var last=java.sql.Timestamp.from(rows.stream().map(MarketFrame::receivedAt).max(java.time.Instant::compareTo).orElseThrow());
            int changed=jdbc.update("UPDATE gc_market_frame_stats SET frames=frames+?,first_at=CASE WHEN first_at<? THEN first_at ELSE ? END,last_at=CASE WHEN last_at>? THEN last_at ELSE ? END WHERE symbol=? AND channel=?",
                rows.size(),first,first,last,last,group.symbol(),group.channel());
            // The counter row is locked before reading boundaries, including concurrent retention.
            var range=jdbc.queryForMap("SELECT MIN(sequence) AS first_sequence,MAX(sequence) AS last_sequence FROM gc_market_frame WHERE symbol=? AND channel=?",group.symbol(),group.channel());
            if(changed==0)jdbc.update("INSERT INTO gc_market_frame_stats(symbol,channel,frames,first_sequence,last_sequence,first_at,last_at) VALUES(?,?,?,?,?,?,?)",
                group.symbol(),group.channel(),rows.size(),range.get("first_sequence"),range.get("last_sequence"),first,last);
            else jdbc.update("UPDATE gc_market_frame_stats SET first_sequence=?,last_sequence=? WHERE symbol=? AND channel=?",range.get("first_sequence"),range.get("last_sequence"),group.symbol(),group.channel());
        });return null;});
    }
    public List<MarketFrame> marketFrames(String symbol,long after,int limit) {
        return jdbc.query("SELECT sequence,symbol,channel,connection_epoch,source_at,received_at,body FROM gc_market_frame WHERE symbol=? AND sequence>? ORDER BY sequence LIMIT ?",
            (rs,row)->new MarketFrame(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant(),rs.getString(7)),symbol,after,Math.max(1,Math.min(500,limit)));
    }
    public List<java.util.Map<String,Object>> marketFrameCounts(String symbol) {
        return jdbc.query("SELECT channel,frames,first_sequence,last_sequence,first_at,last_at FROM gc_market_frame_stats WHERE symbol=? ORDER BY channel",
            (rs,row)->java.util.Map.of("channel",rs.getString(1),"frames",rs.getLong(2),"firstSequence",rs.getLong(3),"lastSequence",rs.getLong(4),"firstAt",rs.getTimestamp(5).toInstant(),"lastAt",rs.getTimestamp(6).toInstant()),symbol);
    }
    public int expireMarketFrames(java.time.Instant cutoff) {
        return transaction(()->{var time=java.sql.Timestamp.from(cutoff);
            var expired=jdbc.queryForList("SELECT symbol,channel,COUNT(*) AS removed FROM gc_market_frame WHERE received_at<? GROUP BY symbol,channel ORDER BY symbol,channel",time);
            int removed=jdbc.update("DELETE FROM gc_market_frame WHERE received_at<?",time);
            for(var row:expired){var symbol=row.get("symbol");var channel=row.get("channel");
                jdbc.update("UPDATE gc_market_frame_stats SET frames=frames-? WHERE symbol=? AND channel=?",row.get("removed"),symbol,channel);
                var range=jdbc.queryForMap("SELECT MIN(sequence) AS first_sequence,MAX(sequence) AS last_sequence,MIN(received_at) AS first_at,MAX(received_at) AS last_at FROM gc_market_frame WHERE symbol=? AND channel=?",symbol,channel);
                if(range.get("first_sequence")==null)jdbc.update("DELETE FROM gc_market_frame_stats WHERE symbol=? AND channel=?",symbol,channel);
                else jdbc.update("UPDATE gc_market_frame_stats SET first_sequence=?,last_sequence=?,first_at=?,last_at=? WHERE symbol=? AND channel=?",
                    range.get("first_sequence"),range.get("last_sequence"),range.get("first_at"),range.get("last_at"),symbol,channel);
            }return removed;});
    }
    public record AccountSample(String symbol,java.time.Instant at,String balance,String equity,String unrealized,String state) {}
    public void accountSample(AccountSample sample){jdbc.update("INSERT INTO gc_account_sample(symbol,sampled_at,balance,equity,unrealized,state) VALUES(?,?,?,?,?,?)",
        sample.symbol(),java.sql.Timestamp.from(sample.at()),sample.balance(),sample.equity(),sample.unrealized(),sample.state());}
    public List<AccountSample> accountSamples(String symbol){return jdbc.query("SELECT * FROM gc_account_sample WHERE symbol=? ORDER BY sampled_at DESC LIMIT 10080",
        (rs,row)->new AccountSample(rs.getString("symbol"),rs.getTimestamp("sampled_at").toInstant(),rs.getString("balance"),rs.getString("equity"),rs.getString("unrealized"),rs.getString("state")),symbol).reversed();}
    public int expireAccountSamples(java.time.Instant cutoff){return jdbc.update("DELETE FROM gc_account_sample WHERE sampled_at<?",java.sql.Timestamp.from(cutoff));}
}
