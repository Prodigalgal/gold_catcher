package com.goldcatcher.analysis;

import com.goldcatcher.information.NewsService;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.jsoup.Jsoup;
import tools.jackson.databind.*;
import static com.goldcatcher.analysis.AnalysisTypes.*;

/** Public page observations. The dated quote is extracted as JSON; scripts are never executed. */
final class CnbcRates {
    private CnbcRates() {}
    static final List<String> SYMBOLS=List.of("US2Y","US10Y","US10YTIPS");
    record Point(Instant sourceAt,Instant receivedAt,BigDecimal value) {}
    record Observed(String symbol,BigDecimal value,BigDecimal previous,Instant sourceAt,String fingerprint,String rawQuote,String pageHash) {}
    static String url(String symbol){if(!SYMBOLS.contains(symbol))throw new IllegalArgumentException("UNKNOWN_RATE_SYMBOL");return "https://www.cnbc.com/quotes/"+symbol;}
    static String id(String symbol){return switch(symbol){case "US2Y"->"US_INTRADAY_NOMINAL_2Y";case "US10Y"->"US_INTRADAY_NOMINAL_10Y";case "US10YTIPS"->"US_INTRADAY_REAL_10Y";default->throw new IllegalArgumentException("UNKNOWN_RATE_SYMBOL");};}
    static String label(String symbol){return switch(symbol){case "US2Y"->"美国2年名义收益率 · 公开盘中";case "US10Y"->"美国10年名义收益率 · 公开盘中";case "US10YTIPS"->"美国10年TIPS实际收益率 · 公开盘中";default->throw new IllegalArgumentException("UNKNOWN_RATE_SYMBOL");};}
    static Observed parse(String symbol,byte[] page,ObjectMapper mapper,Instant receivedAt){
        url(symbol);String html=new String(page,StandardCharsets.UTF_8);JsonNode rows=null;
        for(var script:Jsoup.parse(html).select("script")) {
            String body=script.data().strip();if(!body.startsWith("window.__s_data"))continue;
            int equals=body.indexOf('=');if(equals<0)continue;
            // Other SSR fields can contain JavaScript undefined. Read only quote.data as strict JSON.
            String quoteState=propertyContainer(body.substring(equals+1).stripLeading(),"quote");
            rows=mapper.readTree(propertyContainer(quoteState,"data"));break;
        }
        if(rows==null||!rows.isArray())throw new IllegalStateException("CNBC_RATE_PAGE_FORMAT_CHANGED");
        JsonNode quote=null;for(var candidate:rows)if(symbol.equals(candidate.path("symbol").asText())) {
            if(quote!=null)throw new IllegalStateException("CNBC_RATE_DUPLICATE_SYMBOL");quote=candidate;
        }
        if(quote==null||!quote.path("code").asText().equals("0")||!quote.path("type").asText().equals("BOND"))throw new IllegalStateException("CNBC_RATE_SYMBOL_MISMATCH");
        BigDecimal value=percent(quote.path("last").asText());
        BigDecimal previous=quote.path("previous_day_closing").asText().isBlank()?null:percent(quote.path("previous_day_closing").asText());
        Instant sourceAt;
        try{sourceAt=OffsetDateTime.parse(quote.path("last_time").asText().replaceFirst("([+-][0-9]{2})([0-9]{2})$","$1:$2")).toInstant();}
        catch(RuntimeException invalid){throw new IllegalStateException("CNBC_RATE_DATED_TIMESTAMP_REQUIRED");}
        if(sourceAt.isAfter(receivedAt.plusSeconds(2)))throw new IllegalStateException("CNBC_RATE_FUTURE_TIMESTAMP");
        String raw=mapper.writeValueAsString(quote);return new Observed(symbol,value,previous,sourceAt,NewsService.hash(raw),raw,NewsService.hash(html));
    }
    // Select a direct quoted property without evaluating JavaScript or matching text inside strings.
    static String propertyContainer(String input,String key){
        if(input.isEmpty()||input.charAt(0)!='{')throw new IllegalStateException("CNBC_RATE_STATE_NOT_JSON");
        int depth=0;String found=null;
        for(int i=0;i<input.length();i++) {char c=input.charAt(i);
            if(c=='"'){
                int end=stringEnd(input,i);int colon=end+1;while(colon<input.length()&&Character.isWhitespace(input.charAt(colon)))colon++;
                if(depth==1&&input.substring(i,end+1).equals("\""+key+"\"")&&colon<input.length()&&input.charAt(colon)==':'){
                    if(found!=null)throw new IllegalStateException("CNBC_RATE_DUPLICATE_PROPERTY");
                    found=jsonContainer(input.substring(colon+1).stripLeading());
                }
                i=end;continue;
            }
            if(c=='{'||c=='[')depth++;else if(c=='}'||c==']'){depth--;if(depth==0)break;}
        }
        if(found==null)throw new IllegalStateException("CNBC_RATE_PAGE_FORMAT_CHANGED");return found;
    }
    private static int stringEnd(String input,int start){
        boolean escaped=false;for(int i=start+1;i<input.length();i++){char c=input.charAt(i);if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')return i;}
        throw new IllegalStateException("CNBC_RATE_STATE_INCOMPLETE");
    }
    // Stop at a complete object/array, respecting escaped quotes and verifying bracket pairs.
    private static String jsonContainer(String input){
        if(input.isEmpty()||input.charAt(0)!='{'&&input.charAt(0)!='[')throw new IllegalStateException("CNBC_RATE_STATE_NOT_JSON");
        var stack=new ArrayDeque<Character>();boolean string=false,escaped=false;
        for(int i=0;i<input.length();i++) {char c=input.charAt(i);
            if(string){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')string=false;continue;}
            if(c=='"')string=true;else if(c=='{'||c=='[')stack.push(c);else if(c=='}'||c==']'){
                if(stack.isEmpty()||stack.pop()!=(c=='}'?'{':'['))throw new IllegalStateException("CNBC_RATE_STATE_INCOMPLETE");
                if(stack.isEmpty())return input.substring(0,i+1);
            }
        }
        throw new IllegalStateException("CNBC_RATE_STATE_INCOMPLETE");
    }
    private static BigDecimal percent(String value){
        if(!value.matches("-?[0-9]{1,2}(?:\\.[0-9]{1,8})?%"))throw new IllegalStateException("CNBC_RATE_PERCENT_UNIT_REQUIRED");
        var result=new BigDecimal(value.substring(0,value.length()-1));if(result.compareTo(new BigDecimal("-20"))<0||result.compareTo(new BigDecimal("50"))>0)throw new IllegalStateException("CNBC_RATE_VALUE_OUT_OF_RANGE");return result;
    }
    static Driver driver(Observed quote,Instant receivedAt,int version,List<Point> history){
        var changes=new LinkedHashMap<String,String>();
        if(recent(quote.sourceAt(),receivedAt))for(int minutes:List.of(5,15,60)) {
            var target=quote.sourceAt().minusSeconds(minutes*60L);
            var before=history.stream().filter(p->!p.sourceAt().isAfter(target)&&!p.receivedAt().isAfter(receivedAt))
                .max(Comparator.comparing(Point::sourceAt)).orElse(null);
            if(before!=null&&Duration.between(before.sourceAt(),target).toSeconds()<=90)changes.put(Integer.toString(minutes),bp(quote.value(),before.value()));
        }
        return new Driver(id(quote.symbol()),label(quote.symbol()),"RATES","CNBC_PUBLIC_WEB",quote.symbol(),url(quote.symbol()),
            "INTRADAY_PUBLIC_WEB","PERCENT",plain(quote.value()),quote.previous()==null?null:plain(quote.previous()),
            quote.previous()==null?null:bp(quote.value(),quote.previous()),"BASIS_POINTS",quote.sourceAt().atZone(ZoneId.of("America/New_York")).toLocalDate().toString(),
            quote.sourceAt(),receivedAt,version,recent(quote.sourceAt(),receivedAt)?"RECENT_SOURCE_QUOTE":"STALE_OR_MARKET_CLOSED",Map.of(),null,
            "PROVIDER_LAST_TIME_WITH_DATE_AND_OFFSET; RECEIPT_IS_NOT_QUOTE_TIME",
            "公开网页提供方报价，60秒检查；按last_time判断时效。realTime标记不保证延迟/SLA；未验证开市连续性。日变化为较提供方前收的bp，窗口变化另列；非黄金成交报价。",changes);
    }
    static boolean recent(Instant sourceAt,Instant at){return sourceAt!=null&&!sourceAt.isAfter(at.plusSeconds(2))&&Duration.between(sourceAt,at).toSeconds()<=180;}
    static Driver at(Driver d,Instant at){
        if(!d.frequency().equals("INTRADAY_PUBLIC_WEB"))return d;
        String status=recent(d.responseAt(),at)&&!d.receivedAt().isAfter(at)&&Duration.between(d.receivedAt(),at).toSeconds()<=180?"RECENT_SOURCE_QUOTE":"STALE_OR_MARKET_CLOSED";
        if(status.equals(d.marketStatus()))return d;
        return new Driver(d.id(),d.label(),d.family(),d.source(),d.nativeSymbol(),d.url(),d.frequency(),d.unit(),d.value(),d.previous(),d.change(),d.changeUnit(),d.asOfDate(),d.responseAt(),d.receivedAt(),d.version(),status,d.returnsPct(),d.referenceChangePct(),d.timestampMeaning(),d.note(),d.changesBp());
    }
    private static String bp(BigDecimal value,BigDecimal before){return plain(value.subtract(before).multiply(BigDecimal.valueOf(100)));}
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
