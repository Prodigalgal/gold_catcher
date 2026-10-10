package com.goldcatcher.network;

import com.goldcatcher.information.NewsService.CalendarEvent;
import com.goldcatcher.persistence.DocumentStore;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Only scheduling uses these windows; it never fabricates a publication time or actual value. */
@Service
public class ReleasePolling {
    private final Clock clock;private final DocumentStore store;
    private volatile Instant checkedAt=Instant.MIN;private volatile Set<String> active=Set.of();
    public ReleasePolling(Clock clock,DocumentStore store){this.clock=clock;this.store=store;}
    public int interval(String id,int normal){
        Instant now=clock.instant();if(!checkedAt.plusSeconds(5).isAfter(now))refresh(now);
        if(!active.contains(id))return normal;
        return switch(id){case "FF_EXPECTATIONS","BLS_CALENDAR","BEA_CALENDAR"->Math.min(normal,60);case "FED_PRESS"->Math.min(normal,15);default->Math.min(normal,5);};
    }
    private synchronized void refresh(Instant now){if(checkedAt.plusSeconds(5).isAfter(now))return;active=windows(store.list("calendar",CalendarEvent.class,1000),now);checkedAt=now;}
    static Set<String> windows(List<CalendarEvent> calendar,Instant now){
        var result=new HashSet<String>();var today=now.atZone(ZoneId.of("America/New_York")).toLocalDate();
        for(var event:calendar){
            if(event.source().equals("FOMC_CALENDAR")&&event.scheduledAt()==null){
                String end=event.endDate()!=null?event.endDate():event.scheduledDate();
                if(end!=null&&end.equals(today.toString()))result.add("FED_PRESS");
                continue;
            }
            if(event.scheduledAt()==null||!"EXACT".equals(event.timePrecision())||!Set.of("BLS_CALENDAR","BEA_CALENDAR").contains(event.source()))continue;
            long until=Duration.between(now,event.scheduledAt()).getSeconds();
            if(until>=-600&&until<=1800)result.add("FF_EXPECTATIONS");
            if(until < -900||until>600)continue;
            result.add(event.source());
            if(event.source().equals("BEA_CALENDAR")){result.add("BEA_NEWS");continue;}
            String title=event.title().toLowerCase(Locale.ROOT);
            if(title.contains("consumer price index"))result.add("BLS_CPI");
            else if(title.contains("employment situation"))result.add("BLS_EMPLOYMENT");
            else if(title.contains("producer price index"))result.add("BLS_PPI");
            else if(title.contains("job openings")&&title.contains("turnover"))result.add("BLS_JOLTS");
        }
        return Set.copyOf(result);
    }
}
