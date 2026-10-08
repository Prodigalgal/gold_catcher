package com.goldcatcher.information;

import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
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
import org.w3c.dom.Element;

@Service
public class NewsService {
    public record News(String id,String source,String title,String excerpt,String link,Instant publishedAt,
        Instant receivedAt,String verification,int version,String contentHash) {}
    public record CalendarEvent(String id,String source,String title,Instant scheduledAt,String originalZone,String link,Instant receivedAt) {}
    public record SourceState(String id,String url,String state,int intervalSeconds,Instant lastSuccess,
        Instant nextPoll,String error,int entries) {}
    private static final Map<String,String> FEEDS=Map.of(
        "FED_PRESS","https://www.federalreserve.gov/feeds/press_all.xml",
        "FED_SPEECH","https://www.federalreserve.gov/feeds/speeches.xml",
        "BEA_NEWS","https://apps.bea.gov/rss/rss.xml",
        "ECB_PRESS","https://www.ecb.europa.eu/rss/press.html",
        "BEA_CALENDAR","https://www.bea.gov/news/schedule",
        "BLS_NEWS","https://www.bls.gov/feed/bls_latest.rss",
        "BLS_CALENDAR","https://www.bls.gov/schedule/news_release/bls.ics");
    private final RuntimeSettings settings;private final RemoteHttp http;private final DocumentStore store;
    private final EventHub events;private final Clock clock;
    private final Map<String,SourceState> states=new ConcurrentHashMap<>();
    private final Map<String,String> etags=new ConcurrentHashMap<>();
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> newsTrigger=id->{};
    public NewsService(RuntimeSettings settings,RemoteHttp http,DocumentStore store,EventHub events,Clock clock) {
        this.settings=settings;this.http=http;this.store=store;this.events=events;this.clock=clock;
    }
    public void onNews(Consumer<String> trigger){newsTrigger=trigger;}
    public List<News> news(){return store.list("news",News.class,200).stream()
        .sorted(Comparator.comparing((News news)->news.publishedAt()==null?news.receivedAt():news.publishedAt()).reversed()).limit(100).toList();}
    public List<CalendarEvent> calendar(){return store.list("calendar",CalendarEvent.class,200).stream()
        .filter(event->event.scheduledAt().isAfter(clock.instant().minus(Duration.ofDays(2))))
        .sorted(Comparator.comparing(CalendarEvent::scheduledAt)).limit(40).toList();}
    public List<SourceState> sources(){return FEEDS.entrySet().stream().map(feed->states.getOrDefault(feed.getKey(),
        new SourceState(feed.getKey(),feed.getValue(),"DISABLED",30,null,null,null,0))).toList();}
    public List<News> evidence(Instant cutoff){return news().stream().filter(n->!n.receivedAt().isAfter(cutoff))
        .filter(n->n.publishedAt()==null||!n.publishedAt().isAfter(cutoff)).limit(12).toList();}
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
                var builder=HttpRequest.newBuilder(URI.create(feed.getValue())).timeout(Duration.ofSeconds(12))
                    .header("User-Agent","GoldCatcher/0.3 official-feed-reader");
                if(etags.containsKey(feed.getKey()))builder.header("If-None-Match",etags.get(feed.getKey()));
                var response=http.send(builder.GET().build(),1_500_000);
                if(response.statusCode()==304) {
                    states.put(feed.getKey(),new SourceState(feed.getKey(),feed.getValue(),"CURRENT",interval,clock.instant(),
                        clock.instant().plusSeconds(interval),null,previous==null?0:previous.entries()));events.changed();return;
                }
                if(response.statusCode()!=200)throw new IllegalStateException("HTTP_"+response.statusCode());
                response.headers().firstValue("etag").ifPresent(value->etags.put(feed.getKey(),value));
                int count=feed.getKey().equals("BEA_CALENDAR")?parseBeaSchedule(response.body(),feed.getValue()):
                    feed.getKey().endsWith("_CALENDAR")?parseCalendar(feed.getKey(),response.body(),feed.getValue()):parseRss(feed.getKey(),response.body());
                states.put(feed.getKey(),new SourceState(feed.getKey(),feed.getValue(),"CURRENT",interval,clock.instant(),
                    clock.instant().plusSeconds(interval),null,count));events.changed();
            } catch(Exception failure) {
                int delay=previous!=null&&previous.error()!=null?Math.min(previous.intervalSeconds()*2,feed.getKey().endsWith("_CALENDAR")?3600:300):60;
                states.put(feed.getKey(),new SourceState(feed.getKey(),feed.getValue(),"FETCH_FAILED",delay,
                    previous==null?null:previous.lastSuccess(),clock.instant().plusSeconds(delay),
                    failure instanceof IllegalStateException&&failure.getMessage()!=null&&failure.getMessage().matches("HTTP_[0-9]+")?failure.getMessage():"SOURCE_FETCH_OR_PARSE_FAILED",0));events.changed();
            }
    }
    int parseRss(String source,byte[] xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var nodes=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getElementsByTagName("item");
        int changed=0;
        for(int i=0;i<nodes.getLength();i++) {
            Element item=(Element)nodes.item(i);String title=text(item,"title"),link=text(item,"link");
            if(title.isBlank()||!safeLink(link))continue;
            String identity=text(item,"guid");if(identity.isBlank())identity=link;
            String id=hash(source+":"+identity),excerpt=text(item,"description").replaceAll("<[^>]+>"," ").replaceAll("\\s+"," ").trim();
            if(excerpt.length()>1800)excerpt=excerpt.substring(0,1800)+" [来源摘要已截短]";
            String fingerprint=hash(title+"\n"+excerpt+"\n"+text(item,"pubDate"));
            var previous=store.find("news",id,News.class);
            if(previous.isPresent()&&previous.get().contentHash().equals(fingerprint))continue;
            Instant published=date(text(item,"pubDate"));
            var article=new News(id,source,title,excerpt,link,published,clock.instant(),"OFFICIAL_SOURCE",
                previous.map(n->n.version()+1).orElse(1),fingerprint);
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
            store.save("calendar",id,new CalendarEvent(id,"BEA_CALENDAR",title,scheduled,"America/New_York",link,clock.instant()));entries++;
        }
        if(entries==0)throw new IllegalStateException("BEA_CALENDAR_FORMAT_CHANGED");return entries;
    }
    private String htmlField(String source,String regex) {
        var match=java.util.regex.Pattern.compile(regex,java.util.regex.Pattern.DOTALL).matcher(source);
        return match.find()?match.group(1).replaceAll("<[^>]+>"," ").replace("&amp;","&").replace("&#039;","'").replace("&quot;","\"").replaceAll("\\s+"," ").trim():"";
    }
    int parseCalendar(String calendarSource,byte[] data,String link) {
        String source=new String(data,StandardCharsets.UTF_8).replaceAll("\\r?\\n[ \\t]","");
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
                else scheduled=LocalDateTime.parse(start,DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(ZoneId.of(zone)).toInstant();
            }catch(RuntimeException invalid){continue;}
            String id=hash(calendarSource+":"+(uid.isBlank()?title+start:uid));
            store.save("calendar",id,new CalendarEvent(id,calendarSource,title,scheduled,zone,link,clock.instant()));entries++;
        }
        return entries;
    }
    private boolean safeLink(String value) {try {var uri=URI.create(value);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(RuntimeException invalid){return false;}}
    private String text(Element root,String tag){var nodes=root.getElementsByTagName(tag);return nodes.getLength()==0?"":nodes.item(0).getTextContent().trim();}
    private Instant date(String value){try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException invalid){try{return Instant.parse(value);}catch(RuntimeException ignored){return null;}}}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception unavailable){throw new IllegalStateException(unavailable);}}
}
