package com.goldcatcher.analysis;

import static com.goldcatcher.analysis.AnalysisTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.GateCfdFeed;
import com.goldcatcher.network.BrowserSourceRequest;
import com.goldcatcher.persistence.DocumentStore;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.math.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import tools.jackson.databind.*;

/** Public, read-only adapters with frequency and knowledge time preserved independently. */
@Service
public class DriverSources {
    static final String TREASURY="https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml";
    static final String CALENDAR="https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final MathContext MC=MathContext.DECIMAL128;
    private final RuntimeSettings settings;private final DocumentStore store;private final RemoteHttp http;
    private final ObjectMapper mapper;private final GateCfdFeed gate;private final EventHub events;private final Clock clock;
    private final Map<String,Driver> drivers=new ConcurrentHashMap<>();private final Map<String,SourceCheck> checks=new ConcurrentHashMap<>();
    private final Map<String,List<Price>> histories=new ConcurrentHashMap<>();private final Map<String,Instant> historyAt=new ConcurrentHashMap<>();
    private final Map<String,String> historyErrors=new ConcurrentHashMap<>();private final Set<String> working=ConcurrentHashMap.newKeySet();
    record Price(Instant at,BigDecimal close) {}
    public DriverSources(RuntimeSettings settings,DocumentStore store,RemoteHttp http,ObjectMapper mapper,GateCfdFeed gate,EventHub events,Clock clock){
        this.settings=settings;this.store=store;this.http=http;this.mapper=mapper;this.gate=gate;this.events=events;this.clock=clock;
    }
    private boolean enabled(){return (settings.enabled("GOLD_NEWS_ENABLED")||settings.enabled("GOLD_MARKET_ENABLED"))&&Boolean.parseBoolean(settings.text("GOLD_DRIVERS_ENABLED","true"));}
    @PostConstruct void recover(){store.list("gold-driver",Driver.class,20).forEach(d->drivers.put(d.id(),d));store.list("driver-source",SourceCheck.class,20).forEach(c->checks.put(c.id(),new SourceCheck(c.id(),c.label(),c.url(),c.state(),c.intervalSeconds(),c.lastSuccess(),clock.instant(),c.error(),c.records())));}
    public List<Driver> drivers(Instant cutoff){return drivers.values().stream().filter(d->!d.receivedAt().isAfter(cutoff)).sorted(Comparator.comparing(Driver::id)).toList();}
    public List<SourceCheck> sources(){var result=new ArrayList<SourceCheck>();for(String symbol:List.of("USIDX","EURUSD","USDJPY"))result.add(check("GATE_"+symbol,label(symbol),gateUrl(symbol),5));
        result.add(check("TREASURY_NOMINAL","美国财政部名义收益率",treasuryUrl(false,clock.instant().atZone(ZoneId.of("America/New_York")).getYear()),3600));
        result.add(check("TREASURY_REAL","美国财政部实际收益率",treasuryUrl(true,clock.instant().atZone(ZoneId.of("America/New_York")).getYear()),3600));
        result.add(check("FF_EXPECTATIONS","Forex Factory 发布安排与提供方预期",CALENDAR,1800));return List.copyOf(result);}
    private SourceCheck check(String id,String label,String url,int interval){var c=checks.getOrDefault(id,new SourceCheck(id,label,url,"NOT_YET_FETCHED",interval,null,null,null,0));
        return enabled()?c:new SourceCheck(id,label,url,"DISABLED",interval,c.lastSuccess(),c.nextPoll(),c.error(),c.records());}
    @Scheduled(fixedDelay=1000,initialDelay=4200) void collect(){
        if(!enabled())return;
        for(String symbol:List.of("USIDX","EURUSD","USDJPY"))schedule("GATE_"+symbol,label(symbol),gateUrl(symbol),5,()->collectGate(symbol));
        int year=clock.instant().atZone(ZoneId.of("America/New_York")).getYear();
        schedule("TREASURY_NOMINAL","美国财政部名义收益率",treasuryUrl(false,year),3600,()->collectTreasury(false));
        schedule("TREASURY_REAL","美国财政部实际收益率",treasuryUrl(true,year),3600,()->collectTreasury(true));
        schedule("FF_EXPECTATIONS","Forex Factory 发布安排与提供方预期",CALENDAR,1800,this::collectExpectations);
    }
    @FunctionalInterface private interface Fetch{int run() throws Exception;}
    private void schedule(String id,String label,String url,int interval,Fetch action){
        var previous=checks.get(id);if(previous!=null&&previous.nextPoll()!=null&&previous.nextPoll().isAfter(clock.instant())||!working.add(id))return;
        Thread.ofVirtual().name("gold-driver-"+id).start(()->{try{
            int records=action.run();saveCheck(new SourceCheck(id,label,url,"CURRENT",interval,clock.instant(),clock.instant().plusSeconds(interval),null,records));
        }catch(Exception failed){String error=failed.getMessage();if(error==null||!error.matches("[A-Z0-9_]+"))error="DRIVER_FETCH_OR_PARSE_FAILED";
            int retry=Math.min(id.startsWith("GATE_")?120:3600,Math.max(15,previous!=null&&previous.error()!=null?previous.intervalSeconds()*2:60));
            saveCheck(new SourceCheck(id,label,url,"FETCH_FAILED",retry,previous==null?null:previous.lastSuccess(),clock.instant().plusSeconds(retry),error,0));
        }finally{working.remove(id);events.changed();}});
    }
    private void saveCheck(SourceCheck check){checks.put(check.id(),check);store.save("driver-source",check.id(),check);}
    private int collectGate(String symbol) throws Exception {
        Instant now=clock.instant();var previous=drivers.get("GATE_"+symbol);
        // Quote collection continues if a history refresh fails; the old baseline is explicitly gated.
        if(historyAt.get(symbol)==null||historyAt.get(symbol).plusSeconds(60).isBefore(now))try{
            histories.put(symbol,parseHistory(gate.driverObservation(symbol,true),now));historyAt.put(symbol,now);historyErrors.remove(symbol);
        }catch(Exception failure){historyErrors.put(symbol,"关联1分钟K线未刷新");historyAt.put(symbol,now.minusSeconds(45));}
        var body=gate.driverObservation(symbol,false);Instant receivedAt=clock.instant();
        var parsed=parseGate(symbol,body,receivedAt,previous==null?1:previous.version()+1,histories.getOrDefault(symbol,List.of()));
        if(historyErrors.containsKey(symbol))parsed=new Driver(parsed.id(),parsed.label(),parsed.family(),parsed.source(),parsed.nativeSymbol(),parsed.url(),parsed.frequency(),parsed.unit(),parsed.value(),parsed.previous(),parsed.change(),parsed.changeUnit(),parsed.asOfDate(),parsed.responseAt(),parsed.receivedAt(),parsed.version(),parsed.marketStatus(),parsed.returnsPct(),parsed.referenceChangePct(),parsed.timestampMeaning(),parsed.note()+"；"+historyErrors.get(symbol));
        saveDriver(parsed);return 1;
    }
    static Driver parseGate(String symbol,JsonNode body,Instant receivedAt,int version,List<Price> history){
        var data=body.path("data");long timestamp=body.path("timestamp").asLong();if(!body.path("label").asText().isBlank()||timestamp<=0)throw new IllegalStateException("GATE_DRIVER_RESPONSE_INVALID");
        var responseAt=Instant.ofEpochMilli(timestamp);if(responseAt.isAfter(receivedAt.plusSeconds(2))||responseAt.isBefore(receivedAt.minusSeconds(10)))throw new IllegalStateException("GATE_DRIVER_RESPONSE_STALE");
        BigDecimal bid=number(data.path("bid_price")),ask=number(data.path("ask_price"));if(bid.signum()<=0||ask.compareTo(bid)<0)throw new IllegalStateException("GATE_DRIVER_PRICE_INVALID");
        var mid=bid.add(ask,MC).divide(BigDecimal.valueOf(2),MC);var returns=new LinkedHashMap<String,String>();
        for(int minutes:List.of(5,15,60)) {
            var before=history.stream().filter(p->!p.at().isAfter(responseAt.minusSeconds(minutes*60L))).max(Comparator.comparing(Price::at)).orElse(null);
            if(before!=null&&Duration.between(before.at(),responseAt.minusSeconds(minutes*60L)).toSeconds()<=120)
                returns.put(Integer.toString(minutes),pct(mid,before.close()));
        }
        String ref=null;try{var close=number(data.path("last_today_close_price"));if(close.signum()>0)ref=pct(mid,close);}catch(RuntimeException ignored){}
        return new Driver("GATE_"+symbol,label(symbol),"USD", "GATE_PUBLIC_CFD",symbol,gateUrl(symbol),"POLL_5_SECONDS",symbol.equals("USIDX")?"INDEX_POINTS":"FX_RATE",plain(mid),null,null,null,null,responseAt,receivedAt,version,data.path("status").asText("unknown"),Map.copyOf(returns),ref,
            "SERVER_RESPONSE_TIME; LAST_TICK_TIME_NOT_SUPPLIED",(symbol.equals("USIDX")?"Gate 美元指数 CFD，基准构成尚未独立核实；不标为 ICE USDX。":"Gate 外汇 CFD 报价，最后 tick 时刻未提供。")+"变化以目标时刻之前的已收盘1分钟价为基线，偏移不超过2分钟。");
    }
    static List<Price> parseHistory(JsonNode body,Instant cutoff){
        var prices=new TreeMap<Instant,Price>();if(!body.path("label").asText().isBlank()||!body.path("data").path("list").isArray())throw new IllegalStateException("DRIVER_HISTORY_INVALID");
        for(var row:body.path("data").path("list")){
            long time=row.path("t").asLong();var end=Instant.ofEpochSecond(time).plusSeconds(60);if(time<=0)throw new IllegalStateException("DRIVER_HISTORY_INVALID");if(end.isAfter(cutoff))continue;
            BigDecimal close=number(row.path("c"));if(close.signum()<=0)throw new IllegalStateException("DRIVER_HISTORY_INVALID");
            var old=prices.putIfAbsent(end,new Price(end,close));if(old!=null&&old.close().compareTo(close)!=0)throw new IllegalStateException("DRIVER_HISTORY_CONFLICT");
        }
        if(prices.isEmpty())throw new IllegalStateException("DRIVER_HISTORY_EMPTY");return List.copyOf(prices.values());
    }
    private int collectTreasury(boolean real) throws Exception {
        LocalDate today=clock.instant().atZone(ZoneId.of("America/New_York")).toLocalDate();String url=treasuryUrl(real,today.getYear());
        var rows=treasuryRows(fetch(url,1_000_000),real,today);
        if(today.getDayOfYear()<15)rows.putAll(treasuryRows(fetch(treasuryUrl(real,today.getYear()-1),1_000_000),real,today));
        var observations=treasuryDrivers(rows,real,url,clock.instant());if(observations.isEmpty())throw new IllegalStateException("TREASURY_NO_VALID_DATA");
        for(var d:observations){var old=drivers.get(d.id());if(old!=null&&Objects.equals(old.value(),d.value())&&Objects.equals(old.previous(),d.previous())&&Objects.equals(old.asOfDate(),d.asOfDate()))continue;
            String observationUrl=treasuryUrl(real,LocalDate.parse(d.asOfDate()).getYear());
            saveDriver(new Driver(d.id(),d.label(),d.family(),d.source(),d.nativeSymbol(),observationUrl,d.frequency(),d.unit(),d.value(),d.previous(),d.change(),d.changeUnit(),d.asOfDate(),null,d.receivedAt(),old==null?1:old.version()+1,d.marketStatus(),d.returnsPct(),null,d.timestampMeaning(),d.note()));}
        return observations.size();
    }
    static SortedMap<LocalDate,Map<String,BigDecimal>> treasuryRows(byte[] bytes,boolean real,LocalDate today) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));var result=new TreeMap<LocalDate,Map<String,BigDecimal>>();
        var entries=document.getElementsByTagNameNS("*","entry");for(int i=0;i<entries.getLength();i++){
            var entry=(Element)entries.item(i);String date=xml(entry,"NEW_DATE");if(date.length()<10)continue;LocalDate day=LocalDate.parse(date.substring(0,10));if(day.isAfter(today))continue;
            var values=new LinkedHashMap<String,BigDecimal>();for(String field:real?List.of("TC_10YEAR"):List.of("BC_2YEAR","BC_10YEAR")){
                String value=xml(entry,field);if(!value.matches("-?\\d{1,2}(?:\\.\\d{1,6})?"))continue;values.put(field,new BigDecimal(value));}
            if(!values.isEmpty())result.put(day,Map.copyOf(values));
        }return result;
    }
    static List<Driver> treasuryDrivers(SortedMap<LocalDate,Map<String,BigDecimal>> rows,boolean real,String url,Instant receivedAt){
        List<Driver> result=new ArrayList<>();for(String field:real?List.of("TC_10YEAR"):List.of("BC_2YEAR","BC_10YEAR")){
            var valid=rows.entrySet().stream().filter(e->e.getValue().containsKey(field)).toList();if(valid.isEmpty())continue;var last=valid.getLast();var value=last.getValue().get(field);
            BigDecimal previous=valid.size()>1?valid.get(valid.size()-2).getValue().get(field):null;
            String id=real?"US_REAL_10Y":field.equals("BC_2YEAR")?"US_NOMINAL_2Y":"US_NOMINAL_10Y";
            result.add(new Driver(id,real?"美国10年实际收益率":field.equals("BC_2YEAR")?"美国2年名义收益率":"美国10年名义收益率","RATES","US_TREASURY",field,url,"DAILY","PERCENT",plain(value),previous==null?null:plain(previous),previous==null?null:plain(value.subtract(previous,MC).multiply(BigDecimal.valueOf(100),MC)),"BASIS_POINTS",last.getKey().toString(),null,receivedAt,1,"BACKGROUND",Map.of(),null,"AS_OF_DATE_ONLY; KNOWN_AT_RECEIPT","官方日频曲线；与上一可用统计日比较，不代表盘中实时收益率。"));
        }return List.copyOf(result);
    }
    private static String xml(Element entry,String name){var nodes=entry.getElementsByTagNameNS("*",name);return nodes.getLength()==0?"":nodes.item(0).getTextContent().strip();}
    private int collectExpectations() throws Exception {return ingestExpectations(mapper.readTree(fetch(CALENDAR,600_000)));}
    int ingestExpectations(JsonNode rows){
        if(!rows.isArray())throw new IllegalStateException("CALENDAR_EXPORT_INVALID");int count=0;
        for(var row:rows){if(!row.path("country").asText().equals("USD"))continue;String title=row.path("title").asText();if(title.isBlank()||title.length()>240)continue;
            String mapped=EconomicExtractor.indicator(title),indicator=mapped==null?"UNMAPPED:"+NewsService.hash(title).substring(0,16):mapped;
            Instant scheduled;try{scheduled=OffsetDateTime.parse(row.path("date").asText()).toInstant();}catch(RuntimeException unknown){continue;}
            String rawForecast=row.path("forecast").asText(),rawPrevious=row.path("previous").asText();if(rawForecast.length()>80||rawPrevious.length()>80)continue;
            String unit=mapped==null?"UNMAPPED":EconomicExtractor.unit(indicator),forecast=mapped==null?null:EconomicExtractor.forecast(rawForecast,unit),previous=mapped==null?null:EconomicExtractor.forecast(rawPrevious,unit);
            String id=NewsService.hash(title+":"+scheduled),hash=NewsService.hash(title+"|"+scheduled+"|"+rawForecast+"|"+rawPrevious+"|"+row.path("impact").asText());
            var old=store.find("gold-expectation",id,Expectation.class);if(old.isPresent()&&old.get().hash().equals(hash)){count++;continue;}
            var expectation=new Expectation(id,indicator,title,scheduled,"PROVIDER_TIME_APPROXIMATE",row.path("impact").asText(),unit,forecast,previous,"FOREX_FACTORY_PROVIDER_FORECAST",CALENDAR,clock.instant(),old.map(e->e.version()+1).orElse(1),hash,rawForecast,rawPrevious);
            store.transaction(()->{store.save("gold-expectation",id,expectation);store.save("gold-expectation-version",id+":"+expectation.version(),expectation);return expectation;});count++;
        }
        // Unsupported USD events remain visible, but are excluded from numerical surprise joins.
        return count;
    }
    public List<Expectation> expectations(Instant cutoff){
        var selected=new LinkedHashMap<String,Expectation>();store.list("gold-expectation-version",Expectation.class,2000).stream()
            .filter(e->!e.receivedAt().isAfter(cutoff)&&e.scheduledAt().isAfter(cutoff.minusSeconds(172800))&&e.scheduledAt().isBefore(cutoff.plusSeconds(604800)))
            .sorted(Comparator.comparing(Expectation::receivedAt).thenComparingInt(Expectation::version).reversed()).forEach(e->selected.putIfAbsent(e.id(),e));
        return selected.values().stream().sorted(Comparator.comparing(Expectation::scheduledAt)).toList();
    }
    public Expectation preRelease(String indicator,String unit,Instant publishedAt){
        var rows=store.list("gold-expectation-version",Expectation.class,2000).stream().filter(e->e.indicator().equals(indicator)&&e.unit().equals(unit)&&e.forecast()!=null
            &&e.receivedAt().isBefore(publishedAt)&&Math.abs(Duration.between(e.scheduledAt(),publishedAt).toSeconds())<=300).toList();
        if(rows.stream().map(Expectation::id).distinct().count()!=1)return null;
        return rows.stream().max(Comparator.comparing(Expectation::receivedAt)).orElse(null);
    }
    private byte[] fetch(String url,int maximum) throws Exception {var response=http.send(BrowserSourceRequest.navigation(URI.create(url),Duration.ofSeconds(12)).GET().build(),maximum);
        if(response.statusCode()!=200)throw new IllegalStateException("DRIVER_HTTP_"+response.statusCode());return response.body();}
    private void saveDriver(Driver driver){drivers.put(driver.id(),driver);store.save("gold-driver",driver.id(),driver);}
    private static String treasuryUrl(boolean real,int year){return TREASURY+"?data="+(real?"daily_treasury_real_yield_curve":"daily_treasury_yield_curve")+"&field_tdr_date_value="+year;}
    private static String gateUrl(String symbol){return "https://api.gateio.ws/api/v4/tradfi/symbols/"+symbol+"/tickers";}
    private static String label(String symbol){return switch(symbol){case "USIDX"->"Gate 美元指数 CFD";case "EURUSD"->"欧元／美元";default->"美元／日元";};}
    private static BigDecimal number(JsonNode value){if(!value.isTextual()||!value.asText().matches("[0-9]{1,12}(?:\\.[0-9]{1,12})?"))throw new IllegalStateException("DRIVER_NUMBER_INVALID");return new BigDecimal(value.asText());}
    static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static String pct(BigDecimal value,BigDecimal before){return plain(value.subtract(before,MC).divide(before,MC).multiply(BigDecimal.valueOf(100),MC));}
}
