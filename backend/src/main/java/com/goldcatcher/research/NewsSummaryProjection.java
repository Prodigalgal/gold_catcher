package com.goldcatcher.research;

import java.time.Instant;
import java.util.*;

/** Completed summaries in, frozen downstream material out. No cache lookup, model call or persistent update. */
public final class NewsSummaryProjection {
    public static final String VERSION="NEWS_SUMMARY_SELECTION_V1";
    public record Item(Map<String,Object> original,String sourceHash,String sourceFormat,Integer originalCharacters,EvidenceCompression.Result result) {
        public Item {original=Collections.unmodifiableMap(new LinkedHashMap<>(original));}
    }
    public record Input(String version,boolean enabled,Instant cutoff,String provider,String model,List<Item> items) {
        public Input {items=List.copyOf(items);}
    }
    public record Result(List<Map<String,Object>> selected,Map<String,Object> audit) {}
    private NewsSummaryProjection() {}
    public static Result project(Input input){
        if(!VERSION.equals(input.version()))throw new IllegalArgumentException("UNSUPPORTED_NEWS_PROJECTION_VERSION");
        var selected=new ArrayList<Map<String,Object>>();var audit=new ArrayList<Map<String,Object>>();
        for(var item:input.items()){
            var row=new LinkedHashMap<>(item.original());var result=item.result();
            boolean eligible=input.enabled()&&result!=null&&row.get("id")!=null&&item.sourceHash()!=null&&!item.sourceHash().isBlank()
                &&Objects.equals(result.newsId(),row.get("id"))&&Objects.equals(result.sourceTextHash(),item.sourceHash())
                &&result.state().equals("EXTRACTED")&&result.completedAt()!=null&&!result.completedAt().isAfter(input.cutoff())
                &&result.totalChunks()>0&&result.processedChunks()==result.totalChunks()&&!result.excerpt().isBlank();
            var entry=new LinkedHashMap<String,Object>();entry.put("newsId",row.get("id"));entry.put("title",row.get("title"));entry.put("bodyHash",row.get("bodyHash"));
            entry.put("sourceTextHash",item.sourceHash());entry.put("sourceFormat",item.sourceFormat());entry.put("originalCharacters",item.originalCharacters());
            if(eligible){row.put("excerpt",result.excerpt());row.remove("sourceTextHash");selected.add(Collections.unmodifiableMap(row));
                entry.put("state",result.state());entry.put("resultId",result.id());entry.put("provider",result.provider());entry.put("model",result.model());entry.put("completedAt",result.completedAt());entry.put("processedChunks",result.processedChunks());entry.put("totalChunks",result.totalChunks());}
            else entry.put("state",!input.enabled()?"COMPRESSION_DISABLED":result==null?"COMPRESSION_PENDING":"COMPRESSION_RESULT_NOT_APPLICABLE");
            String excerpt=eligible?result.excerpt():"";entry.put("sentCharacters",excerpt.codePointCount(0,excerpt.length()));audit.add(Collections.unmodifiableMap(entry));
        }
        return new Result(List.copyOf(selected),Map.of("provider",input.provider(),"model",input.model(),"method",EvidenceCompression.VERSION,"voting",false,
            "cutoff",input.cutoff(),"articles",List.copyOf(audit),"completedArticles",selected.size(),"omittedArticles",audit.size()-selected.size()));
    }
}
