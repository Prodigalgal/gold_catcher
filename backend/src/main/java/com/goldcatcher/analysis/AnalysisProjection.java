package com.goldcatcher.analysis;

import com.goldcatcher.EvidenceTable;
import java.math.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Frozen values in, archive-ready facts out. No clock, database, network, event bus or live service reads. */
public final class AnalysisProjection {
    public static final String VERSION="ANALYSIS_PROJECTION_V2";
    private AnalysisProjection() {}
    public record Result(Map<String,Object> packet,Map<String,String> references,List<EconomicSelection.Choice> selection) {}
    public static Result project(ObjectMapper mapper,AnalysisTypes.View view,Instant cutoff) {
        String symbol=view.symbol();Map<String,Object> packet=new LinkedHashMap<>();
        packet.put("method","FROZEN_OFFICIAL_FACTS_V1");
        var refs=new ArrayList<String>();var referenceMap=new LinkedHashMap<String,String>();
        var sourceDrivers=view.drivers().stream().filter(d->d.receivedAt()!=null&&!d.receivedAt().isAfter(cutoff)&&(d.responseAt()==null||!d.responseAt().isAfter(cutoff))).sorted(Comparator.comparing(AnalysisTypes.Driver::id)).toList();
        var compactDrivers=mapper.valueToTree(sourceDrivers);for(int i=0;i<sourceDrivers.size();i++){var d=(tools.jackson.databind.node.ObjectNode)compactDrivers.path(i);var original=sourceDrivers.get(i);refs.add(original.id());referenceMap.put(original.id(),original.id());
            d.put("freshness",original.frequency().equals("DAILY")?(TrendAnalysis.dailyFresh(original,cutoff)?"DAILY_BACKGROUND":"AS_OF_DATE_STALE"):original.frequency().equals("INTRADAY_PUBLIC_WEB")?(TrendAnalysis.rateFresh(original,cutoff)?"RECENT_PROVIDER_DATED_QUOTE; LATENCY_NOT_SLA":"STALE_OR_CLOSED"):(TrendAnalysis.fresh(original,cutoff)?"RECENT_RESPONSE_TICK_TIME_UNKNOWN":"STALE_OR_CLOSED"));}
        for(var node:compactDrivers){var d=(tools.jackson.databind.node.ObjectNode)node;for(String minutes:List.of("5","15","60")){
            var value=d.path("returnsPct").path(minutes);if(!value.isMissingNode()&&!value.isNull())d.put("return"+minutes+"Pct",plain(new BigDecimal(value.asText()).setScale(8,RoundingMode.HALF_EVEN)));
            var bp=d.path("changesBp").path(minutes);if(!bp.isMissingNode()&&!bp.isNull())d.put("change"+minutes+"Bp",bp.asText());}}
        packet.put("drivers",EvidenceTable.pack(mapper,compactDrivers,List.of("id","source","frequency","unit","value","previous","change","changeUnit","asOfDate","responseAt","receivedAt","marketStatus","freshness","return5Pct","return15Pct","return60Pct","change5Bp","change15Bp","change60Bp")));
        packet.put("driverTimeMeaning","Gate timestamp是响应时刻，tick未知；Treasury日频；CNBC responseAt是带日期/偏移的提供方报价时刻，bp窗口仅用已观察样本；USIDX基准未核实。");
        var revisionRows=mapper.createArrayNode();
        Map<String,Map<String,Object>> grouped=new LinkedHashMap<>();for(var release:EconomicSelection.releases(view.releases(),cutoff)){
            String key=release.newsId()+":"+release.bodyHash()+":"+release.extractedAt()+":"+release.precision()+":"+release.extractionVersion();var group=grouped.computeIfAbsent(key,k->{Map<String,Object> row=new LinkedHashMap<>();String id="s"+(grouped.size()+1);row.put("id",id);referenceMap.put(id,release.newsId());row.put("source",release.source());row.put("period",release.period());row.put("publishedAt",release.publishedAt());row.put("receivedAt",release.receivedAt());row.put("extractedAt",release.extractedAt());row.put("precision",release.precision());row.put("extractionVersion",release.extractionVersion());row.put("metrics",new ArrayList<>());return row;});
            String alias="econ:"+release.indicator();refs.add(alias);referenceMap.put(alias,release.id());
            var metric=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(release);metric.put("id",alias);metric.put("revisionCount",release.revisions().size());
            for(var revision:release.revisions()){var row=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(revision);row.put("metricId",alias);revisionRows.add(row);}
            @SuppressWarnings("unchecked") var metrics=(List<Object>)group.get("metrics");metrics.add(metric);
        }
        var metricColumns=List.of("id","unit","actual","previous","forecast","surprise","expectationSource","expectationReceivedAt","revisionCount");packet.put("metricColumns",metricColumns);
        var metadata=mapper.createArrayNode();var releaseGroups=new ArrayList<Map<String,Object>>();for(var group:grouped.values()){
            var source=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(group);source.remove("metrics");metadata.add(source);
            releaseGroups.add(Map.of("sourceId",group.get("id"),"csv",EvidenceTable.pack(mapper,mapper.valueToTree(group.get("metrics")),metricColumns).get("csv")));
        }
        packet.put("releaseSources",EvidenceTable.packFactored(mapper,metadata,List.of("id","source","period","publishedAt","receivedAt","extractedAt","precision","extractionVersion"),List.of("extractedAt","extractionVersion","precision")));
        packet.put("releases",releaseGroups);packet.put("revisions",EvidenceTable.pack(mapper,revisionRows,List.of("metricId","period","before","after","delta","unit")));
        packet.put("releaseMeaning","sourceId关联releaseSources元数据；forecast空=无发布前预期；原文/hash/id映射归档。");
        var selection=EconomicSelection.expectations(view.expectations(),cutoff);
        var expected=mapper.valueToTree(selection.values().stream().filter(e->!e.scheduledAt().isBefore(cutoff)&&e.scheduledAt().isBefore(cutoff.plusSeconds(172800))).limit(6).toList());
        int index=0;for(var node:expected){var row=(tools.jackson.databind.node.ObjectNode)node;String alias="ff:"+(++index);refs.add(alias);referenceMap.put(alias,row.path("id").asText());row.put("id",alias);}
        packet.put("expectations",EvidenceTable.pack(mapper,expected,List.of("id","indicator","title","source","scheduledAt","timingPrecision","impact","unit","forecast","previous","rawForecast","rawPrevious","rawProviderForecast","referencePeriod","receivedAt","version")));
        packet.put("expectationMeaning","TE forecast列是调查一致预期；rawProviderForecast是TE自有预测，不能混用。仅使用发布前实际收到的版本，不回填历史预期。多来源分别保存，不把重复提供方当独立样本。");
        packet.put("expectationSource",Map.of("source","FOREX_FACTORY_PROVIDER_FORECAST","url",DriverSources.CALENDAR));
        packet.put("evidenceIds",List.copyOf(refs));
        packet.put("precision","价格/金额/时刻原精度，派生百分比舍入8位；全精度归档。");
        packet.put("expectationSelection","本周未来48h最多6项USD；UNMAPPED仅原文、不算差值；无实际值。");
        if(view.supplemental()!=null){
            var extra=mapper.createArrayNode();var latest=view.supplemental().observations().stream().filter(o->!o.receivedAt().isAfter(cutoff)&&(o.publishedAt()==null||!o.publishedAt().isAfter(cutoff))).sorted(Comparator.comparing(SupplementalSources.Observation::period).reversed()).toList();
            Set<String> selected=new HashSet<>();for(var o:latest){String field=switch(o.source()){case "DOL_CLAIMS"->o.id().equals("INITIAL_CLAIMS")?"actual":null;case "CFTC_GOLD"->"managedNet";case "CENSUS_ECON"->o.label().contains("Retail and Food Services")?"reportedChangePct":null;case "TREASURY_AUCTIONS"->o.label().startsWith("Note")||o.label().startsWith("Bond")?"bid_to_cover_ratio":null;case "IAU_HOLDINGS","GLD_HOLDINGS"->"tonnes";default->null;};
                if(field==null||!o.values().containsKey(field)||!selected.add(o.source()))continue;String alias="pub:"+(extra.size()+1);referenceMap.put(alias,o.id());
                var row=extra.addObject().put("id",alias).put("source",o.source()).put("period",o.period()).put("field",field).put("value",o.values().get(field));row.put("unit",field.equals("bid_to_cover_ratio")?"RATIO":field.equals("tonnes")?"TONNES":o.unit());row.put("publishedAt",o.publishedAt()==null?null:o.publishedAt().toString());row.put("receivedAt",o.receivedAt().toString());
            }
            var depth=view.supplemental().depth();if(symbol.equals("XAU_USDT")&&depth!=null&&!depth.receivedAt().isAfter(cutoff)&&Duration.between(depth.receivedAt(),cutoff).toSeconds()<=10){String alias="pub:"+(extra.size()+1);referenceMap.put(alias,"public-depth:XAU_USDT:"+depth.sequence());extra.addObject().put("id",alias).put("source","GATE_DEPTH").put("field","20LevelQuantityImbalance").put("value",plain(new BigDecimal(depth.imbalance()).setScale(8,RoundingMode.HALF_EVEN))).put("unit","RATIO").put("receivedAt",depth.receivedAt().toString());}
            var trades=view.supplemental().trades();if(symbol.equals("XAU_USDT")&&trades!=null&&trades.vwap()!=null&&!trades.receivedAt().isAfter(cutoff)&&Duration.between(trades.receivedAt(),cutoff).toSeconds()<=10){String alias="pub:"+(extra.size()+1);referenceMap.put(alias,"public-trades:XAU_USDT:"+trades.receivedAt());extra.addObject().put("id",alias).put("source","GATE_TRADES").put("period",trades.firstAt()+"/"+trades.lastAt()).put("field","recentSampleVwap").put("value",plain(new BigDecimal(trades.vwap()).setScale(8,RoundingMode.HALF_EVEN))).put("unit","USDT_PER_XAU").put("receivedAt",trades.receivedAt().toString());}
            packet.put("publicFacts",EvidenceTable.pack(mapper,extra,List.of("id","source","period","field","unit","value","publishedAt","receivedAt")));
            packet.put("publicScope","每类1值、全量封存；COT周度，Census按field，拍卖倍数非收益率，GLD/IAU日频持仓非资金流；盘口/逐笔连续性未知。");

        }
        var gaps=new ArrayList<>(view.gaps().stream().filter(g->!g.startsWith("补充视图最多读取")).toList());
        for(var driver:view.drivers())if(!sourceDrivers.contains(driver))gaps.add(driver.label()+"：收到或报价时间晚于研究截止，本轮未采用。");
        packet.put("gaps",List.copyOf(gaps));
        var conflicts=selection.choices().stream().filter(EconomicSelection.Choice::disagreement)
            .map(c->c.indicator()+ (c.ambiguous()?": 同质量同一时点数值冲突，相应值未知。":": 公开预期来源有分歧，采用所给来源的一份预期。 ")).toList();
        if(!conflicts.isEmpty())packet.put("expectationDisagreements",conflicts);
        return new Result(Collections.unmodifiableMap(packet),Collections.unmodifiableMap(referenceMap),selection.choices());
    }
    private static String plain(BigDecimal value){return DriverSources.plain(value);}
}
