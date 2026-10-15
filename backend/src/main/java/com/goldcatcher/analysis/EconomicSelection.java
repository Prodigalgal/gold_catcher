package com.goldcatcher.analysis;

import static com.goldcatcher.analysis.AnalysisTypes.*;
import java.time.Instant;
import java.util.*;

/** Pure as-of selection. Publication time and the time we actually knew a value are separate. */
public final class EconomicSelection {
    private EconomicSelection() {}
    public record Choice(String indicator,String period,Instant scheduledAt,String source,int candidates,
                         boolean disagreement,boolean ambiguous,String reason) {}
    public record Expectations(List<Expectation> values,List<Choice> choices) {
        public Expectations {values=List.copyOf(values);choices=List.copyOf(choices);}
    }

    public static List<Release> releases(Collection<Release> input,Instant cutoff) {
        var selected=new TreeMap<String,Release>();
        input.stream().filter(r->visible(r.publishedAt(),cutoff)&&visible(r.receivedAt(),cutoff)&&visible(r.extractedAt(),cutoff))
            .sorted(Comparator.comparing(Release::publishedAt).reversed()
                .thenComparing(Comparator.comparingInt((Release r)->authority(r.source())).reversed())
                .thenComparing(Release::extractedAt,Comparator.reverseOrder()).thenComparing(Release::version,Comparator.reverseOrder())
                .thenComparing(Release::id))
            .forEach(r->selected.putIfAbsent(r.indicator()+"|"+r.unit(),r));
        return selected.values().stream().sorted(Comparator.comparing(Release::publishedAt).reversed().thenComparing(Release::indicator)).toList();
    }

    public static Expectations expectations(Collection<Expectation> input,Instant cutoff) {
        // First collapse versions of each source observation, then compare the same definition/period/event.
        var versions=new TreeMap<String,Expectation>();
        input.stream().filter(e->visible(e.receivedAt(),cutoff)&&e.scheduledAt()!=null&&e.receivedAt().isBefore(e.scheduledAt()))
            .sorted(Comparator.comparing(Expectation::receivedAt).reversed().thenComparing(Expectation::version,Comparator.reverseOrder())
                .thenComparing(EconomicSelection::stable)).forEach(e->versions.putIfAbsent(e.id(),e));
        var groups=new TreeMap<String,List<Expectation>>();
        for(var e:versions.values()) {
            // Unmapped titles, different periods, units and event times never become an invented common series.
            String definition=e.indicator().startsWith("UNMAPPED:")?e.indicator()+"|"+e.title():e.indicator();
            String key=definition+"|"+e.unit()+"|"+Objects.toString(e.referencePeriod(),"UNKNOWN")+"|"+e.scheduledAt();
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(e);
        }
        var values=new ArrayList<Expectation>();var choices=new ArrayList<Choice>();
        for(var rows:groups.values()) {
            rows.sort(Comparator.comparingInt(EconomicSelection::quality).reversed()
                .thenComparing(Expectation::receivedAt,Comparator.reverseOrder()).thenComparing(Expectation::version,Comparator.reverseOrder())
                .thenComparing(EconomicSelection::stable));
            var first=rows.getFirst();var winner=first;boolean disagreement=rows.stream().filter(e->e.forecast()!=null).map(e->valueKey(e.forecast())).distinct().count()>1
                ||rows.stream().filter(e->e.previous()!=null).map(e->valueKey(e.previous())).distinct().count()>1;
            boolean forecastConflict=rows.stream().anyMatch(e->quality(e)==quality(first)&&e.receivedAt().equals(first.receivedAt())
                &&!Objects.equals(valueKey(e.forecast()),valueKey(first.forecast())));
            boolean previousConflict=rows.stream().anyMatch(e->quality(e)==quality(first)&&e.receivedAt().equals(first.receivedAt())
                &&!Objects.equals(valueKey(e.previous()),valueKey(first.previous())));
            boolean ambiguous=forecastConflict||previousConflict;
            if(ambiguous)winner=new Expectation(winner.id(),winner.indicator(),winner.title(),winner.scheduledAt(),winner.timingPrecision(),
                winner.impact(),winner.unit(),forecastConflict?null:winner.forecast(),previousConflict?null:winner.previous(),winner.source(),winner.url(),winner.receivedAt(),winner.version(),
                winner.hash(),forecastConflict?null:winner.rawForecast(),previousConflict?null:winner.rawPrevious(),winner.rawProviderForecast(),winner.rawActual(),winner.referencePeriod());
            values.add(winner);choices.add(new Choice(winner.indicator(),winner.referencePeriod(),winner.scheduledAt(),winner.source(),rows.size(),
                disagreement,ambiguous,ambiguous?"EQUAL_QUALITY_CONFLICT_VALUE_UNKNOWN":"NORMALIZED_SURVEY_THEN_TIME_PRECISION_THEN_KNOWN_VERSION"));
        }
        values.sort(Comparator.comparing(Expectation::scheduledAt).thenComparing(Expectation::indicator).thenComparing(Expectation::source));
        return new Expectations(values,choices);
    }
    private static int quality(Expectation e){return (e.forecast()!=null?100:0)+
        (e.source().equals("TRADING_ECONOMICS_SURVEY_CONSENSUS")?20:e.source().equals("FOREX_FACTORY_PROVIDER_FORECAST")?10:0)+
        (e.timingPrecision().equals("EXACT")?2:0);}
    private static int authority(String source){return Set.of("BLS","BEA","DOL","CENSUS","US_TREASURY").contains(source)||source.startsWith("BLS_")||source.startsWith("BEA_")?1:0;}
    private static String valueKey(String value){if(value==null)return null;try{return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException invalid){return value;}}
    private static boolean visible(Instant time,Instant cutoff){return time!=null&&!time.isAfter(cutoff);}
    private static String stable(Expectation e){return String.join("|",e.source(),e.id(),Objects.toString(e.forecast(),""),Objects.toString(e.rawForecast(),""));}
}
