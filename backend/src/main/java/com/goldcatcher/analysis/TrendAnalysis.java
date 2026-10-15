package com.goldcatcher.analysis;

import static com.goldcatcher.analysis.AnalysisTypes.*;
import static com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.persistence.DocumentStore;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** Continuous structure observations, separate from AI judgment and order execution. */
@Service
public class TrendAnalysis {
    private final DriverSources sources;private final NewsService news;private final MarketFeed market;
    private final DocumentStore store;private final ObjectMapper mapper;private final Clock clock;private final EventHub events;
    private final AtomicBoolean extracting=new AtomicBoolean();private final Map<String,String> processed=new ConcurrentHashMap<>();
    private final SupplementalSources supplemental;
    private final Map<String,List<Sample>> samples=new ConcurrentHashMap<>();
    record Sample(Instant at,String value,boolean continuous) {}
    private static final MathContext MC=MathContext.DECIMAL128;
    public TrendAnalysis(DriverSources sources,NewsService news,MarketFeed market,DocumentStore store,ObjectMapper mapper,Clock clock,EventHub events){
        this(sources,news,market,store,mapper,clock,events,null);
    }
    @Autowired public TrendAnalysis(DriverSources sources,NewsService news,MarketFeed market,DocumentStore store,ObjectMapper mapper,Clock clock,EventHub events,SupplementalSources supplemental){
        this.sources=sources;this.news=news;this.market=market;this.store=store;this.mapper=mapper;this.clock=clock;this.events=events;
        this.supplemental=supplemental;
    }
    @Scheduled(fixedDelay=3000,initialDelay=6500) void extract(){
        if(!extracting.compareAndSet(false,true))return;try{
            Instant now=clock.instant();for(var article:news.analysisArticles(now)){
                var body=news.bodyForEvidence(article.id(),now);if(body==null||body.text().isBlank()||body.hash()==null)continue;
                String fingerprint=NewsService.hash(EconomicExtractor.VERSION+"|"+body.hash()+"|"+article.publishedAt()+"|"+article.version());if(fingerprint.equals(processed.get(article.id())))continue;
                ingest(article,body,now);processed.put(article.id(),fingerprint);
            }
        }finally{extracting.set(false);}
    }
    void ingest(NewsService.News article,NewsService.ArticleBody body,Instant at){
        if(article.publishedAt()==null||"FEED_TIMESTAMP".equals(article.publishedPrecision())||article.publishedAt().isAfter(at)||article.receivedAt().isAfter(at)||body.receivedAt()==null||body.receivedAt().isAfter(at)||!Objects.equals(body.feedHash(),article.contentHash()))return;
        for(var m:EconomicExtractor.extract(article.source(),article.title(),body.text())){
            String id="release:"+NewsService.hash(m.indicator()+"|"+m.period()+"|"+article.publishedAt());
            var old=store.find("economic-release",id,Release.class);if(old.isPresent()&&old.get().bodyHash().equals(body.hash())&&EconomicExtractor.VERSION.equals(old.get().extractionVersion()))continue;
            var expectation=sources.preRelease(m.indicator(),m.unit(),article.publishedAt());
            String forecast=expectation==null?null:expectation.forecast();boolean rounded=m.precision().equals("OFFICIAL_ROUNDED_MILLIONS");String surprise=forecast==null||rounded?null:plain(new BigDecimal(m.actual()).subtract(new BigDecimal(forecast),MC));
            Instant received=article.receivedAt().isAfter(body.receivedAt())?article.receivedAt():body.receivedAt();
            var release=new Release(id,m.indicator(),m.label(),m.period(),m.unit(),m.actual(),m.previous(),m.precision(),m.revisions(),forecast,surprise,
                expectation==null?null:expectation.source(),expectation==null?null:expectation.receivedAt(),expectation==null?"NO_VERIFIED_PRE_RELEASE_FORECAST":rounded?"ACTUAL_ROUNDED_SURPRISE_UNKNOWN":"PROVIDER_FORECAST_CAPTURED_BEFORE_RELEASE",
                article.source(),article.id(),body.hash(),old.map(r->r.version()+1).orElse(1),article.publishedAt(),received,at,m.excerpt(),EconomicExtractor.VERSION);
            store.transaction(()->{store.save("economic-release",id,release);store.save("economic-release-version",id+":"+release.version(),release);return release;});
        }
        events.changed();
    }
    public List<Release> releases(Instant cutoff){
        return EconomicSelection.releases(store.list("economic-release-version",Release.class,1200),cutoff);
    }
    @Scheduled(fixedDelay=1000,initialDelay=1800) void observe(){for(String symbol:List.of("XAU_USDT","GATE:XAUUSD")){
        var snapshot=market.snapshot(symbol);var quote=snapshot.quote();Instant now=clock.instant();if(!snapshot.state().equals("LIVE")||quote==null||!quote.priceFresh(now))continue;
        observe(symbol,quote.receivedAt(),plain(quote.bid().add(quote.ask(),MC).divide(BigDecimal.valueOf(2),MC)),quote.continuous());
    }}
    void observe(String symbol,Instant at,String mid,boolean continuous){samples.compute(symbol,(key,old)->{var rows=new ArrayList<>(old==null?List.of():old);
        if(!rows.isEmpty()&&!at.isAfter(rows.getLast().at()))return old;rows.add(new Sample(at,mid,continuous));
        rows.removeIf(p->p.at().isBefore(at.minusSeconds(2100)));return List.copyOf(rows);});}
    public View view(String symbol){return view(symbol,clock.instant());}
    public View view(String symbol,Instant cutoff){
        long start=System.nanoTime();var snapshot=market.snapshot(symbol);var drivers=sources.drivers(cutoff);var releases=releases(cutoff);var gaps=coverage(drivers,cutoff);
        var horizons=new ArrayList<Horizon>();horizons.add(horizon("M5_15","5–15 分钟",List.of("1","5"),snapshot,drivers,cutoff,gaps));
        horizons.add(horizon("M15_60","15–60 分钟",List.of("5","15"),snapshot,drivers,cutoff,gaps));
        horizons.add(horizon("H1_4","1–4 小时",List.of("60","240"),snapshot,drivers,cutoff,gaps));
        horizons.add(horizon("H4_48","4–48 小时",List.of("240","D"),snapshot,drivers,cutoff,gaps));
        var reactions=releases.stream().filter(r->r.publishedAt().isAfter(cutoff.minusSeconds(1800))).map(r->reaction(symbol,r,cutoff)).toList();
        var extra=supplemental==null?null:supplemental.view(cutoff);if(extra!=null){var combined=new ArrayList<>(gaps);combined.addAll(extra.gaps());gaps=List.copyOf(combined);}
        String version=NewsService.hash(mapper.writeValueAsString(Map.of("releases",releases.stream().map(r->r.id()+":"+r.version()).toList(),"background",drivers.stream().filter(d->d.frequency().equals("DAILY")).map(d->d.id()+":"+d.version()).toList(),"public",extra==null?List.of():extra.observations().stream().map(o->o.id()+":"+o.bodyHash()+":"+o.receivedAt()).toList())));
        return new View(symbol,cutoff,(System.nanoTime()-start)/1000,"JAVA_STRUCTURE_V1_UNCALIBRATED",List.copyOf(horizons),drivers,releases,sources.expectations(cutoff),sources.sources(),gaps,reactions,market.costConfiguration(symbol),version,extra);
    }
    static List<String> coverage(List<Driver> drivers,Instant cutoff){
        List<String> gaps=new ArrayList<>();gaps.add("公开盘中美债/TIPS报价已实现60秒检查；未验证开市连续性和端到端延迟，利率期货/FedWatch仍未接入");gaps.add("公开商业媒体RSS已接入；授权低延迟新闻线、全球ETF流量与期权仍缺失，GLD/IAU为日频持仓");
        for(String id:List.of("US_INTRADAY_NOMINAL_2Y","US_INTRADAY_NOMINAL_10Y","US_INTRADAY_REAL_10Y"))if(drivers.stream().noneMatch(d->d.id().equals(id)&&rateFresh(d,cutoff)))gaps.add(id+" 当前报价过期、休市或尚未取得");
        if(drivers.stream().noneMatch(d->d.id().equals("GATE_USIDX")&&fresh(d,cutoff)))gaps.add("当前美元指数 CFD 观察不可用或已过期");
        if(drivers.stream().noneMatch(d->d.id().equals("US_REAL_10Y")&&dailyFresh(d,cutoff)))gaps.add("美国实际收益率日频背景未取得或统计日过旧");
        return List.copyOf(gaps);
    }
    static Horizon horizon(String id,String label,List<String> frames,Snapshot market,List<Driver> drivers,Instant at,List<String> globalGaps){
        int up=0,down=0,ready=0,flat=0;List<String> support=new ArrayList<>(),counter=new ArrayList<>(),gaps=new ArrayList<>(),refs=new ArrayList<>();
        boolean price=market.state().equals("LIVE")&&market.quote()!=null&&!market.quote().receivedAt().isAfter(at)&&market.quote().priceFresh(at);
        if(!price)gaps.add("当前产品报价不可用或过期");
        for(String frame:frames){var indicator=market.indicators().get(frame);
            if(indicator==null||!indicator.state().equals("READY")||!"CURRENT".equals(indicator.freshness())||indicator.calculatedAt()==null||indicator.calculatedAt().isAfter(at)||indicator.closedThrough()==null||indicator.closedThrough().isAfter(at)){gaps.add(frame+" 周期完整已收盘指标不可用");continue;}
            var trend=indicator.readings().get("trend");if(trend==null){gaps.add(frame+" 周期趋势读数缺失");continue;}ready++;refs.add("indicators");if(trend.code().equals("RANGE"))flat++;
            String frameLabel=frame.equals("D")?"日线":frame+"分钟";
            if(trend.code().equals("UPTREND")){up++;support.add(frameLabel+"：收盘 > EMA20 > EMA60");}
            else if(trend.code().equals("DOWNTREND")){down++;counter.add(frameLabel+"：收盘 < EMA20 < EMA60");}
            else counter.add(frameLabel+"价格与均线未一致排列");
        }
        String direction=!price||ready!=frames.size()||!gaps.isEmpty()?"UNKNOWN":flat==frames.size()?"RANGE":up==frames.size()?"BULLISH":down==frames.size()?"BEARISH":"MIXED";
        int context=0;boolean background=id.equals("H4_48");
        for(var driver:drivers){
            String value=null;boolean inverse=false;boolean rate=false;
            if(!background&&driver.id().equals("GATE_USIDX")&&fresh(driver,at)){value=driver.returnsPct().get(id.equals("H1_4")?"60":"15");inverse=true;}
            if(background&&driver.id().equals("US_REAL_10Y")&&dailyFresh(driver,at)){value=driver.change();inverse=true;}
            if(!background&&(driver.id().equals("US_INTRADAY_NOMINAL_10Y")||driver.id().equals("US_INTRADAY_REAL_10Y"))&&rateFresh(driver,at)){
                value=driver.changesBp().get(id.equals("H1_4")?"60":id.equals("M5_15")?"5":"15");inverse=true;rate=true;}
            if(value==null)continue;int change=new BigDecimal(value).signum();if(change==0)continue;context++;refs.add(driver.id());boolean bullish=inverse&&change<0;
            String displayed=plain(new BigDecimal(value).setScale(4,RoundingMode.HALF_EVEN));
            String fact=driver.label()+(background?"上一统计日变化 "+displayed+"bp":"观察窗口变化约 "+displayed+(rate?"bp":"%"));
            if(bullish)support.add(fact+"；常见关系支持黄金，需市场确认");else counter.add(fact+"；常见关系压制黄金，需市场确认");
            if(direction.equals("BULLISH")&&!bullish||direction.equals("BEARISH")&&bullish)direction="MIXED";
        }
        if(context==0)gaps.add(background?"没有可用的实际收益率背景确认":"缺少对应窗口的美元关联变化");
        if(background)gaps.add("长线宏观、政策和事件解释待多 AI 复核");
        return new Horizon(id,label,direction,context==0?"PRICE_STRUCTURE_ONLY":"STRUCTURE_WITH_CONTEXT",List.copyOf(support),List.copyOf(counter),List.copyOf(gaps),
            "报价过期、所用已收盘结构变化或出现新重大事件时重新计算；这不是自动订单指令。",at.plusSeconds(3),refs.stream().distinct().toList());
    }
    Reaction reaction(String symbol,Release release,Instant cutoff){
        var rows=samples.getOrDefault(symbol,List.of()).stream().filter(p->!p.at().isAfter(cutoff)).toList();
        var before=rows.stream().filter(p->!p.at().isAfter(release.publishedAt())).max(Comparator.comparing(Sample::at)).orElse(null);
        if(before==null||Duration.between(before.at(),release.publishedAt()).toSeconds()>10)return new Reaction(release.id(),release.label(),release.publishedAt(),"NO_PRE_RELEASE_QUOTE",Map.of(),"BID_ASK_MID","公布前没有已收到的可靠报价；不以补抓K线回填成实时反应。");
        Map<String,String> changes=new LinkedHashMap<>();boolean gap=false;Sample last=before;
        for(var p:rows)if(p.at().isAfter(before.at())){if(Duration.between(last.at(),p.at()).toSeconds()>10)gap=true;last=p;}
        if(rows.isEmpty()||Duration.between(rows.getLast().at(),cutoff).toSeconds()>10)gap=true;
        if(gap)return new Reaction(release.id(),release.label(),release.publishedAt(),"QUOTE_GAP",Map.of(),"BID_ASK_MID","公布后报价路径存在超过10秒的观测缺口，暂不确认反应。");
        for(int minutes:List.of(1,5,15)){Instant target=release.publishedAt().plusSeconds(minutes*60L);if(target.isAfter(cutoff))continue;
            var after=rows.stream().filter(p->!p.at().isBefore(target)).findFirst().orElse(null);if(after!=null&&Duration.between(target,after.at()).toSeconds()<=10)changes.put(Integer.toString(minutes),pct(after.value(),before.value()));}
        var current=rows.getLast();if(current.at().isAfter(release.publishedAt()))changes.put("current",pct(current.value(),before.value()));
        return new Reaction(release.id(),release.label(),release.publishedAt(),changes.isEmpty()?"WAITING_RESPONSE":"OBSERVED",Map.copyOf(changes),"BID_ASK_MID",symbol.equals("GATE:XAUUSD")?"CFD 响应时刻的报价观察；最后 tick 时刻未知，不能据此归因或推断成交。":"记录已收到的中间价变化；相关反应不证明因果或可成交盈利。");
    }
    static boolean fresh(Driver d,Instant at){return d.marketStatus().equals("open")&&!d.receivedAt().isAfter(at)&&Duration.between(d.receivedAt(),at).toSeconds()<=15&&d.responseAt()!=null&&!d.responseAt().isAfter(at.plusSeconds(2))&&Duration.between(d.responseAt(),at).toSeconds()<=15;}
    static boolean rateFresh(Driver d,Instant at){return d.frequency().equals("INTRADAY_PUBLIC_WEB")&&!d.receivedAt().isAfter(at)&&Duration.between(d.receivedAt(),at).toSeconds()<=180&&CnbcRates.recent(d.responseAt(),at);}
    static boolean dailyFresh(Driver d,Instant at){try{long days=d.asOfDate()==null?-1:ChronoUnit.DAYS.between(LocalDate.parse(d.asOfDate()),at.atZone(ZoneId.of("America/New_York")).toLocalDate());return !d.receivedAt().isAfter(at)&&days>=0&&days<=5;}catch(RuntimeException invalid){return false;}}
    public Map<String,Object> freeze(String symbol,Instant cutoff,String runId){
        var snapshot=view(symbol,cutoff);
        var projected=AnalysisProjection.project(mapper,snapshot,cutoff);
        store.transaction(()->{store.save("research-analysis",runId,snapshot);
            store.save("research-analysis-references",runId,projected.references());
            store.save("research-analysis-selection",runId,projected.selection());
            store.save("research-analysis-projection",runId,Map.of("version",AnalysisProjection.VERSION,"cutoff",cutoff,"output",projected.packet()));return null;});
        return projected.packet();
    }
    private static String plain(BigDecimal value){return DriverSources.plain(value);}
    private static String pct(String after,String before){var prior=new BigDecimal(before);return plain(new BigDecimal(after).subtract(prior,MC).divide(prior,MC).multiply(BigDecimal.valueOf(100),MC));}
}
