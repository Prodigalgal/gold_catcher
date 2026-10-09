package com.goldcatcher.information;

import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.network.SourceProxyPool;
import com.goldcatcher.network.BrowserSourceRequest;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.jsoup.Jsoup;
import org.w3c.dom.Element;

@Service
public class NewsService {
    public record News(String id,String source,String title,String excerpt,String link,Instant publishedAt,
        Instant receivedAt,String verification,int version,String contentHash,String publishedPrecision,
        String contentState,Integer contentCharacters,Instant contentReceivedAt,String bodyHash,String freshness,Boolean archived,Instant sourcePublishedAt) {
        public News(String id,String source,String title,String excerpt,String link,Instant publishedAt,Instant receivedAt,
            String verification,int version,String contentHash) {
            this(id,source,title,excerpt,link,publishedAt,receivedAt,verification,version,contentHash,
                publishedAt==null?"UNKNOWN":"EXACT",null,null,null,null,null,null,null);
        }
    }
    public record CalendarEvent(String id,String source,String title,Instant scheduledAt,String originalZone,String link,
        Instant receivedAt,String scheduledDate,String endDate,String timePrecision) {
        public CalendarEvent(String id,String source,String title,Instant scheduledAt,String originalZone,String link,Instant receivedAt) {
            this(id,source,title,scheduledAt,originalZone,link,receivedAt,null,null,"EXACT");
        }
    }
    public record ArticleBody(String id,String source,String link,String text,String state,String hash,Instant receivedAt,
        Instant checkedAt,Instant nextPoll,int version,String error,boolean truncated,String feedHash,Instant releaseAt,String format) {}
    public record SourceQuality(int records,int datedRecords,int bodies,int excludedRecords,Instant lastPublishedAt,
        Instant lastReceivedAt,Instant lastBodySuccess,String connection,String publication,String content,String note) {}
    public record SourceState(String id,String url,String state,int intervalSeconds,Instant lastSuccess,
        Instant nextPoll,String error,int entries,SourceQuality quality,String route) {
        public SourceState(String id,String url,String state,int intervalSeconds,Instant lastSuccess,Instant nextPoll,String error,int entries) {
            this(id,url,state,intervalSeconds,lastSuccess,nextPoll,error,entries,null,"DIRECT");
        }
    }
    private static final Map<String,String> FEEDS=Map.ofEntries(
        Map.entry("FED_PRESS","https://www.federalreserve.gov/feeds/press_all.xml"),
        Map.entry("FED_SPEECH","https://www.federalreserve.gov/feeds/speeches.xml"),
        Map.entry("BEA_NEWS","https://www.bea.gov/news/current-releases"),
        Map.entry("ECB_PRESS","https://www.ecb.europa.eu/rss/press.html"),
        Map.entry("BEA_CALENDAR","https://www.bea.gov/news/schedule"),
        Map.entry("BLS_CPI","https://www.bls.gov/feed/cpi.rss"),
        Map.entry("BLS_EMPLOYMENT","https://www.bls.gov/feed/empsit.rss"),
        Map.entry("BLS_PPI","https://www.bls.gov/feed/ppi.rss"),
        Map.entry("BLS_JOLTS","https://www.bls.gov/feed/jolts.rss"),
        Map.entry("BLS_CALENDAR","https://www.bls.gov/schedule/news_release/bls.ics"),
        Map.entry("FOMC_CALENDAR","https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"));
    private final RuntimeSettings settings;private final RemoteHttp http;private final DocumentStore store;
    private final EventHub events;private final Clock clock;
    private final Map<String,SourceState> states=new ConcurrentHashMap<>();
    private final Map<String,String> etags=new ConcurrentHashMap<>();
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private final Set<String> bodyInFlight=ConcurrentHashMap.newKeySet();
    private final Map<String,ArticleBody> bodies=new ConcurrentHashMap<>();
    private final SourceProxyPool proxy;
    private volatile Consumer<String> newsTrigger=id->{};
    public NewsService(RuntimeSettings settings,RemoteHttp http,DocumentStore store,EventHub events,Clock clock) {
        this(settings,http,store,events,clock,null);
    }
    @Autowired public NewsService(RuntimeSettings settings,RemoteHttp http,DocumentStore store,EventHub events,Clock clock,SourceProxyPool proxy) {
        this.settings=settings;this.http=http;this.store=store;this.events=events;this.clock=clock;this.proxy=proxy;
    }
    @PostConstruct void recover() {
        store.list("news-body",ArticleBody.class,1000).forEach(body->bodies.put(body.id(),body));
        for(var state:store.list("source-state",SourceState.class,100))if(Objects.equals(FEEDS.get(state.id()),state.url())
            &&Objects.equals(state.route(),proxy==null?"DIRECT":proxy.routeName(state.id())))
            states.put(state.id(),state);
    }
    public void onNews(Consumer<String> trigger){newsTrigger=trigger;}
    public List<News> news(){return store.list("news",News.class,1000).stream().map(this::decorate)
        .sorted(Comparator.comparing((News news)->Boolean.TRUE.equals(news.archived()))
            .thenComparing(News::publishedAt,Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(News::receivedAt,Comparator.nullsLast(Comparator.reverseOrder()))).limit(300).toList();}
    public List<CalendarEvent> calendar(){return store.list("calendar",CalendarEvent.class,1000).stream()
        .filter(event->calendarEnd(event).isAfter(clock.instant().minus(Duration.ofDays(2))))
        .sorted(Comparator.comparing(this::calendarStart)).limit(120).toList();}
    public List<SourceState> sources(){
        var articles=news();var calendars=calendar();
        return FEEDS.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(feed->{
            var state=states.getOrDefault(feed.getKey(),new SourceState(feed.getKey(),feed.getValue(),"DISABLED",30,null,null,null,0));
            var owned=articles.stream().filter(n->n.source().equals(feed.getKey())).toList();
            boolean calendar=feed.getKey().endsWith("_CALENDAR");
            int records=calendar?(int)calendars.stream().filter(e->e.source().equals(feed.getKey())).count():owned.size();
            int dated=(int)owned.stream().filter(this::verifiedTime).count();
            int full=(int)owned.stream().filter(n->n.contentCharacters()!=null&&n.contentCharacters()>0).count();
            int excluded=(int)owned.stream().filter(n->Boolean.TRUE.equals(n.archived())||!verifiedTime(n)).count();
            var quality=new SourceQuality(records,dated,full,excluded,max(owned.stream().filter(this::verifiedTime).map(News::publishedAt).toList()),
                max(owned.stream().map(News::receivedAt).toList()),max(owned.stream().map(News::contentReceivedAt).toList()),
                state.lastSuccess()==null?"UNVERIFIED":state.lastSuccess().plusSeconds(Math.max(90,state.intervalSeconds()*3L)).isBefore(clock.instant())?"DELAYED":"RECENT_FETCH",
                calendar?"SCHEDULE_ONLY":dated==0?"UNKNOWN":dated==records?"VERIFIED":"PARTIAL",
                calendar?"SCHEDULE_ONLY":full==0?"HEADLINES_ONLY":full==records-excluded?"TEXT_AVAILABLE":"PARTIAL_TEXT",
                feed.getKey().startsWith("BLS_")&&!calendar?"Feed 时间可能早于正式公布；需用正文声明的发布时间核验。":
                    feed.getKey().equals("BEA_NEWS")?"当前发布列表已替换旧 RSS；历史且时间未知的旧记录隔离。":
                    feed.getKey().equals("FOMC_CALENDAR")?"官方会议日期范围；未给出决议时刻，不推定为14:00。":
                    "连接成功不表示新闻刚刚发布；正文和发布时间分别核验。" );
            return new SourceState(state.id(),state.url(),state.state(),state.intervalSeconds(),state.lastSuccess(),state.nextPoll(),state.error(),state.entries(),quality,state.route());
        }).toList();
    }
    public List<News> evidence(Instant cutoff){return store.list("news",News.class,1000).stream()
        .filter(n->!n.receivedAt().isAfter(cutoff)).map(n->decorate(n,cutoff))
        .filter(n->verifiedTime(n)&&!n.publishedAt().isAfter(cutoff)&&!Boolean.TRUE.equals(n.archived()))
        .filter(n->n.publishedAt().isAfter(cutoff.minus(Duration.ofDays(7))))
        .sorted(Comparator.comparing(News::publishedAt,Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(News::receivedAt,Comparator.reverseOrder())).limit(12).toList();}
    private boolean verifiedTime(News news){return news.publishedAt()!=null&&!"FEED_TIMESTAMP".equals(news.publishedPrecision());}
    public List<News> analysisArticles(Instant cutoff){return store.list("news",News.class,1000).stream()
        .filter(n->!n.receivedAt().isAfter(cutoff)).map(n->decorate(n,cutoff))
        .filter(n->verifiedTime(n)&&!n.publishedAt().isAfter(cutoff)&&!Boolean.TRUE.equals(n.archived())
            &&n.publishedAt().isAfter(cutoff.minus(Duration.ofDays(90))))
        .sorted(Comparator.comparing(News::publishedAt).reversed()).toList();}
    public ArticleBody bodyForEvidence(String id,Instant cutoff){return store.find("news",id,News.class).map(n->knownBody(n,cutoff)).orElse(null);}
    private ArticleBody knownBody(News news,Instant cutoff) {
        var body=bodies.get(news.id());
        return body!=null&&body.receivedAt()!=null&&!body.receivedAt().isAfter(cutoff)&&Objects.equals(body.feedHash(),news.contentHash())?body:null;
    }
    private News decorate(News news) {return decorate(news,clock.instant());}
    private News decorate(News news,Instant cutoff) {
        var body=knownBody(news,cutoff);boolean archive=news.source().equals("BEA_NEWS")&&news.publishedAt()==null;
        Instant published=body!=null&&body.releaseAt()!=null?body.releaseAt():news.publishedAt();
        String precision=body!=null&&body.releaseAt()!=null?"DECLARED_RELEASE_TIME":
            news.publishedPrecision()==null?(published==null?"UNKNOWN":"EXACT"):news.publishedPrecision();
        String freshness=archive?"QUARANTINED":published==null?"TIME_UNKNOWN":"FEED_TIMESTAMP".equals(precision)?"TIME_UNVERIFIED":
            published.isAfter(cutoff)?"FUTURE":published.isAfter(cutoff.minus(Duration.ofDays(2)))?"RECENT":"BACKGROUND";
        String content=body==null?"PENDING":body.state();
        if(body==null&&news.publishedAt()!=null&&news.publishedAt().isBefore(cutoff.minus(Duration.ofDays(90))))content="BACKGROUND_FEED_ONLY";
        if(archive)content="QUARANTINED";
        var pending=bodies.get(news.id());if(body==null&&pending!=null&&Objects.equals(pending.feedHash(),news.contentHash()))content=pending.state();
        return new News(news.id(),news.source(),news.title(),body!=null&&!body.text().isBlank()?OfficialArticle.excerpt(body.text(),1000):news.excerpt(),
            news.link(),published,news.receivedAt(),news.verification(),news.version(),news.contentHash(),precision,content,
            body==null?0:body.text().codePointCount(0,body.text().length()),body==null?null:body.receivedAt(),body==null?null:body.hash(),freshness,archive,news.publishedAt());
    }
    private Instant max(List<Instant> values){return values.stream().filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);}
    private Instant calendarStart(CalendarEvent event){return event.scheduledAt()!=null?event.scheduledAt():LocalDate.parse(event.scheduledDate()).atStartOfDay(ZoneId.of(event.originalZone())).toInstant();}
    private Instant calendarEnd(CalendarEvent event){return event.scheduledAt()!=null?event.scheduledAt():LocalDate.parse(event.endDate()==null?event.scheduledDate():event.endDate()).plusDays(1).atStartOfDay(ZoneId.of(event.originalZone())).toInstant();}
    private void saveCalendar(CalendarEvent event) {
        var previous=store.find("calendar",event.id(),CalendarEvent.class);
        if(previous.isPresent()) {
            var old=previous.get();
            var unchanged=new CalendarEvent(event.id(),event.source(),event.title(),event.scheduledAt(),event.originalZone(),event.link(),
                old.receivedAt(),event.scheduledDate(),event.endDate(),event.timePrecision());
            if(unchanged.equals(old))return;
        }
        store.transaction(()->{store.save("calendar",event.id(),event);store.event(event.id(),"CALENDAR_VERSION",event);return event;});
    }
    public Map<String,Object> detail(String id) {
        var article=store.find("news",id,News.class).orElseThrow(()->new IllegalArgumentException("NEWS_NOT_FOUND"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("news",decorate(article));result.put("body",bodies.get(id));return result;
    }
    public List<Map<String,Object>> freezeEvidence(Instant cutoff,String runId,int limit) {
        var selected=evidence(cutoff).stream().limit(limit).toList();var packet=new ArrayList<Map<String,Object>>();var archive=new ArrayList<Map<String,Object>>();
        for(var article:selected) {
            var original=store.find("news",article.id(),News.class).orElseThrow();
            if(original.version()!=article.version()||original.receivedAt().isAfter(cutoff))continue;
            var body=knownBody(original,cutoff);
            // Freeze the exact time and body together, rather than a body updated since selection.
            article=decorate(original,cutoff);
            if(!verifiedTime(article)||article.publishedAt().isAfter(cutoff))continue;
            Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("id",article.id());evidence.put("title",article.title());
            evidence.put("excerpt",body!=null&&!body.text().isBlank()?OfficialArticle.excerpt(body.text(),480):OfficialArticle.excerpt(original.excerpt(),180));
            evidence.put("source",article.source());evidence.put("publishedAt",article.publishedAt());evidence.put("receivedAt",article.receivedAt());
            evidence.put("publishedPrecision",article.publishedPrecision());evidence.put("sourcePublishedAt",article.sourcePublishedAt());
            evidence.put("version",article.version());evidence.put("bodyHash",body==null?null:body.hash());
            evidence.put("contentState",body==null?"HEADLINE_OR_FEED_SUMMARY":body.state());packet.add(evidence);
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("news",original);snapshot.put("body",body);archive.add(snapshot);
        }
        store.save("research-news",runId,Map.of("cutoff",cutoff,"articles",archive));return packet;
    }
    @Scheduled(fixedDelay=3000,initialDelay=5000) void collectBodies() {
        if(!settings.enabled("GOLD_NEWS_ENABLED"))return;
        for(var article:news()) {
            if(article.publishedAt()==null||Boolean.TRUE.equals(article.archived())||article.publishedAt().isAfter(clock.instant())
                ||article.publishedAt().isBefore(clock.instant().minus(Duration.ofDays(90))))continue;
            var previous=bodies.get(article.id());
            if(previous!=null&&(Objects.equals(previous.feedHash(),article.contentHash())||previous.error()!=null)&&previous.nextPoll().isAfter(clock.instant()))continue;
            String group=article.source().startsWith("FED_")?"FED":article.source().startsWith("BLS_")?"BLS":article.source();
            if(!bodyInFlight.add(group))continue;
            Thread.ofVirtual().name("gold-article-"+group).start(()->{try{enrich(article);}finally{bodyInFlight.remove(group);}});
        }
    }
    void enrich(News article) {
        var previous=bodies.get(article.id());Instant now=clock.instant();ArticleBody body;
        try {
            String link=article.link();var extracted=OfficialArticle.extract(article.source(),articleBytes(article.source(),link),link);
            if(extracted.linkedMinutes()!=null) {link=extracted.linkedMinutes();extracted=OfficialArticle.extract("FED_PRESS",articleBytes("FED_PRESS",link),link);}
            String text=extracted.text();int maximum=Math.max(2000,Math.min(120000,settings.integer("GOLD_NEWS_BODY_MAX_CHARACTERS",80000)));
            boolean truncated=text.codePointCount(0,text.length())>maximum;
            if(truncated)text=text.substring(0,text.offsetByCodePoints(0,maximum));
            String fingerprint=hash(text);boolean changed=previous==null||!Objects.equals(previous.hash(),fingerprint);
            boolean revision=changed||previous!=null&&!Objects.equals(previous.feedHash(),article.contentHash());
            body=new ArticleBody(article.id(),article.source(),link,text,truncated?"TRUNCATED":"AVAILABLE",fingerprint,
                revision?now:previous.receivedAt(),now,now.plusSeconds(21600),previous==null?1:previous.version()+(revision?1:0),null,truncated,article.contentHash(),extracted.releaseAt(),extracted.format());
            ArticleBody saved=body;store.transaction(()->{store.save("news-body",article.id(),saved);if(changed)store.event(article.id(),"NEWS_BODY_VERSION",saved);return saved;});
            if(changed&&article.publishedAt()!=null&&article.publishedAt().isAfter(now.minusSeconds(1800)))newsTrigger.accept(article.id());
        }catch(Exception failure) {
            String error=failure instanceof IllegalStateException&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]+")?failure.getMessage():"ARTICLE_FETCH_FAILED";
            body=new ArticleBody(article.id(),article.source(),article.link(),previous==null?"":previous.text(),previous==null?"FETCH_FAILED":"CACHED_AFTER_FAILURE",
                previous==null?null:previous.hash(),previous==null?null:previous.receivedAt(),now,now.plusSeconds(900),previous==null?0:previous.version(),error,
                previous!=null&&previous.truncated(),previous==null?article.contentHash():previous.feedHash(),previous==null?null:previous.releaseAt(),previous==null?null:previous.format());store.save("news-body",article.id(),body);
        }
        bodies.put(article.id(),body);events.changed();
    }
    private byte[] articleBytes(String source,String link) throws Exception {
        URI target=URI.create(link);
        for(int redirect=0;redirect<3;redirect++) {
            if(!OfficialArticle.allowed(source,target))throw new IllegalStateException("ARTICLE_HOST_NOT_ALLOWED");
            var request=BrowserSourceRequest.navigation(target,Duration.ofSeconds(12)).GET().build();
            var response=proxy==null?http.send(request,4_000_000):proxy.send(source,request,4_000_000);
            if(Set.of(301,302,303,307,308).contains(response.statusCode())){target=target.resolve(response.headers().firstValue("location").orElseThrow());continue;}
            if(response.statusCode()!=200)throw new IllegalStateException("HTTP_"+response.statusCode());return response.body();
        }
        throw new IllegalStateException("ARTICLE_REDIRECT_LIMIT");
    }
    @Scheduled(fixedDelay=5000,initialDelay=1500)
    void collect() {
        if(!settings.enabled("GOLD_NEWS_ENABLED"))return;
        for(var feed:FEEDS.entrySet()) {
            var previous=states.get(feed.getKey());
            if(previous!=null&&previous.nextPoll()!=null&&previous.nextPoll().isAfter(clock.instant()))continue;
            if(!inFlight.add(feed.getKey()))continue;
            Thread.ofVirtual().name("gold-source-"+feed.getKey()).start(()->{
                try{fetch(feed,previous);}finally{inFlight.remove(feed.getKey());}
            });
        }
    }
    private void fetch(Map.Entry<String,String> feed,SourceState previous) {
            int interval=feed.getKey().endsWith("_CALENDAR")?3600:Math.max(15,settings.integer("GOLD_NEWS_POLL_SECONDS",30));
            try {
                var builder=BrowserSourceRequest.navigation(URI.create(feed.getValue()),Duration.ofSeconds(12));
                if(etags.containsKey(feed.getKey()))builder.header("If-None-Match",etags.get(feed.getKey()));
                var response=proxy==null?http.send(builder.GET().build(),1_500_000):proxy.send(feed.getKey(),builder.GET().build(),1_500_000);
                if(response.statusCode()==304) {
                    recordState(new SourceState(feed.getKey(),feed.getValue(),"CURRENT",interval,clock.instant(),
                        clock.instant().plusSeconds(interval),null,0));events.changed();return;
                }
                if(response.statusCode()!=200)throw new IllegalStateException("HTTP_"+response.statusCode());
                int count=feed.getKey().equals("BEA_NEWS")?parseBeaCurrent(response.body(),feed.getValue()):
                    feed.getKey().equals("FOMC_CALENDAR")?parseFomcSchedule(response.body(),feed.getValue()):
                    feed.getKey().equals("BEA_CALENDAR")?parseBeaSchedule(response.body(),feed.getValue()):
                    feed.getKey().endsWith("_CALENDAR")?parseCalendar(feed.getKey(),response.body(),feed.getValue()):parseRss(feed.getKey(),response.body());
                response.headers().firstValue("etag").ifPresent(value->etags.put(feed.getKey(),value));
                recordState(new SourceState(feed.getKey(),feed.getValue(),"CURRENT",interval,clock.instant(),
                    clock.instant().plusSeconds(interval),null,count));events.changed();
            } catch(Exception failure) {
                String error=failure instanceof IllegalStateException&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]+")?failure.getMessage():"SOURCE_FETCH_OR_PARSE_FAILED";
                boolean waiting=error.equals("PROXY_NOT_READY");
                int delay=waiting?5:previous!=null&&previous.error()!=null?Math.min(previous.intervalSeconds()*2,feed.getKey().endsWith("_CALENDAR")?3600:300):60;
                recordState(new SourceState(feed.getKey(),feed.getValue(),waiting?"WAITING_PROXY":"FETCH_FAILED",delay,
                    previous==null?null:previous.lastSuccess(),clock.instant().plusSeconds(delay),
                    error,0));events.changed();
            }
    }
    private void recordState(SourceState state) {
        state=new SourceState(state.id(),state.url(),state.state(),state.intervalSeconds(),state.lastSuccess(),state.nextPoll(),state.error(),state.entries(),null,
            proxy==null?"DIRECT":proxy.routeName(state.id()));
        states.put(state.id(),state);store.save("source-state",state.id(),state);
    }
    int parseBeaCurrent(byte[] bytes,String link) {
        var document=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),link);int entries=0;
        for(var row:document.select("tr.release-row")) {
            var anchor=row.selectFirst("td[headers=view-title-table-column] a[href]");var timestamp=row.selectFirst("time[datetime]");
            if(anchor==null||timestamp==null)continue;
            Instant published=date(timestamp.attr("datetime"));if(published==null)continue;
            String url=anchor.absUrl("href");if(!OfficialArticle.allowed("BEA_NEWS",URI.create(url)))continue;
            String id=hash("BEA_NEWS:"+url),title=anchor.text(),fingerprint=hash(title+"\n"+published);var previous=store.find("news",id,News.class);
            if(previous.isPresent()&&previous.get().contentHash().equals(fingerprint)){entries++;continue;}
            var article=new News(id,"BEA_NEWS",title,"",url,published,clock.instant(),"OFFICIAL_SOURCE",previous.map(n->n.version()+1).orElse(1),fingerprint);
            store.transaction(()->{store.save("news",id,article);store.event(id,"NEWS_VERSION",article);return article;});entries++;
            if(published.isAfter(clock.instant().minusSeconds(1800)))newsTrigger.accept(id);
        }
        if(entries==0)throw new IllegalStateException("BEA_NEWS_FORMAT_CHANGED");return entries;
    }
    int parseFomcSchedule(byte[] bytes,String link) {
        var document=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),link);int entries=0;
        for(var panel:document.select(".panel")) {
            var heading=panel.selectFirst(".panel-heading");if(heading==null)continue;
            var yearMatch=java.util.regex.Pattern.compile("(20[0-9]{2}) FOMC Meetings").matcher(heading.text());if(!yearMatch.find())continue;
            int year=Integer.parseInt(yearMatch.group(1));if(year<Year.now(clock).getValue()||year>Year.now(clock).getValue()+1)continue;
            for(var row:panel.select(".fomc-meeting")) {
                var month=row.selectFirst(".fomc-meeting__month");var days=row.selectFirst(".fomc-meeting__date");if(month==null||days==null)continue;
                var range=java.util.regex.Pattern.compile("^(\\d{1,2})(?:[-–](\\d{1,2}))?\\*?$").matcher(days.text());if(!range.matches())continue;
                try {
                    String[] months=month.text().split("/");int firstMonth=Month.from(DateTimeFormatter.ofPattern("MMMM",Locale.US).parse(months[0])).getValue();
                    int lastMonth=months.length==1?firstMonth:Month.from(DateTimeFormatter.ofPattern("MMMM",Locale.US).parse(months[1])).getValue();
                    var start=LocalDate.of(year,firstMonth,Integer.parseInt(range.group(1)));var end=LocalDate.of(year,lastMonth,Integer.parseInt(range.group(2)==null?range.group(1):range.group(2)));
                    String title="FOMC meeting"+(days.text().endsWith("*")?" · economic projections":""),id=hash("FOMC_CALENDAR:"+start);
                    saveCalendar(new CalendarEvent(id,"FOMC_CALENDAR",title,null,"America/New_York",link,clock.instant(),start.toString(),end.toString(),start.equals(end)?"DATE_ONLY":"DATE_RANGE"));entries++;
                }catch(java.time.DateTimeException invalid){throw new IllegalStateException("FOMC_CALENDAR_FORMAT_CHANGED");}
            }
        }
        if(entries==0)throw new IllegalStateException("FOMC_CALENDAR_FORMAT_CHANGED");return entries;
    }
    int parseRss(String source,byte[] xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        var nodes=document.getElementsByTagNameNS("*","item");boolean atom=nodes.getLength()==0;
        if(atom)nodes=document.getElementsByTagNameNS("*","entry");
        if(nodes.getLength()==0&&!Set.of("rss","feed","RDF").contains(document.getDocumentElement().getLocalName()))
            throw new IllegalStateException("NEWS_FEED_FORMAT_CHANGED");
        int changed=0;
        for(int i=0;i<nodes.getLength();i++) {
            Element item=(Element)nodes.item(i);String title=text(item,"title"),link=atom?atomLink(item):text(item,"link");
            if(title.isBlank()||!safeLink(link))continue;
            String identity=text(item,atom?"id":"guid");if(identity.isBlank())identity=link;
            String summary=text(item,atom?"content":"description");if(summary.isBlank()&&atom)summary=text(item,"summary");
            String id=hash(source+":"+identity),excerpt=Jsoup.parseBodyFragment(summary).text();
            if(excerpt.length()>1800)excerpt=excerpt.substring(0,1800)+" [来源摘要已截短]";
            String publication=text(item,atom?"published":"pubDate");if(publication.isBlank()&&atom)publication=text(item,"updated");
            String fingerprint=hash(title+"\n"+excerpt+"\n"+publication+"\n"+text(item,"updated"));
            var previous=store.find("news",id,News.class);
            if(previous.isPresent()&&previous.get().contentHash().equals(fingerprint))continue;
            Instant published=date(publication);
            var article=new News(id,source,title,excerpt,link,published,clock.instant(),"OFFICIAL_SOURCE",
                previous.map(n->n.version()+1).orElse(1),fingerprint,source.startsWith("BLS_")&&atom?"FEED_TIMESTAMP":published==null?"UNKNOWN":"EXACT",
                null,null,null,null,null,null,null);
            store.transaction(()->{store.save("news",id,article);store.event(id,"NEWS_VERSION",article);return article;});changed++;
            if(previous.isPresent()||(published!=null&&published.isAfter(clock.instant().minusSeconds(1800))))newsTrigger.accept(id);
        }
        return changed;
    }
    int parseCalendar(byte[] data,String link) {
        return parseCalendar("BLS_CALENDAR",data,link);
    }
    int parseBeaSchedule(byte[] bytes,String link) {
        String html=new String(bytes,StandardCharsets.UTF_8);
        var yearMatch=java.util.regex.Pattern.compile(">Year (20[0-9]{2})<").matcher(html);
        if(!yearMatch.find())throw new IllegalStateException("BEA_CALENDAR_FORMAT_CHANGED");
        int year=Integer.parseInt(yearMatch.group(1));int entries=0;
        var rows=java.util.regex.Pattern.compile("<tr class=\"scheduled-releases-type-press\">(.*?)</tr>",java.util.regex.Pattern.DOTALL).matcher(html);
        while(rows.find()) {
            String row=rows.group(1);String date=htmlField(row,"<div class=\"release-date\">(.*?)</div>");
            String time=htmlField(row,"<small class=\"text-muted\">(.*?)</small>");
            String title=htmlField(row,"<td class=\"release-title[^\"]*\"[^>]*>(.*?)</td>");
            if(date.isBlank()||time.isBlank()||title.isBlank())continue;
            var local=LocalDateTime.parse(date+" "+year+" "+time,DateTimeFormatter.ofPattern("MMMM d yyyy h:mm a",Locale.US));
            Instant scheduled=local.atZone(ZoneId.of("America/New_York")).toInstant();
            String id=hash("BEA_CALENDAR:"+title+":"+scheduled);
            saveCalendar(new CalendarEvent(id,"BEA_CALENDAR",title,scheduled,"America/New_York",link,clock.instant()));entries++;
        }
        if(entries==0)throw new IllegalStateException("BEA_CALENDAR_FORMAT_CHANGED");return entries;
    }
    private String htmlField(String source,String regex) {
        var match=java.util.regex.Pattern.compile(regex,java.util.regex.Pattern.DOTALL).matcher(source);
        return match.find()?match.group(1).replaceAll("<[^>]+>"," ").replace("&amp;","&").replace("&#039;","'").replace("&quot;","\"").replaceAll("\\s+"," ").trim():"";
    }
    int parseCalendar(String calendarSource,byte[] data,String link) {
        String source=new String(data,StandardCharsets.UTF_8).replaceAll("\\r?\\n[ \\t]","");
        if(!source.contains("BEGIN:VCALENDAR"))throw new IllegalStateException("CALENDAR_FORMAT_CHANGED");
        int entries=0;
        for(String block:source.split("BEGIN:VEVENT")) {
            if(!block.contains("END:VEVENT"))continue;
            String uid="",title="",start="",zone="America/New_York";
            for(String line:block.split("\\r?\\n")) {
                if(line.startsWith("UID:"))uid=line.substring(4);
                if(line.startsWith("SUMMARY:"))title=line.substring(8).replace("\\,",",").replace("\\n"," ");
                if(line.startsWith("DTSTART")) {
                    int colon=line.indexOf(':');start=line.substring(colon+1);
                    if(line.substring(0,colon).contains("TZID="))zone=line.substring(line.indexOf("TZID=")+5,colon).replace("\"","");
                }
            }
            if(start.isBlank()||title.isBlank())continue;
            Instant scheduled;
            try {
                if(start.endsWith("Z")){scheduled=LocalDateTime.parse(start.substring(0,start.length()-1),DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC);zone="UTC";}
                else scheduled=LocalDateTime.parse(start,DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .atZone(ZoneId.of(zone.equals("US-Eastern")?"America/New_York":zone)).toInstant();
            }catch(RuntimeException invalid){continue;}
            String id=hash(calendarSource+":"+(uid.isBlank()?title+start:uid));
            saveCalendar(new CalendarEvent(id,calendarSource,title,scheduled,zone,link,clock.instant()));entries++;
        }
        if(entries==0)throw new IllegalStateException("CALENDAR_FORMAT_CHANGED");return entries;
    }
    private boolean safeLink(String value) {try {var uri=URI.create(value);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(RuntimeException invalid){return false;}}
    private String atomLink(Element root){var links=root.getElementsByTagNameNS("*","link");for(int i=0;i<links.getLength();i++){
        var link=(Element)links.item(i);if(link.getAttribute("rel").isBlank()||link.getAttribute("rel").equals("alternate"))return link.getAttribute("href");}return "";}
    private String text(Element root,String tag){var nodes=root.getElementsByTagNameNS("*",tag);return nodes.getLength()==0?"":nodes.item(0).getTextContent().trim();}
    private Instant date(String value){try{return ZonedDateTime.parse(value.replaceAll("\\s+"," "),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException invalid){try{return Instant.parse(value);}catch(RuntimeException ignored){return null;}}}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception unavailable){throw new IllegalStateException(unavailable);}}
}
