package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.goldcatcher.analysis.AnalysisTypes.*;
import static com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrendAnalysisTest {
    TestSupport f=new TestSupport();
    DriverSources sources=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);
    MarketFeed market=mock(MarketFeed.class);
    TrendAnalysis analysis=new TrendAnalysis(sources,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),market,f.store,f.mapper,f.clock,new EventHub());
    NewsService.News article(Instant publication,String hash){return new NewsService.News("news-id","BLS_CPI","CPI","","https://www.bls.gov/news.release/cpi.htm",publication,f.clock.instant(),"OFFICIAL_SOURCE",1,hash);}
    NewsService.ArticleBody body(String hash,String actual){return new NewsService.ArticleBody("news-id","BLS_CPI","https://www.bls.gov/news.release/cpi.htm","CONSUMER PRICE INDEX - SEPTEMBER 2026 The Consumer Price Index for All Urban Consumers (CPI-U) increased "+actual+" percent on a seasonally adjusted basis.","AVAILABLE",hash,f.clock.instant(),f.clock.instant(),f.clock.instant().plusSeconds(60),1,null,false,hash,null,"HTML_TEXT");}
    @Test void persistsExactSurpriseAndPreviousVersionWithoutLateForecastBackfill(){
        Instant published=f.clock.instant().plusSeconds(60);
        sources.ingestExpectations(f.mapper.readTree("[{\"title\":\"CPI m/m\",\"country\":\"USD\",\"date\":\""+published+"\",\"forecast\":\"0.2%\",\"previous\":\"0.1%\"}]"));
        f.clock.advance(120000);analysis.ingest(article(published,"first"),body("first","0.3"),f.clock.instant());Instant firstCutoff=f.clock.instant();
        f.clock.advance(1000);analysis.ingest(article(published,"second"),body("second","0.4"),f.clock.instant());
        assertThat(analysis.releases(firstCutoff).getFirst().actual()).isEqualTo("0.3");assertThat(analysis.releases(firstCutoff).getFirst().surprise()).isEqualTo("0.1");
        assertThat(analysis.releases(f.clock.instant()).getFirst().actual()).isEqualTo("0.4");assertThat(analysis.releases(f.clock.instant()).getFirst().version()).isEqualTo(2);
        assertThat(f.store.list("economic-release-version",Release.class,10)).hasSize(2);
        analysis.ingest(article(published.minusSeconds(180),"old"),body("old","0.1"),f.clock.instant());
        assertThat(f.store.list("economic-release",Release.class,10).stream().filter(r->r.bodyHash().equals("old")).findFirst().orElseThrow().surprise()).isNull();
    }
    @Test void unverifiedOrFutureBodyDoesNotBecomeEvidence(){
        var article=article(f.clock.instant().plusSeconds(60),"future");analysis.ingest(article,body("future","0.3"),f.clock.instant());
        assertThat(analysis.releases(f.clock.instant())).isEmpty();
        article=article(f.clock.instant(),"current");analysis.ingest(article,body("different-feed-version","0.3"),f.clock.instant());assertThat(analysis.releases(f.clock.instant())).isEmpty();
    }
    private Snapshot snapshot(String trend){
        Map<String,Indicators> indicators=new LinkedHashMap<>();for(String frame:List.of("1","5","15","60","240","D"))indicators.put(frame,new Indicators(frame,100,f.clock.instant(),"READY",Map.of(),f.clock.instant(),"CURRENT",Map.of("trend",new IndicatorReading(trend,"test","test"))));
        var quote=new Quote("XAU_USDT","TEST",1,f.clock.instant(),f.clock.instant(),new BigDecimal("100"),new BigDecimal("101"),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,null,null,null,null,true);
        return new Snapshot("XAU_USDT","LIVE",quote,null,indicators,List.of(),f.clock.instant(),null);
    }
    @Test void opposingDollarAndStaleDataAreSeparateFromPriceStructure(){
        var dollar=new Driver("GATE_USIDX","美元指数","USD","GATE_PUBLIC_CFD","USIDX","https://api.gateio.ws","POLL_5_SECONDS","INDEX_POINTS","100",null,null,null,null,f.clock.instant(),f.clock.instant(),1,"open",Map.of("15","0.1"),null,"SERVER_RESPONSE_TIME","test");
        var h=TrendAnalysis.horizon("M5_15","5–15",List.of("1","5"),snapshot("UPTREND"),List.of(dollar),f.clock.instant(),List.of());assertThat(h.direction()).isEqualTo("MIXED");assertThat(h.counter()).anyMatch(s->s.contains("0.1%"));
        f.clock.advance(16000);h=TrendAnalysis.horizon("M5_15","5–15",List.of("1","5"),snapshot("UPTREND"),List.of(dollar),f.clock.instant(),List.of());assertThat(h.direction()).isEqualTo("BULLISH");assertThat(h.scope()).isEqualTo("PRICE_STRUCTURE_ONLY");
        var flat=TrendAnalysis.horizon("M5_15","5–15",List.of("1","5"),snapshot("RANGE"),List.of(),f.clock.instant(),List.of());assertThat(flat.direction()).isEqualTo("RANGE");
    }
    @Test void expiredGoldQuotePreventsFreshDirectionalConclusion(){
        var snapshot=snapshot("UPTREND");f.clock.advance(4000);var h=TrendAnalysis.horizon("M5_15","5–15",List.of("1","5"),snapshot,List.of(),f.clock.instant(),List.of());assertThat(h.direction()).isEqualTo("UNKNOWN");
    }
    @Test void marketReactionCannotUsePostEventPriceAsPreEventBaseline(){
        Instant published=f.clock.instant();var r=new Release("r","CPI_MOM","CPI","2026-09","PERCENT_MOM_SA","0.3",null,"OFFICIAL_STATEMENT",List.of(),null,null,null,null,"NO_VERIFIED_PRE_RELEASE_FORECAST","BLS_CPI","news","body",1,published,published,published,"test",EconomicExtractor.VERSION);
        analysis.observe("XAU_USDT",published.plusSeconds(1),"100",true);assertThat(analysis.reaction("XAU_USDT",r,published.plusSeconds(60)).state()).isEqualTo("NO_PRE_RELEASE_QUOTE");
        var other="GATE:XAUUSD";analysis.observe(other,published.minusSeconds(1),"100",false);
        for(int second=1;second<=60;second++)analysis.observe(other,published.plusSeconds(second),"101",false);
        assertThat(analysis.reaction(other,r,published.plusSeconds(60)).returnsPct()).containsEntry("1","1");
        analysis.observe(other,published.plusSeconds(90),"102",false);assertThat(analysis.reaction(other,r,published.plusSeconds(90)).state()).isEqualTo("QUOTE_GAP");
    }
    @Test void futureStatisticalDateIsNeverCurrent(){
        var d=new Driver("US_REAL_10Y","real","RATES","US_TREASURY","TC_10YEAR","https://home.treasury.gov","DAILY","PERCENT","2",null,null,null,"2026-10-09",null,f.clock.instant(),1,"BACKGROUND",Map.of(),null,"DATE_ONLY","test");
        assertThat(TrendAnalysis.dailyFresh(d,f.clock.instant())).isFalse();
    }
    @Test void frozenTablesKeepExactOfficialValuesAndReferenceMappingWithoutUiDirection(){
        var published=f.clock.instant().plusSeconds(60);
        sources.ingestExpectations(f.mapper.readTree("[{\"title\":\"CPI m/m\",\"country\":\"USD\",\"date\":\""+published+"\",\"forecast\":\"0.2%\",\"previous\":\"0.1%\"}]"));
        f.clock.advance(120000);analysis.ingest(article(published,"exact"),body("exact","0.30000000000000001"),f.clock.instant());
        when(market.snapshot("XAU_USDT")).thenReturn(snapshot("UPTREND"));
        var packet=f.mapper.valueToTree(analysis.freeze("XAU_USDT",f.clock.instant(),"run"));
        assertThat(packet.has("horizons")).isFalse();
        assertThat(packet.path("releases").path(0).path("csv").asText()).contains("econ:CPI_MOM","0.30000000000000001","0.10000000000000001");
        assertThat(packet.path("releaseSources").path("csv").asText()).contains(published.toString());assertThat(packet.path("releaseSources").path("common").path("extractionVersion").asText()).isEqualTo(EconomicExtractor.VERSION);
        var archived=f.store.find("research-analysis","run",View.class).orElseThrow();assertThat(archived.horizons()).hasSize(4);
        var references=f.store.find("research-analysis-references","run",Map.class).orElseThrow();
        assertThat(references.get("econ:CPI_MOM")).isEqualTo(archived.releases().getFirst().id());
        assertThat(references.get("s1")).isEqualTo("news-id");
    }
}
