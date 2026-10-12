package com.goldcatcher.analysis;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.jsoup.Jsoup;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** Public calendar: survey consensus and TE's own forecast are separate source fields. */
final class TradingEconomicsCalendar {
    static final String URL="https://tradingeconomics.com/calendar";
    private TradingEconomicsCalendar() {}
    static ArrayNode parse(byte[] bytes,ObjectMapper mapper){
        var doc=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),URL);
        var selected=doc.select("#DropDownListTimezone option[selected]");
        if(selected.size()!=1||!selected.getFirst().attr("value").matches("-?\\d{1,4}"))throw new IllegalStateException("TE_CALENDAR_TIMEZONE_UNKNOWN");
        int minutes=Integer.parseInt(selected.getFirst().attr("value"));if(minutes < -720||minutes > 840)throw new IllegalStateException("TE_CALENDAR_TIMEZONE_UNKNOWN");
        var heads=doc.select("#calendar thead th").eachText();if(!heads.containsAll(List.of("Actual","Previous","Consensus","Forecast")))throw new IllegalStateException("TE_CALENDAR_FORMAT_INVALID");
        var offset=ZoneOffset.ofTotalSeconds(minutes*60);var rows=doc.select("#calendar tr[data-country=united states][data-id]");
        if(rows.isEmpty())throw new IllegalStateException("TE_CALENDAR_FORMAT_INVALID");var result=mapper.createArrayNode();
        var format=DateTimeFormatter.ofPattern("uuuu-MM-dd hh:mm a",Locale.US).withResolverStyle(ResolverStyle.STRICT);
        for(var row:rows){
            var dateCell=row.children().stream().filter(e->e.tagName().equals("td")).findFirst().orElseThrow(()->new IllegalStateException("TE_CALENDAR_FORMAT_INVALID"));
            var date=dateCell.classNames().stream().filter(value->value.matches("20\\d{2}-\\d{2}-\\d{2}")).findFirst();
            String time=dateCell.text().strip(),title=row.select(".calendar-event").text();
            if(date.isEmpty()||!time.matches("\\d{2}:\\d{2} [AP]M"))continue; // Unknown/all-day timing is not an exact surprise join.
            var cells=row.children().stream().filter(e->e.tagName().equals("td")).toList();int ownCount=row.select("[id=forecast]").size();
            if(cells.size()<7)throw new IllegalStateException("TE_CALENDAR_FORMAT_INVALID");
            if(title.isBlank()){var titleCell=cells.get(2).clone();titleCell.select(".calendar-reference").remove();title=titleCell.text();}
            if(title.isBlank()||title.length()>240||!row.attr("data-id").matches("\\d{1,16}")||cells.size()<7||row.select("[id=consensus]").size()!=1||ownCount>1||ownCount==0&&!cells.get(6).text().isBlank())throw new IllegalStateException("TE_CALENDAR_FORMAT_INVALID");
            Instant at;try{at=LocalDateTime.parse(date.get()+" "+time,format).toInstant(offset);}catch(DateTimeException invalid){throw new IllegalStateException("TE_CALENDAR_DATE_INVALID");}
            String importance=dateCell.select("[class*=calendar-date-]").stream().flatMap(e->e.classNames().stream()).filter(value->value.matches("calendar-date-[123]")).findFirst().orElse("");
            var value=result.addObject();value.put("country","USD").put("title",title).put("date",at.toString()).put("providerEventId",row.attr("data-id"));
            value.put("forecast",row.select("[id=consensus]").text()).put("previous",row.select("[id=previous]").text());
            value.put("rawProviderForecast",row.select("[id=forecast]").text()).put("rawActual",row.select("[id=actual]").text()).put("referencePeriod",row.select(".calendar-reference").text());
            value.put("impact",importance.endsWith("3")?"High":importance.endsWith("2")?"Medium":importance.endsWith("1")?"Low":"UNKNOWN");
        }
        if(result.isEmpty())throw new IllegalStateException("TE_CALENDAR_NO_TIMED_US_EVENTS");return result;
    }
    static String indicator(String title){return switch(title){
        case "Inflation Rate MoM"->"CPI_MOM";case "Inflation Rate YoY"->"CPI_YOY";case "Core Inflation Rate MoM"->"CORE_CPI_MOM";case "Core Inflation Rate YoY"->"CORE_CPI_YOY";
        case "Non Farm Payrolls"->"NFP";case "Unemployment Rate"->"UNEMPLOYMENT";case "Average Hourly Earnings MoM"->"WAGES_MOM";case "PPI MoM"->"PPI_MOM";case "JOLTs Job Openings"->"JOLTS";
        case "Core PCE Price Index MoM"->"CORE_PCE_MOM";case "PCE Price Index MoM"->"PCE_MOM";
        case "GDP Growth Rate QoQ Adv"->"GDP_ADVANCE";case "GDP Growth Rate QoQ 2nd Est"->"GDP_SECOND";case "GDP Growth Rate QoQ 3rd Est"->"GDP_THIRD";default->null;
    };}
}
