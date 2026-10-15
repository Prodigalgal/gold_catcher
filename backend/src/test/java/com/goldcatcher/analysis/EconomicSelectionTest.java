package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static com.goldcatcher.analysis.AnalysisTypes.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EconomicSelectionTest {
    final Instant at=Instant.parse("2026-10-15T07:00:00Z"),event=at.plusSeconds(3600);
    Expectation row(String id,String source,String forecast,String period,Instant known){return new Expectation(id,"PPI_MOM","PPI",event,"EXACT","HIGH","PERCENT_MOM_SA",forecast,"0.4",source,"https://example.invalid",known,1,"hash",forecast+"%","0.4%","0.8%",null,period);}
    @Test void oneSurveyPerDefinitionWinsWhileDifferentPeriodsAndProviderOwnPredictionStayDistinct(){
        var ff=row("ff","FOREX_FACTORY_PROVIDER_FORECAST","0.5","2026-09",at.minusSeconds(60));var te=row("te","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.6","2026-09",at.minusSeconds(120));
        var other=row("other","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.7","2026-08",at.minusSeconds(120));
        var result=EconomicSelection.expectations(List.of(ff,te,other),at);assertThat(result.values()).hasSize(2);
        var selected=result.values().stream().filter(e->e.referencePeriod().equals("2026-09")).findFirst().orElseThrow();
        assertThat(selected.forecast()).isEqualTo("0.6");assertThat(selected.rawProviderForecast()).isEqualTo("0.8%");
        assertThat(result.choices()).anyMatch(EconomicSelection.Choice::disagreement);
        assertThat(EconomicSelection.expectations(List.of(other,te,ff),at)).isEqualTo(result);
    }
    @Test void futureObservationsAndPostReleaseForecastsNeverFillAnEarlierSnapshot(){
        var future=row("future","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.9","2026-09",at.plusSeconds(1));
        var afterEvent=row("late","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.9","2026-09",event.plusSeconds(1));
        assertThat(EconomicSelection.expectations(List.of(future,afterEvent),at).values()).isEmpty();
        assertThat(EconomicSelection.expectations(List.of(afterEvent),event.plusSeconds(60)).values()).isEmpty();
    }
    @Test void equalQualitySameTimeConflictBecomesUnknownInsteadOfArbitrarySelection(){
        var result=EconomicSelection.expectations(List.of(row("one","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.5","2026-09",at),row("two","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.6","2026-09",at)),at);
        assertThat(result.values()).hasSize(1);assertThat(result.values().getFirst().forecast()).isNull();assertThat(result.values().getFirst().rawForecast()).isNull();assertThat(result.choices().getFirst().ambiguous()).isTrue();
    }
    @Test void equalDecimalFormattingIsNotAConflictAndPreviousConflictOnlyRemovesPrevious(){
        var first=row("one","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.6","2026-09",at);
        var formatted=row("two","TRADING_ECONOMICS_SURVEY_CONSENSUS","0.60","2026-09",at);
        assertThat(EconomicSelection.expectations(List.of(first,formatted),at).choices().getFirst().disagreement()).isFalse();
        var changed=new Expectation(formatted.id(),formatted.indicator(),formatted.title(),formatted.scheduledAt(),formatted.timingPrecision(),formatted.impact(),formatted.unit(),formatted.forecast(),"0.3",formatted.source(),formatted.url(),formatted.receivedAt(),formatted.version(),formatted.hash(),formatted.rawForecast(),"0.3%",formatted.rawProviderForecast(),formatted.rawActual(),formatted.referencePeriod());
        var result=EconomicSelection.expectations(List.of(first,changed),at);assertThat(result.values().getFirst().previous()).isNull();assertThat(result.values().getFirst().rawPrevious()).isNull();assertThat(result.values().getFirst().forecast()).isNotNull();
    }
}
