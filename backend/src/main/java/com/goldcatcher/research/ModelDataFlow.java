package com.goldcatcher.research;

import com.goldcatcher.CanonicalJson;
import com.goldcatcher.EvidenceTable;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.UnaryOperator;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** A versioned, deterministic value pipeline. Each stage owns a copy; no external effects or live reads. */
public final class ModelDataFlow {
    public static final String VERSION="MODEL_DATA_FLOW_V1";
    public record Step(String name,String rule,int inputCharacters,int outputCharacters,String inputHash,String outputHash,List<String> changedPaths) {}
    public record Result(String version,String kind,JsonNode output,List<Step> steps) {}
    public record StageSnapshot(Step step,JsonNode input,JsonNode output) {}
    private ModelDataFlow() {}
    public static Result project(ObjectMapper mapper,JsonNode input,boolean compression){
        return project(mapper,input,compression,null);
    }
    public static StageSnapshot inspect(ObjectMapper mapper,JsonNode input,boolean compression,String stage){
        var snapshots=new ArrayList<StageSnapshot>();project(mapper,input,compression,snapshots);
        return snapshots.stream().filter(s->s.step().name().equals(stage)).findFirst().orElseThrow(()->new IllegalArgumentException("DATA_FLOW_STAGE_NOT_FOUND"));
    }
    private static Result project(ObjectMapper mapper,JsonNode input,boolean compression,List<StageSnapshot> snapshots){
        var stages=new ArrayList<Step>();JsonNode value=input.deepCopy();
        value=step(mapper,stages,snapshots,"boundary",compression?"只保留待压缩材料":"去除身份、引用与执行控制；保留金融事实",value,
            p->compression?ModelInput.compression(mapper,p):ModelInput.project(mapper,p));
        if(!compression){
            value=step(mapper,stages,snapshots,"effective-facts","采用实际费用及有效杠杆约束；相同资产与估值时点只声明一次",value,p->effective(mapper,p));
            value=step(mapper,stages,snapshots,"exact-duplicates","去除完全相同的摘要、日历项和空可选字段；不同口径保留",value,p->duplicates(mapper,p));
            value=step(mapper,stages,snapshots,"numeric-duplicates","核验规范化预期与原文相同；核验布林中轨与SMA20相等",value,p->numeric(mapper,p));
            value=step(mapper,stages,snapshots,"peer-prose","仅消除同一观点内部完全重复的标题、修订与影响说明；独立观点不合并",value,p->peers(mapper,p));
            value=step(mapper,stages,snapshots,"scope-notes","工程字段留在服务端；已知口径说明按适用品种精简一次",value,p->notes(mapper,p));
            value=step(mapper,stages,snapshots,"table-common","逐单元格核验后提取相同公共列；只接受实际更短的表示",value,p->tables(mapper,p));
            value=step(mapper,stages,snapshots,"driver-groups","同来源同频率分组，共享说明；日频、盘中、名义、实际分别保留",value,p->drivers(mapper,p));
            value=step(mapper,stages,snapshots,"release-schema","经济发布表共享相同字段定义；保留期次、发布时间及修订",value,p->headers(mapper,p));
        }
        return new Result(VERSION,compression?"COMPRESSION":"RESEARCH",CanonicalJson.tree(mapper,value),List.copyOf(stages));
    }
    public static String instruction(JsonNode output){
        var e=output.path("evidence");var a=e.path("analysis");String text="";
        if(a.path("drivers").isArray())text+="drivers为分组表，common适用组内各行。";
        if(a.has("metricColumns"))text+="metrics无columns时用analysis.metricColumns。";
        if(e.path("indicators").path("common").path("bbMiddleMeaning").asText().equals("sma20"))text+="bbMiddle与sma20相同，数值只列sma20。";
        if(e.path("account").isObject()&&!e.path("account").has("valuedAt")&&e.path("quote").hasNonNull("exchangeTime"))text+="账户估值时点同quote.exchangeTime。";
        return text.isEmpty()?"":"\n"+text;
    }
    private static JsonNode step(ObjectMapper m,List<Step> report,List<StageSnapshot> snapshots,String name,String rule,JsonNode input,UnaryOperator<JsonNode> transform){
        String before=CanonicalJson.text(m,input);JsonNode result=transform.apply(input.deepCopy());String after=CanonicalJson.text(m,result);
        var paths=new ArrayList<String>();diff("",input,result,paths);
        var step=new Step(name,rule,before.codePointCount(0,before.length()),after.codePointCount(0,after.length()),CanonicalJson.hash(before),CanonicalJson.hash(after),List.copyOf(paths));report.add(step);
        if(snapshots!=null)snapshots.add(new StageSnapshot(step,input.deepCopy(),result.deepCopy()));
        return result;
    }
    private static void diff(String path,JsonNode before,JsonNode after,List<String> out){
        if(before.equals(after))return;
        if(before.isObject()&&after.isObject()){var keys=new TreeSet<String>();keys.addAll(before.propertyNames());keys.addAll(after.propertyNames());
            for(String k:keys)diff(path+"/"+k.replace("~","~0").replace("/","~1"),before.path(k),after.path(k),out);}
        else out.add(path.isEmpty()?"/":path);
    }
    private static ObjectNode object(JsonNode n,String...path){for(String key:path)n=n.path(key);return n instanceof ObjectNode o?o:null;}
    private static JsonNode effective(ObjectMapper m,JsonNode p){var e=object(p,"evidence");if(e==null)return p;
        for(String key:List.of("quote","rules","account")){var row=object(e,key);if(row!=null&&row.path("symbol").equals(e.path("symbol")))row.remove("symbol");}
        var r=object(e,"rules");var c=object(e,"costConfiguration");
        if(r!=null&&c!=null&&decimal(c.path("takerFeeRate"))!=null)r.remove("takerFeeRate");
        if(r!=null&&c!=null&&c.path("feeUnit").asText().equals("USD_PER_LOT")&&sameNumber(r.path("commission_per_lot"),c.path("feePerLot")))r.remove("commission_per_lot");
        if(r!=null&&r.hasNonNull("maximumPermittedLeverage")&&r.path("permittedLeverages").isArray()&&!r.path("permittedLeverages").isEmpty())
            r.remove(List.of("leverage","leverages","weekly_limit_leverage"));
        var account=object(e,"account");if(account!=null&&account.hasNonNull("valuedAt")&&account.path("valuedAt").equals(e.path("quote").path("exchangeTime")))account.remove("valuedAt");
        return p;
    }
    private static JsonNode duplicates(ObjectMapper m,JsonNode p){var e=object(p,"evidence");if(e==null)return p;
        for(String key:List.of("news","calendar"))if(e.path(key).isArray()){
            var out=m.createArrayNode();var seen=new HashSet<String>();for(var item:e.path(key)){
                var value=item.deepCopy();if(key.equals("calendar")&&value.isObject()&&value.hasNonNull("scheduledAt"))
                    for(String empty:List.of("scheduledDate","endDate"))if(!value.hasNonNull(empty))((ObjectNode)value).remove(empty);
                if(seen.add(CanonicalJson.text(m,value)))out.add(value);
            }e.set(key,out);
        }
        for(var peer:p.path("peers")){var o=(ObjectNode)peer;for(String key:List.of("changes","impacts"))
            if(o.path(key).isNull()||o.path(key).isTextual()&&o.path(key).asText().isBlank()||o.path(key).isArray()&&o.path(key).isEmpty())o.remove(key);}
        if(p.path("peers").isArray()&&p.path("peers").isEmpty())((ObjectNode)p).remove("peers");return p;
    }
    private static JsonNode numeric(ObjectMapper m,JsonNode p){var e=object(p,"evidence");if(e==null)return p;
        var indicators=e.path("indicators");if(indicators.path("columns").isArray()){
            var rows=ModelInput.records(m,indicators);boolean same=!rows.isEmpty();for(var row:rows)if(!sameNumber(row.path("sma20"),row.path("bbMiddle")))same=false;
            if(same){var cols=new ArrayList<>(columns(indicators));cols.remove("bbMiddle");var out=table(m,rows,cols);out.withObject("common").put("bbMiddleMeaning","sma20");e.set("indicators",out);}
        }
        var a=object(e,"analysis");if(a==null)return p;var t=a.path("expectations");
        if(t.path("columns").isArray()){
            var rows=ModelInput.records(m,t);for(var row:rows)for(String field:List.of("forecast","previous")){
                String raw="raw"+Character.toUpperCase(field.charAt(0))+field.substring(1);var value=decimal(row.path(field));
                var normalized=rawNumber(row.path(raw).asText(),row.path("unit").asText());
                if(value!=null&&normalized!=null&&value.compareTo(normalized)==0)((ObjectNode)row).remove(raw);
            }
            var cols=new ArrayList<>(columns(t));cols.removeIf(k->Set.of("rawForecast","rawPrevious").contains(k)&&allEmpty(rows,k));a.set("expectations",table(m,rows,cols));
        }
        // A count can be omitted only if the complete corresponding detail is present and the metric is unique across releases.
        var revisions=ModelInput.records(m,a.path("revisions"));var counts=new HashMap<String,Integer>();
        for(var group:a.path("releases"))for(var row:ModelInput.records(m,group.path("metrics")))counts.merge(row.path("name").asText(),1,Integer::sum);
        for(var group:a.path("releases"))if(group.path("metrics").path("columns").isArray()){
            var rows=ModelInput.records(m,group.path("metrics"));boolean complete=!rows.isEmpty();
            for(var row:rows){long count=0;for(var rev:revisions)if(rev.path("name").equals(row.path("name")))count++;
                if(counts.getOrDefault(row.path("name").asText(),0)!=1||!row.hasNonNull("revisionCount")||row.path("revisionCount").asLong(-1)!=count)complete=false;}
            if(complete){var cols=new ArrayList<>(columns(group.path("metrics")));cols.remove("revisionCount");((ObjectNode)group).set("metrics",table(m,rows,cols));}
        }return p;
    }
    private static JsonNode peers(ObjectMapper m,JsonNode p){for(var value:p.path("peers")){
        var peer=(ObjectNode)value;String body=peer.path("output").asText();String title=peer.path("title").asText(),changes=peer.path("changes").asText();
        if(!title.isBlank()&&(body.contains(title)||Set.of("候选","修订","研究","观点").contains(title)))peer.remove("title");
        if(!changes.isBlank()&&body.contains(changes))peer.remove("changes");
        if(peer.path("impacts").isArray()){
            var kept=m.createArrayNode();String prose=body+"\n"+peer.path("changes").asText()+"\n"+peer.path("plan").path("riskAssessment").asText()+"\n"+peer.path("plan").path("invalidate").asText();
            for(var impact:peer.path("impacts")){
                String direction=impact.path("horizon").asText().equals("SHORT")?"shortDirection":"longDirection";
                boolean repeated=impact.path("direction").equals(peer.path(direction))&&Set.of("SHORT","LONG").contains(impact.path("horizon").asText());
                for(String key:List.of("reason","counter"))if(!impact.path(key).asText().isBlank()&&!prose.contains(impact.path(key).asText()))repeated=false;
                if(impact.propertyNames().stream().anyMatch(k->!Set.of("horizon","direction","reason","counter").contains(k)))repeated=false;
                if(!repeated)kept.add(impact);
            }if(kept.isEmpty())peer.remove("impacts");else peer.set("impacts",kept);
        }
    }return p;}
    private static JsonNode notes(ObjectMapper m,JsonNode p){var e=object(p,"evidence");if(e==null)return p;
        var provenance=object(e,"marketProvenance");if(provenance!=null)provenance.remove(List.of("venue","nativeSymbol","transport"));
        String legacy="公开taker费为假设；资金费用历史费率/边界mark，缺mark未知；强平简化；CFD隔夜费/执行未核实。";
        if(e.path("costLimitations").asText().equals(legacy))e.put("costLimitations",e.path("symbol").asText().equals("GATE:XAUUSD")?
            "CFD隔夜费/执行未核实，仅报价情景。":"资金费使用历史费率及边界mark，缺mark未知；强平简化。费用来源及假设见costConfiguration。");
        var a=object(e,"analysis");if(a!=null){
            replace(a,"expectationMeaning","TE forecast列是调查一致预期；rawProviderForecast是TE自有预测，不能混用。仅使用发布前实际收到的版本，不回填历史预期。多来源分别保存，不把重复提供方当独立样本。",
                "forecast为所给来源一致预期，rawProviderForecast为提供方自有预测；仅发布前可见版本，不混用或当作独立样本。");
            replace(a,"publicScope","每类1值、全量封存；COT周度，Census按field，拍卖倍数非收益率，GLD/IAU日频持仓非资金流；盘口/逐笔连续性未知。",
                "COT周度；Census按field；拍卖倍数非收益率；ETF日频持仓非资金流；盘口/逐笔连续性未知。");
            if(a.path("precision").asText().equals("价格/金额/时刻原精度，派生百分比舍入8位；全精度归档。")&&e.path("indicatorPrecision").asText().equals("Java派生指标舍入8位；原DECIMAL128数值归档。")){
                e.put("indicatorPrecision","原始金融值保持精度；Java派生指标及百分比最多8位小数。");a.remove("precision");}
        }return p;
    }
    private static void replace(ObjectNode node,String field,String original,String replacement){if(node.path(field).asText().equals(original))node.put(field,replacement);}
    private static JsonNode tables(ObjectMapper m,JsonNode p){
        if(p.isObject()&&p.path("columns").isArray()&&p.path("csv").isTextual())return factor(m,p);
        if(p.isObject()){var o=(ObjectNode)p;for(var f:new ArrayList<>(o.properties()))o.set(f.getKey(),tables(m,f.getValue()));}
        else if(p.isArray()){var a=(ArrayNode)p;for(int i=0;i<a.size();i++)a.set(i,tables(m,a.path(i)));}return p;
    }
    private static JsonNode factor(ObjectMapper m,JsonNode original){var rows=ModelInput.records(m,original);if(rows.size()<2)return original;
        var best=(ObjectNode)original.deepCopy();var cols=new ArrayList<String>();for(var column:original.path("columns"))cols.add(column.asText());
        for(String key:List.copyOf(cols)){
            if(cols.size()<2)break; // CSV must retain a physical row for each observation.
            var first=rows.path(0).path(key);if(first.isNull()||first.isMissingNode())continue;boolean equal=true;for(var r:rows)if(!r.path(key).equals(first))equal=false;if(!equal)continue;
            var nextCols=new ArrayList<>(cols);nextCols.remove(key);var candidate=table(m,rows,nextCols);
            if(best.path("common").isObject())candidate.set("common",best.path("common").deepCopy());candidate.withObject("common").set(key,first);
            if(cost(m,candidate)<cost(m,best)){assertRows(m,original,candidate);best=candidate;cols=nextCols;}
        }return best;
    }
    private static JsonNode drivers(ObjectMapper m,JsonNode p){var a=object(p,"evidence","analysis");if(a==null||!a.path("drivers").path("columns").isArray())return p;
        var original=a.path("drivers");var rows=ModelInput.records(m,original);var groups=new TreeMap<String,ArrayNode>();
        for(var r:rows)groups.computeIfAbsent(r.path("source").asText()+"|"+r.path("frequency").asText(),k->m.createArrayNode()).add(r);
        if(groups.size()<2)return p;var out=m.createArrayNode();for(var group:groups.values())out.add(factor(m,table(m,group,columns(original))));
        var restored=m.createArrayNode();for(var t:out)for(var r:ModelInput.records(m,t))restored.add(r);
        if(!canonicalRows(m,rows).equals(canonicalRows(m,restored)))throw new IllegalStateException("DRIVER_PROJECTION_CHANGED_VALUE");
        if(cost(m,out)+"drivers为分组表，common适用组内各行。".length()<cost(m,original))a.set("drivers",out);return p;
    }
    private static JsonNode headers(ObjectMapper m,JsonNode p){var a=object(p,"evidence","analysis");if(a==null)return p;var groups=new TreeMap<String,Integer>();
        for(var r:a.path("releases"))if(r.path("metrics").path("columns").isArray())groups.merge(r.path("metrics").path("columns").toString(),1,Integer::sum);
        if(groups.isEmpty())return p;var best=groups.entrySet().stream().max(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue).thenComparing(Map.Entry::getKey)).orElseThrow();
        if(best.getValue()<2)return p;var candidate=a.deepCopy();var cols=m.readTree(best.getKey());candidate.set("metricColumns",cols);
        for(var r:candidate.path("releases"))if(r.path("metrics").path("columns").equals(cols))((ObjectNode)r.path("metrics")).remove("columns");
        if(cost(m,candidate)+"metrics无columns时用analysis.metricColumns。".length()<cost(m,a))((ObjectNode)object(p,"evidence")).set("analysis",candidate);return p;
    }
    static List<String> columns(JsonNode t){var cols=new LinkedHashSet<String>();t.path("columns").forEach(v->cols.add(v.asText()));cols.addAll(t.path("common").propertyNames());cols.remove("bbMiddleMeaning");return List.copyOf(cols);}
    private static ObjectNode table(ObjectMapper m,JsonNode rows,List<String> columns){return (ObjectNode)m.valueToTree(EvidenceTable.pack(m,rows,columns));}
    private static int cost(ObjectMapper m,JsonNode n){return m.writeValueAsString(EvidenceWire.encode(m,CanonicalJson.tree(m,n),false)).length();}
    private static List<String> canonicalRows(ObjectMapper m,JsonNode rows){var out=new ArrayList<String>();for(var r:rows)out.add(CanonicalJson.text(m,r));Collections.sort(out);return out;}
    private static void assertRows(ObjectMapper m,JsonNode a,JsonNode b){if(!canonicalRows(m,ModelInput.records(m,a)).equals(canonicalRows(m,ModelInput.records(m,b))))throw new IllegalStateException("TABLE_PROJECTION_CHANGED_VALUE");}
    private static boolean allEmpty(JsonNode rows,String key){for(var r:rows)if(r.hasNonNull(key))return false;return true;}
    private static BigDecimal decimal(JsonNode value){try{return value.isNull()||value.isMissingNode()?null:new BigDecimal(value.asText());}catch(NumberFormatException invalid){return null;}}
    private static boolean sameNumber(JsonNode a,JsonNode b){var x=decimal(a);var y=decimal(b);return x!=null&&y!=null&&x.compareTo(y)==0;}
    private static BigDecimal rawNumber(String raw,String unit){if(!(unit.equals("COUNT")||unit.equals("PERCENT")||unit.startsWith("PERCENT_")))return null;
        String text=raw.strip().replace(",","");BigDecimal multiplier=BigDecimal.ONE;
        if(unit.startsWith("PERCENT")&&text.endsWith("%"))text=text.substring(0,text.length()-1);
        if(unit.equals("COUNT")&&!text.isEmpty()){String suffix=text.substring(text.length()-1);if(Set.of("K","M","B").contains(suffix)){multiplier=new BigDecimal(suffix.equals("K")?"1000":suffix.equals("M")?"1000000":"1000000000");text=text.substring(0,text.length()-1);}}
        try{return new BigDecimal(text).multiply(multiplier);}catch(NumberFormatException invalid){return null;}}
}
