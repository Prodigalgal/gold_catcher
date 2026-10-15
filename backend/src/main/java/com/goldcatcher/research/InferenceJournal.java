package com.goldcatcher.research;

import com.goldcatcher.CanonicalJson;
import com.goldcatcher.persistence.DocumentStore;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

/** Effect boundary only: persist frozen inputs and observed replies; replay delegates exclusively to pure functions. */
@Service
public class InferenceJournal {
    public record Snapshot(String id,Instant createdAt,String version,String kind,InferenceRequest.Input input,
        ModelDataFlow.Result flow,String body,String bodyHash,String state,JsonNode accepted,String error) {}
    public record Exchange(int number,Integer status,String response,String clientTraceId,String requestId,String error) {}
    public record Replay(String version,boolean supported,boolean inputMatches,boolean requestMatches,boolean stepsMatch,
        String responseState,Boolean responseMatches,String responseError,List<ModelDataFlow.Step> steps) {}
    private final DocumentStore store;private final ObjectMapper mapper;private final Clock clock;
    public InferenceJournal(DocumentStore store,ObjectMapper mapper,Clock clock){this.store=store;this.mapper=mapper;this.clock=clock;}
    String prepare(InferenceRequest.Plan plan){String id=UUID.randomUUID().toString();
        var snapshot=new Snapshot(id,clock.instant(),ModelDataFlow.VERSION,plan.flow().kind(),plan.input(),plan.flow(),plan.prepared().body(),
            CanonicalJson.hash(plan.prepared().body()),"PREPARED",null,null);
        store.journal(()->{store.save("inference-flow",id,snapshot);return null;});return id;
    }
    void exchange(String id,int number,Integer status,byte[] response,String clientTrace,String requestId,String error){if(id==null)return;
        store.journal(()->{var old=store.find("inference-exchange",id+":"+number,Exchange.class).orElse(null);
            var value=new Exchange(number,status==null&&old!=null?old.status():status,response==null?(old==null?null:old.response()):new String(response,StandardCharsets.UTF_8),
                clientTrace,requestId==null&&old!=null?old.requestId():requestId,error);
            store.save("inference-exchange",id+":"+number,value);return null;});
    }
    void finish(String id,JsonNode output,String error){if(id==null)return;store.journal(()->{
        var old=find(id);store.save("inference-flow",id,new Snapshot(old.id(),old.createdAt(),old.version(),old.kind(),old.input(),old.flow(),old.body(),old.bodyHash(),
            error==null?"VALIDATED":"FAILED",output,error));return null;});}
    public Snapshot find(String id){checkId(id);return store.find("inference-flow",id,Snapshot.class).orElseThrow(()->new IllegalArgumentException("DATA_FLOW_NOT_FOUND"));}
    public Map<String,Object> detail(String id){return Map.of("snapshot",find(id),"exchanges",exchanges(id));}
    private List<Exchange> exchanges(String id){var out=new ArrayList<Exchange>();for(int n=1;n<=2;n++)store.find("inference-exchange",id+":"+n,Exchange.class).ifPresent(out::add);return List.copyOf(out);}
    public Replay replay(String id){return replay(mapper,find(id),exchanges(id));}
    public ModelDataFlow.StageSnapshot stage(String id,String name){var saved=find(id);
        if(!ModelDataFlow.VERSION.equals(saved.version()))throw new IllegalArgumentException("UNSUPPORTED_DATA_FLOW_VERSION");
        return ModelDataFlow.inspect(mapper,saved.input().packet(),saved.input().compression(),name);}
    public static Replay replay(ObjectMapper mapper,Snapshot saved,List<Exchange> exchanges){
        if(!ModelDataFlow.VERSION.equals(saved.version())||!InferenceRequest.VERSION.equals(saved.input().version()))
            return new Replay(saved.version(),false,false,false,false,"UNSUPPORTED_VERSION",null,null,List.of());
        var plan=InferenceRequest.prepare(mapper,saved.input());
        boolean inputMatches=plan.flow().output().equals(saved.flow().output());
        boolean requestMatches=plan.prepared().body().equals(saved.body())&&CanonicalJson.hash(saved.body()).equals(saved.bodyHash());
        boolean stepsMatch=plan.flow().steps().equals(saved.flow().steps());
        String state="NOT_RECORDED",error=null;Boolean matches=null;
        if(!exchanges.isEmpty()){
            var last=exchanges.getLast();
            if(last.response()!=null&&last.status()!=null){try{
                byte[] body=last.response().getBytes(StandardCharsets.UTF_8);
                if(last.status()!=200)throw new IllegalStateException(ModelResponse.httpError(mapper,last.status(),body));
                var decoded=ModelResponse.extract(mapper,body,saved.input().model());
                var output=ModelResponse.accept(mapper,decoded,saved.input().compression(),plan.adjudication(),plan.unified(),plan.structuredTool(),
                    saved.input().packet().has("phase")?saved.input().packet().path("phase").asInt():null);
                state="VALIDATED";matches=saved.state().equals("VALIDATED")&&output.equals(saved.accepted());
            }catch(Exception invalid){state="FAILED";error=Any2ApiClient.safeError(invalid);matches=saved.state().equals("FAILED")&&Objects.equals(error,saved.error());}}
            else {state="TRANSPORT_WITHOUT_COMPLETE_RESPONSE";error=last.error();}
        }
        return new Replay(saved.version(),true,inputMatches,requestMatches,stepsMatch,state,matches,error,plan.flow().steps());
    }
    private static void checkId(String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("INVALID_DATA_FLOW_ID");}
}
