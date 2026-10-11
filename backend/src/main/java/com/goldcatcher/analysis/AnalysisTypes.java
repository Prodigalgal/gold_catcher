package com.goldcatcher.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AnalysisTypes {
    private AnalysisTypes() {}
    public record Driver(String id,String label,String family,String source,String nativeSymbol,String url,
        String frequency,String unit,String value,String previous,String change,String changeUnit,
        String asOfDate,Instant responseAt,Instant receivedAt,int version,String marketStatus,
        Map<String,String> returnsPct,String referenceChangePct,String timestampMeaning,String note,Map<String,String> changesBp) {
        public Driver { changesBp=changesBp==null?Map.of():Map.copyOf(changesBp); }
        public Driver(String id,String label,String family,String source,String nativeSymbol,String url,
            String frequency,String unit,String value,String previous,String change,String changeUnit,
            String asOfDate,Instant responseAt,Instant receivedAt,int version,String marketStatus,
            Map<String,String> returnsPct,String referenceChangePct,String timestampMeaning,String note) {
            this(id,label,family,source,nativeSymbol,url,frequency,unit,value,previous,change,changeUnit,
                asOfDate,responseAt,receivedAt,version,marketStatus,returnsPct,referenceChangePct,timestampMeaning,note,Map.of());
        }
    }
    public record SourceCheck(String id,String label,String url,String state,int intervalSeconds,
        Instant lastSuccess,Instant nextPoll,String error,int records) {}
    public record Expectation(String id,String indicator,String title,Instant scheduledAt,String timingPrecision,
        String impact,String unit,String forecast,String previous,String source,String url,
        Instant receivedAt,int version,String hash,String rawForecast,String rawPrevious) {}
    public record Revision(String period,String before,String after,String delta,String unit,String excerpt) {}
    public record Metric(String indicator,String label,String period,String unit,String actual,String previous,
        String precision,String excerpt,List<Revision> revisions) {}
    public record Release(String id,String indicator,String label,String period,String unit,String actual,String previous,
        String precision,List<Revision> revisions,String forecast,String surprise,String expectationSource,
        Instant expectationReceivedAt,String expectationState,String source,String newsId,String bodyHash,
        int version,Instant publishedAt,Instant receivedAt,Instant extractedAt,String excerpt,String extractionVersion) {}
    public record Horizon(String id,String label,String direction,String scope,List<String> support,
        List<String> counter,List<String> gaps,String invalidation,Instant validUntil,List<String> evidenceIds) {}
    public record Reaction(String eventId,String label,Instant publishedAt,String state,Map<String,String> returnsPct,
        String basis,String note) {}
    public record View(String symbol,Instant calculatedAt,long calculationMicros,String method,
        List<Horizon> horizons,List<Driver> drivers,List<Release> releases,List<Expectation> expectations,
        List<SourceCheck> sources,List<String> gaps,List<Reaction> reactions,Map<String,String> costs,
        String evidenceVersion,SupplementalSources.View supplemental) {}
}
