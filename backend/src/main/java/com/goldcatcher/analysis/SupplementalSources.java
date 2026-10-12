package com.goldcatcher.analysis;

import static com.goldcatcher.analysis.AnalysisTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.network.BrowserSourceRequest;
import com.goldcatcher.network.SourcePolling;
import com.goldcatcher.network.SourceReliability;
import com.goldcatcher.network.SourceProxyPool;
import jakarta.annotation.PostConstruct;
import com.goldcatcher.persistence.DocumentStore;
import java.io.ByteArrayInputStream;
import java.math.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.*;

/** Public observations; provenance and limits remain explicit, without changing simulation fills. */
@Service
public class SupplementalSources {
    public record Observation(String id,String source,String label,String period,String frequency,String unit,
        Map<String,String> values,Instant publishedAt,Instant receivedAt,String url,String bodyHash,String note) {}
    public record Depth(String symbol,String sequence,Instant responseAt,Instant updatedAt,Instant receivedAt,
        String quantityMultiplier,List<Map<String,String>> bids,List<Map<String,String>> asks,String imbalance,String coverage) {}
    public record Trades(String symbol,Instant receivedAt,Instant firstAt,Instant lastAt,int count,String quantityMultiplier,
        String volume,String vwap,List<Map<String,String>> rows,String coverage) {}
    public record Raw(String source,String url,String hash,Instant receivedAt,String format,String body) {}
    public record Coverage(String id,String label,String state,String detail,String url) {}
    public record View(List<Observation> observations,Depth depth,Trades trades,List<SourceCheck> sources,List<String> gaps,List<Coverage> coverage) {}
    static final Map<String,String> URLS=Map.of(
        "DOL_CLAIMS","https://www.dol.gov/ui/data.pdf",
        "CENSUS_ECON","https://www.census.gov/economic-indicators/indicator.xml",
        "CFTC_GOLD","https://publicreporting.cftc.gov/resource/72hh-3qpy.json?%24where=cftc_contract_market_code%3D%27088691%27&%24order=report_date_as_yyyy_mm_dd%20DESC&%24limit=2",
        "TREASURY_AUCTIONS","https://api.fiscaldata.treasury.gov/services/api/fiscal_service/v1/accounting/od/auctions_query?sort=-auction_date&page%5Bsize%5D=40",
        "IAU_HOLDINGS","https://www.ishares.com/us/products/239561/ishares-gold-trust-fund",
        "GLD_HOLDINGS","https://api.spdrgoldshares.com/api/v1/historical-archive?product=gld&exchange=NYSE&lang=en",
        "GATE_DEPTH","https://api.gateio.ws/api/v4/futures/usdt/order_book?contract=XAU_USDT&limit=20&with_id=true",
        "GATE_TRADES","https://api.gateio.ws/api/v4/futures/usdt/trades?contract=XAU_USDT&limit=100");
    private static final Map<String,String> LABELS=Map.of("DOL_CLAIMS","初请与续请失业金","CENSUS_ECON","零售、住房与贸易官方摘要","CFTC_GOLD","CFTC 黄金期货持仓","TREASURY_AUCTIONS","美国国债拍卖","IAU_HOLDINGS","IAU 发行方黄金持仓","GLD_HOLDINGS","GLD 发行方黄金持仓","GATE_DEPTH","黄金永续20档盘口","GATE_TRADES","黄金永续近期逐笔样本");
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;private final DocumentStore store;
    private final MarketFeed market;private final Clock clock;private final EventHub events;
    private final SourceProxyPool proxy;private final SourceReliability reliability;
    private final Map<String,SourceCheck> checks=new ConcurrentHashMap<>();private final Set<String> busy=ConcurrentHashMap.newKeySet();
    public SupplementalSources(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,DocumentStore store,MarketFeed market,Clock clock,EventHub events){
        this(settings,http,mapper,store,market,clock,events,null,new SourceReliability(clock));
    }
    @Autowired public SupplementalSources(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,DocumentStore store,MarketFeed market,Clock clock,EventHub events,SourceProxyPool proxy,SourceReliability reliability){
        this.settings=settings;this.http=http;this.mapper=mapper;this.store=store;this.market=market;this.clock=clock;this.events=events;this.proxy=proxy;this.reliability=reliability;
    }
    @PostConstruct void recover(){store.list("public-source-check",SourceCheck.class,20).stream().filter(c->Objects.equals(URLS.get(c.id()),c.url())).forEach(c->checks.put(c.id(),new SourceCheck(c.id(),c.label(),c.url(),c.state(),interval(c.id()),c.lastSuccess(),c.error()!=null&&c.nextPoll()!=null&&c.nextPoll().isAfter(clock.instant())?c.nextPoll():clock.instant(),c.error(),c.records())));}
    boolean enabled(){return Boolean.parseBoolean(settings.text("GOLD_SUPPLEMENTAL_ENABLED",Boolean.toString(settings.enabled("GOLD_NEWS_ENABLED")||settings.enabled("GOLD_MARKET_ENABLED"))));}
    public int interval(String id){
        var local=clock.instant().atZone(ZoneId.of("America/New_York"));int minute=local.getHour()*60+local.getMinute();
        if(id.startsWith("GATE_"))return 2;
        if(Set.of("IAU_HOLDINGS","GLD_HOLDINGS").contains(id))return 3600;
        if(id.equals("DOL_CLAIMS"))return local.getDayOfWeek().getValue()<=5&&minute>=480&&minute<=570?15:900;
        if(id.equals("CFTC_GOLD"))return local.getDayOfWeek()==DayOfWeek.FRIDAY&&minute>=900&&minute<=990?30:900;
        if(id.equals("TREASURY_AUCTIONS"))return local.getDayOfWeek().getValue()<=5&&(minute>=680&&minute<=710||minute>=770&&minute<=800)?15:300;
        return 60;
    }
    public View view(Instant cutoff){
        Map<String,Observation> known=new LinkedHashMap<>();
        store.list("public-observation-version",Observation.class,1000).stream().filter(o->!o.receivedAt().isAfter(cutoff)&&!o.receivedAt().isBefore(cutoff.minusSeconds(7776000)))
            .sorted(Comparator.comparing(Observation::receivedAt).reversed()).forEach(o->known.putIfAbsent(o.id(),o));
        var observations=List.copyOf(known.values());
        var sources=URLS.keySet().stream().sorted().map(id->{var c=checks.getOrDefault(id,new SourceCheck(id,LABELS.get(id),URLS.get(id),"NOT_YET_FETCHED",interval(id),null,null,null,0));return enabled()?c:new SourceCheck(c.id(),c.label(),c.url(),"DISABLED",interval(id),c.lastSuccess(),c.nextPoll(),c.error(),c.records());}).toList();
        var depth=store.find("public-depth","XAU_USDT",Depth.class).filter(d->!d.receivedAt().isAfter(cutoff)).orElse(null);
        var trades=store.find("public-trades","XAU_USDT",Trades.class).filter(t->!t.receivedAt().isAfter(cutoff)).orElse(null);
        return new View(observations,depth,trades,sources,List.of("盘口为20档REST快照；逐笔为近期100条样本，完整连续性尚未验证；CFD没有L2/逐笔接口", "CFTC是周度统计背景；拍卖收益率不是盘中现金国债收益率；IAU持仓不是盘中申赎资金流", "补充视图最多读取最近1000个版本、90天知识时刻范围；每次研究的完整证据单独封存"),coverage());
    }
    static List<Coverage> coverage(){return List.of(
        new Coverage("US_RATES_INTRADAY","分钟级美债收益率与实际利率","PARTIAL","CNBC公开2年/10年名义和10年TIPS报价已接入，60秒检查、保留真实报价时间及Java bp计算；公开网页无接口SLA，开市连续性/延迟尚未验证。当前时效看趋势因子卡片，不能用轮询时间替代报价时间。","https://www.cnbc.com/quotes/US10YTIPS"),
        new Coverage("CME_RATES_OPTIONS","利率期货、FedWatch 与黄金期权","NOT_CONNECTED","交易所实时行情、隐含波动率与会议概率尚无已验证的数据通道。","https://www.cmegroup.com/market-data/real-time-futures-and-options-data-api.html"),
        new Coverage("COMMERCIAL_FLASH","商业快讯与突发报道","PARTIAL","CNBC头条/经济/全球公开RSS已接入，每30秒检查出版方标题和摘要，同一链接合并；不抓付费正文。金十MCP、Reuters/Bloomberg低延迟授权新闻线未配置，公开RSS不能视为完整实时新闻线。","https://www.cnbc.com/id/100003114/device/rss/rss.html"),
        new Coverage("CONSENSUS","经济数据预期与发布前历史","PARTIAL","公开采集 Forex Factory 提供方预期及 Trading Economics 网页调查一致预期；TE自有预测另存，不混用。当前传输状态见来源检查；无授权推送和历史发布前全量档案，不回填预期。Investing 日历直连返回403；Bloomberg 授权经济数据未配置。","https://tradingeconomics.com/calendar"),
        new Coverage("MICROSTRUCTURE","完整盘口、逐笔连续性与 CFD 明细","PARTIAL","黄金永续有20档快照/近期100条；未实现全量增量簿及连续成交重放。CFD缺L2、逐笔、可见数量及已核实隔夜费。","https://www.gate.com/docs/developers/futures/ws/en/"),
        new Coverage("GOLD_SUPPLY","GLD、全球ETF、央行储备、库存与亚洲市场","PARTIAL","IAU与GLD发行方日频持仓已实现；不是盘中申赎流。WGC、SAFE、CME库存、LBMA、上金所仍为待接入。","https://www.spdrgoldshares.com/usa/gld/"),
        new Coverage("PRIVATE_SURVEYS","ISM、PMI、ADP 与地区央行","PARTIAL","联储Board、ECB、BoE新闻/讲话、BoJ发布已接RSS；私营调查、所有地区联储、完整政策日历和现场讲话流尚待实现。","https://www.ismworld.org/supply-management-news-and-reports/reports/ism-report-on-business/"),
        new Coverage("NEWS_POLICY_ENERGY","能源、地缘、平台公告与相关资产","PARTIAL","EIA新闻/能源摘要/产品更新已接RSS；结构化库存、OPEC、外交政策、Gate公告、社交流、股指/油/银独立通道尚待实现。详见45项信源目录。","https://www.eia.gov/petroleum/supply/weekly/"));}
    @Scheduled(fixedDelay=1000,initialDelay=8000) void collect(){
        if(!enabled())return;for(var id:URLS.keySet()){
            var old=checks.get(id);
            if(old!=null&&!SourcePolling.due(clock.instant(),old.lastSuccess(),old.nextPoll(),old.error(),old.intervalSeconds(),interval(id))||!busy.add(id))continue;
            Thread.ofVirtual().name("gold-public-"+id).start(()->{long started=reliability.begin();try{
                int count=fetch(id);saveCheck(new SourceCheck(id,LABELS.get(id),URLS.get(id),"CURRENT",interval(id),clock.instant(),clock.instant().plusSeconds(interval(id)),null,count));
                reliability.success(id,"COLLECTION",started,count);
            }catch(Exception failed){String error=failed.getMessage();if(error==null||!error.matches("[A-Z0-9_]+"))error="PUBLIC_FETCH_OR_PARSE_FAILED";
                reliability.failure(id,"COLLECTION",started,error);
                int retry=SourcePolling.retrySeconds(failed,clock.instant(),SourcePolling.backoff(reliability.failures(id),id.startsWith("GATE_")?15:60,id.startsWith("GATE_")?120:3600));
                saveCheck(new SourceCheck(id,LABELS.get(id),URLS.get(id),"FETCH_FAILED",interval(id),old==null?null:old.lastSuccess(),clock.instant().plusSeconds(retry),error,0));
            }finally{busy.remove(id);events.changed();}});
        }
    }
    private void saveCheck(SourceCheck check){checks.put(check.id(),check);store.save("public-source-check",check.id(),check);}
    private int fetch(String id) throws Exception {
        var request=BrowserSourceRequest.navigation(URI.create(URLS.get(id)),Duration.ofSeconds(15)).GET().build();
        var response=proxy==null||id.startsWith("GATE_")?http.send(request,4000000):proxy.send(id,request,4000000);
        if(response.statusCode()!=200)throw SourcePolling.failure("PUBLIC_HTTP_",response,clock.instant());
        Instant received=clock.instant();byte[] bytes=response.body();String text;
        if(id.equals("DOL_CLAIMS")){if(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalStateException("CLAIMS_NOT_PDF");
            try(var pdf=Loader.loadPDF(bytes)){if(pdf.getNumberOfPages()>30||!pdf.getCurrentAccessPermission().canExtractContent())throw new IllegalStateException("CLAIMS_PDF_UNSUPPORTED");text=new PDFTextStripper().getText(pdf);}
        }else text=id.equals("GLD_HOLDINGS")?"":new String(bytes,StandardCharsets.UTF_8);
        boolean binary=Set.of("DOL_CLAIMS","GLD_HOLDINGS").contains(id);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));String body=binary?Base64.getEncoder().encodeToString(bytes):text;
        var raw=new Raw(id,URLS.get(id),hash,received,id.equals("DOL_CLAIMS")?"PDF_BASE64":id.equals("GLD_HOLDINGS")?"XLSX_BASE64":"UTF8",body);
        // High-frequency market responses retain the latest sample; macro documents retain immutable versions.
        if(!id.startsWith("GATE_")&&store.find("public-raw-version",id+":"+hash,Raw.class).isEmpty())store.save("public-raw-version",id+":"+hash,raw);
        store.save("public-raw",id,raw);
        List<Observation> rows;
        switch(id){
            case "DOL_CLAIMS" -> rows=parseClaims(text,received,hash);
            case "CENSUS_ECON" -> rows=parseCensus(bytes,received,hash);
            case "CFTC_GOLD" -> rows=parseCot(mapper.readTree(text),received,hash);
            case "TREASURY_AUCTIONS" -> rows=parseAuctions(mapper.readTree(text),received,hash);
            case "IAU_HOLDINGS" -> rows=parseIau(text,received,hash);
            case "GLD_HOLDINGS" -> rows=GldArchive.parse(bytes,received,hash,URLS.get(id));
            case "GATE_DEPTH" -> {var rules=market.instrument();if(rules==null)throw new IllegalStateException("QUANTITY_MULTIPLIER_NOT_READY");var depth=parseDepth(mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text),rules.quantityMultiplier(),received);store.save("public-depth","XAU_USDT",depth);reliability.arrived(id,"depth:"+depth.sequence(),depth.updatedAt(),received,"ORDER_BOOK_UPDATE_TIME");return 1;}
            case "GATE_TRADES" -> {var rules=market.instrument();if(rules==null)throw new IllegalStateException("QUANTITY_MULTIPLIER_NOT_READY");var trades=parseTrades(mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text),rules.quantityMultiplier(),received);store.save("public-trades","XAU_USDT",trades);reliability.arrived(id,"trades:"+trades.lastAt(),trades.lastAt(),received,"RECENT_TRADES_SAMPLE_NOT_CONTINUOUS");return 1;}
            default -> throw new IllegalStateException("PUBLIC_SOURCE_UNKNOWN");
        }
        if(rows.isEmpty())throw new IllegalStateException("PUBLIC_NO_VERIFIED_RECORDS");
        saveObservations(rows);
        return rows.size();
    }
    static List<Observation> parseIau(String html,Instant at,String hash){
        var doc=Jsoup.parse(html);if(doc.select("[data-id='keyFundFacts-row-ounces']").size()!=1)throw new IllegalStateException("IAU_PAGE_SCHEMA_UNKNOWN");
        Map<String,Map<String,String>> periods=new TreeMap<>();var labels=Map.of("ounces","Ounces in Trust","tonnes","Tonnes in Trust","sharesOutstanding","Shares Outstanding","totalNetAssetsFundLevel","Net Assets of Fund");
        for(String field:List.of("ounces","tonnes","sharesOutstanding","totalNetAssetsFundLevel")){
            var rows=doc.select("[data-id='keyFundFacts-row-"+field+"']");if(rows.size()!=1)throw new IllegalStateException("IAU_FIELD_MISSING");var row=rows.first();
            var label=row.select("[data-id='keyFundFacts-"+field+"-label']");var value=row.select("[data-id='keyFundFacts-"+field+"-data']");var date=row.select("[data-id='keyFundFacts-"+field+"-asOf']");
            if(label.size()!=1||value.size()!=1||date.size()!=1||!label.text().equals(labels.get(field)))throw new IllegalStateException("IAU_FIELD_SCHEMA_UNKNOWN");
            String n=value.text().replace(",","").replace("$","");if(!n.matches("[0-9]{1,18}(?:\\.[0-9]{1,12})?")||new BigDecimal(n).signum()<=0)throw new IllegalStateException("IAU_NUMBER_INVALID");
            LocalDate period=LocalDate.parse(date.text().replaceFirst("^as of\\s+",""),DateTimeFormatter.ofPattern("MMM dd, uuuu",Locale.US));if(period.isAfter(at.atZone(ZoneId.of("America/New_York")).toLocalDate()))throw new IllegalStateException("IAU_ASOF_FUTURE");
            periods.computeIfAbsent(period.toString(),p->new LinkedHashMap<>()).put(field,n);
        }
        return periods.entrySet().stream().map(e->observation("IAU:"+e.getKey(),"IAU_HOLDINGS","IAU 黄金信托发行方持仓",e.getKey(),"DAILY","FIELD_SPECIFIC",e.getValue(),null,at,hash,"ounces为金衡盎司，tonnes为公吨，sharesOutstanding为份额，totalNetAssetsFundLevel为USD；每个字段按发行方as-of分组。交易日口径与会计金条清单可能不同；不是盘中申赎流，无精确公布时刻。")).toList();
    }
    void saveObservations(List<Observation> rows){
        for(var row:rows){String key=NewsService.hash(row.id()+":"+mapper.writeValueAsString(List.of(new TreeMap<>(row.values()),row.period(),Objects.toString(row.publishedAt(),""),row.note())));
            var previous=store.find("public-observation-version",key,Observation.class);var saved=previous.orElse(row);
            if(previous.isEmpty()){store.save("public-observation-version",key,row);reliability.arrived(row.source(),key,row.publishedAt(),row.receivedAt(),row.publishedAt()!=null?"DECLARED_RELEASE_TIME":"PUBLICATION_UNVERIFIED");}store.save("public-observation",row.id(),saved);}
    }
    static List<Observation> parseCot(JsonNode json,Instant at,String hash){
        List<Observation> result=new ArrayList<>();if(!json.isArray())throw new IllegalStateException("COT_FORMAT_INVALID");
        for(var row:json){if(!row.path("cftc_contract_market_code").asText().equals("088691"))throw new IllegalStateException("COT_WRONG_PRODUCT");
            String date=row.path("report_date_as_yyyy_mm_dd").asText().substring(0,10);if(LocalDate.parse(date).isAfter(at.atZone(ZoneOffset.UTC).toLocalDate()))continue;
            var values=new LinkedHashMap<String,String>();for(String key:List.of("open_interest_all","m_money_positions_long_all","m_money_positions_short_all","m_money_positions_spread","prod_merc_positions_long","prod_merc_positions_short"))values.put(key,num(row.path(key)));
            values.put("managedNet",plain(new BigDecimal(values.get("m_money_positions_long_all")).subtract(new BigDecimal(values.get("m_money_positions_short_all")))));
            result.add(observation("COT:"+date,"CFTC_GOLD","黄金期货分类持仓（Futures Only）",date,"WEEKLY","CONTRACTS",values,null,at,hash,"周度快照，不代表今天仓位；实际公布时刻未提供，以首次取得为知识时刻。"));
        }return result;
    }
    static List<Observation> parseAuctions(JsonNode json,Instant at,String hash){
        List<Observation> result=new ArrayList<>();if(!json.path("data").isArray())throw new IllegalStateException("AUCTION_FORMAT_INVALID");
        for(var row:json.path("data")){String announced=row.path("announcemt_date").asText(),date=row.path("auction_date").asText();
            if(LocalDate.parse(announced).isAfter(at.atZone(ZoneId.of("America/New_York")).toLocalDate()))continue;
            Map<String,String> values=new LinkedHashMap<>();for(String key:List.of("high_yield","high_investment_rate","bid_to_cover_ratio","offering_amt","indirect_bidder_accepted","total_accepted")){String v=nullable(row.path(key));if(v!=null)values.put(key,v);}
            String close=row.path("closing_time_comp").asText();Instant scheduled=null;if(!close.isBlank()&&!close.equals("null"))scheduled=LocalDateTime.of(LocalDate.parse(date),LocalTime.parse(close,DateTimeFormatter.ofPattern("hh:mm a",Locale.US))).atZone(ZoneId.of("America/New_York")).toInstant();
            boolean future=scheduled!=null&&scheduled.isAfter(at);if(future){values.keySet().removeIf(k->!k.equals("offering_amt"));}
            if(scheduled!=null)values.put("competitiveCloseUtc",scheduled.toString());
            result.add(observation("auction:"+row.path("cusip").asText()+":"+date,"TREASURY_AUCTIONS",row.path("security_type").asText()+" · "+row.path("security_term").asText(),date,"AUCTION_EVENT","FIELD_SPECIFIC",values,null,at,hash,(future?"已公告的未来安排，尚无结果。":"已取得拍卖记录，结果未给出精确公布时刻。")+"yield/rate为%，cover为倍数，金额为USD；不是盘中收益率。"));
        }return result;
    }
    static List<Observation> parseCensus(byte[] bytes,Instant at,String hash) throws Exception {
        var f=DocumentBuilderFactory.newInstance();f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");f.setExpandEntityReferences(false);
        var doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));var items=doc.getElementsByTagName("item");List<Observation> result=new ArrayList<>();
        for(int i=0;i<items.getLength();i++){var item=(org.w3c.dom.Element)items.item(i);String title=xml(item,"title"),text=Jsoup.parseBodyFragment(xml(item,"description")).text(),link=xml(item,"link");
            if(!link.startsWith("https://www.census.gov/"))continue;Instant pub=ZonedDateTime.parse(xml(item,"pubDate"),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();if(pub.isAfter(at))continue;
            var change=Pattern.compile("(January|February|March|April|May|June|July|August|September|October|November|December) (20[0-9]{2}): ([+-]?[0-9]+(?:\\.[0-9]+)?)[*°]? ?%",Pattern.CASE_INSENSITIVE).matcher(text);
            Map<String,String> values=new LinkedHashMap<>();String period="UNKNOWN";if(change.find()){period=YearMonth.of(Integer.parseInt(change.group(2)),Month.from(DateTimeFormatter.ofPattern("MMMM",Locale.US).parse(change.group(1))).getValue()).toString();values.put("reportedChangePct",change.group(3));if(change.find())values.put("previousReportedChangePct",change.group(3));}
            var base=observation("census:"+NewsService.hash(title),"CENSUS_ECON",title,period,"MONTHLY_OR_QUARTERLY","PERCENT_REPORTED_CHANGE",values,pub,at,hash,"官方RSS摘要；变化具体口径以标题/原文为准，不把库存或季度变化当零售月环比。 "+text);
            result.add(new Observation(base.id(),base.source(),base.label(),base.period(),base.frequency(),base.unit(),base.values(),base.publishedAt(),base.receivedAt(),link,hash,base.note()));
        }return result;
    }
    static List<Observation> parseClaims(String text,Instant at,String hash){
        String normalized=text.replaceAll("\\s+"," ");var stamp=Pattern.compile("8:30 A\\.M\\. \\(Eastern\\) (?:Monday|Tuesday|Wednesday|Thursday|Friday), ([A-Za-z]+ [0-9]{1,2}, 20[0-9]{2})").matcher(normalized);if(!stamp.find())throw new IllegalStateException("CLAIMS_PUBLICATION_UNKNOWN");
        var day=LocalDate.parse(stamp.group(1),DateTimeFormatter.ofPattern("MMMM d, uuuu",Locale.US));Instant pub=day.atTime(8,30).atZone(ZoneId.of("America/New_York")).toInstant();if(pub.isAfter(at))throw new IllegalStateException("CLAIMS_EMBARGOED");
        int unadjusted=normalized.indexOf("UNADJUSTED DATA");if(unadjusted>=0)normalized=normalized.substring(0,unadjusted);
        List<Observation> result=new ArrayList<>();
        int split=normalized.indexOf("The advance number for seasonally adjusted insured unemployment");
        String initialPart=split<0?normalized:normalized.substring(0,split),continuedPart=split<0?"":normalized.substring(split);
        extractClaim(result,"INITIAL_CLAIMS","初请失业金（季调）",initialPart,"week ending ([A-Za-z]+ [0-9]{1,2}), the advance figure for seasonally adjusted initial claims was ([0-9,]+)",day,pub,at,hash);
        extractClaim(result,"CONTINUED_CLAIMS","续请失业金（季调）",continuedPart,"insured unemployment during the week ending ([A-Za-z]+ [0-9]{1,2}) was ([0-9,]+)",day,pub,at,hash);
        return result;
    }
    private static void extractClaim(List<Observation> result,String id,String label,String part,String expression,LocalDate day,Instant pub,Instant at,String hash){
        var actual=Pattern.compile(expression).matcher(part);if(!actual.find())return;
        String tail=part.substring(actual.end());int average=tail.indexOf("4-week");if(average>=0)tail=tail.substring(0,average);
        var previous=Pattern.compile("previous week's level was (?:revised (?:up|down) by [0-9,]+ from ([0-9,]+) to ([0-9,]+)|unrevised at ([0-9,]+))\\.").matcher(tail);
        if(previous.find()){String before=previous.group(3)==null?previous.group(1):previous.group(3),after=previous.group(3)==null?previous.group(2):previous.group(3);
            result.add(claim(id,label,actual.group(1),actual.group(2),before,after,day,pub,at,hash));}
    }
    private static Observation claim(String id,String label,String week,String actual,String before,String after,LocalDate publication,Instant pub,Instant at,String hash){
        var date=LocalDate.parse(week+" "+publication.getYear(),DateTimeFormatter.ofPattern("MMMM d uuuu",Locale.US));if(date.isAfter(publication))date=date.minusYears(1);
        return observation(id,"DOL_CLAIMS",label,date.toString(),"WEEKLY","COUNT",Map.of("actual",actual.replace(",",""),"previousBeforeRevision",before.replace(",",""),"previous",after.replace(",","")),pub,at,hash,"原文前值修订保留；历史发布前预期未取得，不回填超预期差值。未支持的原文模板保持未知。");
    }
    static Depth parseDepth(JsonNode json,BigDecimal multiplier,Instant at){
        if(multiplier==null||multiplier.signum()<=0)throw new IllegalStateException("DEPTH_MULTIPLIER_INVALID");var bids=levels(json.path("bids"),multiplier,true);var asks=levels(json.path("asks"),multiplier,false);
        if(bids.isEmpty()||asks.isEmpty()||new BigDecimal(bids.getFirst().get("price")).compareTo(new BigDecimal(asks.getFirst().get("price")))>=0)throw new IllegalStateException("DEPTH_CROSSED_OR_EMPTY");
        Instant response=seconds(json.path("current")),update=seconds(json.path("update"));if(response.isAfter(at.plusSeconds(2))||response.isBefore(at.minusSeconds(15)))throw new IllegalStateException("DEPTH_RESPONSE_STALE");
        if(update.isAfter(response)||!json.path("id").asText().matches("[0-9]{1,20}"))throw new IllegalStateException("DEPTH_CLOCK_OR_SEQUENCE_INVALID");
        BigDecimal b=bids.stream().map(v->new BigDecimal(v.get("quantity"))).reduce(BigDecimal.ZERO,BigDecimal::add),a=asks.stream().map(v->new BigDecimal(v.get("quantity"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new Depth("XAU_USDT",json.path("id").asText(),response,update,at,plain(multiplier),bids,asks,plain(b.subtract(a).divide(b.add(a),MathContext.DECIMAL128)),"REST_20_LEVEL_SNAPSHOT; FULL_BOOK_CONTINUITY_UNVERIFIED");
    }
    static List<Map<String,String>> levels(JsonNode json,BigDecimal multiplier,boolean bid){
        if(!json.isArray()||json.size()>20)throw new IllegalStateException("DEPTH_FORMAT_INVALID");List<Map<String,String>> result=new ArrayList<>();BigDecimal previous=null;
        for(var row:json){var price=new BigDecimal(num(row.path("p")));var size=new BigDecimal(num(row.path("s")));if(price.signum()<=0||size.signum()<=0||previous!=null&&(bid?price.compareTo(previous)>=0:price.compareTo(previous)<=0))throw new IllegalStateException("DEPTH_LEVEL_INVALID");
            result.add(Map.of("price",plain(price),"nativeContracts",plain(size),"quantity",plain(size.multiply(multiplier))));previous=price;}return List.copyOf(result);
    }
    static Trades parseTrades(JsonNode json,BigDecimal multiplier,Instant at){
        if(!json.isArray()||json.size()>100||multiplier==null||multiplier.signum()<=0)throw new IllegalStateException("TRADES_FORMAT_INVALID");
        var rows=new ArrayList<Map<String,String>>();Set<String> ids=new HashSet<>();BigDecimal volume=BigDecimal.ZERO,notional=BigDecimal.ZERO;Instant first=null,last=null;
        for(var row:json){if(!row.path("contract").asText().equals("XAU_USDT"))throw new IllegalStateException("TRADES_WRONG_PRODUCT");String id=row.path("id").asText();if(id.isBlank()||!ids.add(id))throw new IllegalStateException("TRADE_ID_DUPLICATE");
            var time=seconds(row.path("create_time"));if(time.isAfter(at.plusSeconds(2)))throw new IllegalStateException("TRADE_TIME_FUTURE");var size=new BigDecimal(num(row.path("size")));var price=new BigDecimal(num(row.path("price")));if(size.signum()==0||price.signum()<=0)throw new IllegalStateException("TRADE_VALUE_INVALID");var quantity=size.abs().multiply(multiplier);
            rows.add(Map.of("id",id,"at",time.toString(),"price",plain(price),"signedContracts",plain(size),"quantity",plain(quantity)));volume=volume.add(quantity);notional=notional.add(quantity.multiply(price));if(first==null||time.isBefore(first))first=time;if(last==null||time.isAfter(last))last=time;
        }return new Trades("XAU_USDT",at,first,last,rows.size(),plain(multiplier),plain(volume),volume.signum()==0?null:plain(notional.divide(volume,MathContext.DECIMAL128)),List.copyOf(rows),"LATEST_100_SAMPLE; NOT_FIXED_TIME_WINDOW; FULL_CONTINUITY_AND_AGGRESSOR_SIDE_UNVERIFIED");
    }
    static Instant seconds(JsonNode n){var value=new BigDecimal(num(n));long seconds=value.toBigInteger().longValueExact();long nanos=value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValueExact();return Instant.ofEpochSecond(seconds,nanos);}
    private static Observation observation(String id,String source,String label,String period,String frequency,String unit,Map<String,String> values,Instant pub,Instant at,String hash,String note){return new Observation(id,source,label,period,frequency,unit,Map.copyOf(values),pub,at,URLS.get(source),hash,note);}
    private static String xml(org.w3c.dom.Element e,String name){var nodes=e.getElementsByTagName(name);return nodes.getLength()==0?"":nodes.item(0).getTextContent();}
    private static String nullable(JsonNode n){String v=n.asText();if(n.isMissingNode()||n.isNull()||v.isBlank()||v.equals("null"))return null;return num(n);}
    private static String num(JsonNode n){String v=n.isNumber()?n.decimalValue().toPlainString():n.asText();if(!v.matches("-?[0-9]{1,18}(?:\\.[0-9]{1,12})?"))throw new IllegalStateException("PUBLIC_NUMBER_INVALID");return v;}
    private static String plain(BigDecimal n){return n.stripTrailingZeros().toPlainString();}
}
