package com.goldcatcher.external;

import com.goldcatcher.CanonicalJson;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.auth.PersonalAccessTokens;
import com.goldcatcher.auth.OAuthPolicy;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Durable at-most-once receipts. A crash after dispatch is unknown, never silently re-executed. */
@Service
public class ExternalCommands {
    public record Result(String operationId, String requestKey, String state, boolean ok, boolean replayed, JsonNode result) {}
    public static final class Rejected extends RuntimeException {
        public final int status;
        public final String operationId;
        public Rejected(int status, String message, String operationId) { super(message); this.status=status; this.operationId=operationId; }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String process = UUID.randomUUID().toString();
    private final Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public ExternalCommands(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc=jdbc;this.mapper=mapper;this.clock=clock; }
    public Result execute(Identity actor, String tool, JsonNode args, Function<String,Object> work) {
        String key=args.path("request_key").asText();
        if(!key.matches("[A-Za-z0-9_.:-]{8,128}"))throw new IllegalArgumentException("INVALID_REQUEST_KEY");
        String digest=CanonicalJson.hash(CanonicalJson.text(mapper, Map.of("tool",tool,"arguments",args)));
        String id=UUID.randomUUID().toString();
        running.add(id);
        try {
            jdbc.update("INSERT INTO gc_external_command(id,owner_name,command_key,tool_name,input_hash,actor_id,process_id,state,created_at) VALUES(?,?,?,?,?,?,?,'STARTED',?)",
                id,actor.owner(),key,tool,digest,actor.id(),process,Timestamp.from(clock.instant()));
        } catch(DuplicateKeyException duplicate) {
            running.remove(id);
            var row=jdbc.queryForMap("SELECT * FROM gc_external_command WHERE owner_name=? AND command_key=?",actor.owner(),key);
            String existing=(String)row.get("id");
            if(!tool.equals(row.get("tool_name"))||!digest.equals(row.get("input_hash")))throw new Rejected(409,"REQUEST_KEY_CONFLICT",existing);
            if(!"COMPLETED".equals(row.get("state")))throw new Rejected(409,running.contains(existing)?"COMMAND_IN_PROGRESS":"COMMAND_OUTCOME_UNKNOWN",existing);
            return new Result(existing,key,"COMPLETED",Boolean.TRUE.equals(row.get("succeeded")),true,mapper.readTree((String)row.get("result_body")));
        } catch(RuntimeException failure){running.remove(id);throw failure;
        }
        try {
        JsonNode result;
        boolean ok;
        try {
            result=mapper.valueToTree(work.apply(id));
            ok=!result.path("status").asText().equals("REJECTED");
        } catch(IllegalArgumentException invalid) {
            String error=invalid.getMessage();
            result=mapper.valueToTree(Map.of("error",error!=null&&error.matches("[A-Z0-9_]+")?error:"INVALID_COMMAND_ARGUMENTS"));ok=false;
        } catch(PersonalAccessTokens.Rejected denied){result=mapper.valueToTree(Map.of("error",denied.getMessage()));ok=false;
        } catch(OAuthPolicy.Failure denied){result=mapper.valueToTree(Map.of("error",denied.getMessage()));ok=false;
        } catch(RuntimeException unavailable) {
            // The operation may already have committed domain state. Retain STARTED for explicit reconciliation.
            throw new Rejected(503,"COMMAND_OUTCOME_UNKNOWN",id);
        }
        var durable=result.deepCopy();
        if(durable.isObject()&&durable.has("secret")){
            ((ObjectNode)durable).putNull("secret");((ObjectNode)durable).put("secretDelivery","ONE_TIME_RESPONSE_ALREADY_ISSUED");
        }
        jdbc.update("UPDATE gc_external_command SET state='COMPLETED',completed_at=?,succeeded=?,result_body=? WHERE id=?",
            Timestamp.from(clock.instant()),ok,mapper.writeValueAsString(durable),id);
        return new Result(id,key,"COMPLETED",ok,false,result);
        } catch(Rejected failure){throw failure;
        } catch(RuntimeException failure){throw new Rejected(503,"COMMAND_OUTCOME_UNKNOWN",id);
        } finally {running.remove(id);}
    }
    public Map<String,Object> find(String owner, String id) {
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("INVALID_OPERATION_ID");
        var rows=jdbc.queryForList("SELECT * FROM gc_external_command WHERE owner_name=? AND id=?",owner,id);
        if(rows.isEmpty())throw new IllegalArgumentException("OPERATION_NOT_FOUND");
        var row=rows.getFirst();var out=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","tool_name","command_key","created_at","completed_at","succeeded"))out.put(field,row.get(field));
        out.put("state","COMPLETED".equals(row.get("state"))?"COMPLETED":running.contains(id)?"IN_PROGRESS":"OUTCOME_UNKNOWN");
        out.put("result",row.get("result_body")==null?null:mapper.readTree((String)row.get("result_body")));return out;
    }
}
