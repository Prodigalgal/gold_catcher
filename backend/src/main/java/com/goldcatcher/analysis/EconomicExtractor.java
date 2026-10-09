package com.goldcatcher.analysis;

import static com.goldcatcher.analysis.AnalysisTypes.*;
import java.math.BigDecimal;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Parses explicit official statements; an unsupported template leaves a metric absent. */
public final class EconomicExtractor {
    private EconomicExtractor() {}
    static final String VERSION="OFFICIAL_METRICS_V1";
    private static final String NUMBER="([+-]?\\d[\\d,]*(?:\\.\\d+)?)";
    private static final String MONTH="(January|February|March|April|May|June|July|August|September|October|November|December)";
    private static final String VERB="(increased|rose|advanced|moved up|decreased|declined|fell|moved down)";
    private static Matcher match(String regex,String text){return Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(text);}
    private static String decimal(String value){return new BigDecimal(value.replace(",","")).stripTrailingZeros().toPlainString();}
    private static String signed(String verb,String value){var n=new BigDecimal(decimal(value));return (Set.of("decreased","declined","fell","moved down").contains(verb.toLowerCase(Locale.ROOT))?n.abs().negate():n).stripTrailingZeros().toPlainString();}
    private static String monthPeriod(String month,String year){return year+"-"+String.format(Locale.ROOT,"%02d",Month.valueOf(month.toUpperCase(Locale.ROOT)).getValue());}
    public static List<Metric> extract(String source,String title,String body) {
        String text=body.replaceAll("\\s+"," ").strip();List<Metric> metrics=new ArrayList<>();
        String heading=switch(source){case "BLS_CPI"->"CONSUMER PRICE INDEX";case "BLS_EMPLOYMENT"->"THE EMPLOYMENT SITUATION";
            case "BLS_PPI"->"PRODUCER PRICE INDEXES";case "BLS_JOLTS"->"JOB OPENINGS AND LABOR TURNOVER";default->null;};
        String period=null;
        if(heading!=null){var m=match(Pattern.quote(heading)+"\\s*[-–—:\\uFFFD]\\s*"+MONTH+"\\s+(20\\d{2})",text);if(m.find())period=monthPeriod(m.group(1),m.group(2));}
        if(source.equals("BEA_NEWS")){var m=match("Personal Income and Outlays,?\\s+"+MONTH+"\\s+(20\\d{2})",title);if(m.find())period=monthPeriod(m.group(1),m.group(2));}
        if(period!=null)switch(source) {
            case "BLS_CPI" -> {
                change(metrics,"CPI_MOM","CPI 环比",period,"PERCENT_MOM_SA",text,"(?:The )?Consumer Price Index for All Urban Consumers \\(CPI-U\\)\\s+", "",true);
                change(metrics,"CORE_CPI_MOM","核心 CPI 环比",period,"PERCENT_MOM_SA",text,"The index for all items less food and energy\\s+","",true);
                change(metrics,"CPI_YOY","CPI 同比",period,"PERCENT_YOY_NSA",text,"Over the last 12 months, the all items index\\s+","",false);
                change(metrics,"CORE_CPI_YOY","核心 CPI 同比",period,"PERCENT_YOY_NSA",text,"The all items less food and energy index\\s+","(?= (?:over the year|over the last 12 months|for the 12 months))",false);
            }
            case "BLS_EMPLOYMENT" -> {
                var payroll=match("(?:Total )?nonfarm payroll employment\\s+(?:\\(([-+]?\\d[\\d,]*)\\)|(?:increased|rose|declined|decreased|fell) by\\s+([-+]?\\d[\\d,]*)|changed little in [A-Za-z]+ \\(([-+]?\\d[\\d,]*)\\)|was (?:essentially |little )?unchanged in [A-Za-z]+ \\(([-+]?\\d[\\d,]*)\\))",text);
                if(payroll.find()){String n=null;for(int i=1;i<=4;i++)if(payroll.group(i)!=null)n=payroll.group(i);if(n!=null){n=decimal(n);if(match("declined|decreased|fell",payroll.group()).find())n=new BigDecimal(n).abs().negate().toPlainString();metrics.add(new Metric("NFP","非农就业增减",period,"COUNT",n,null,"OFFICIAL_STATEMENT",payroll.group(),payrollRevisions(text)));}}
                var unemployment=match("(?:the )?unemployment rate(?:,? at|\\s*\\(| (?:was|remained|changed little|was little changed|held steady|increased|rose|decreased|fell|edged (?:up|down))(?: [A-Za-z]+){0,5} (?:at|to))\\s*"+NUMBER+" percent",text);
                if(unemployment.find())metrics.add(new Metric("UNEMPLOYMENT","失业率",period,"PERCENT_LEVEL",decimal(unemployment.group(1)),null,"OFFICIAL_STATEMENT",unemployment.group(),List.of()));
                var wages=match("average hourly earnings for all employees on private nonfarm payrolls.{0,110}?"+NUMBER+" percent",text);
                if(wages.find())metrics.add(new Metric("WAGES_MOM","平均时薪环比",period,"PERCENT_MOM_SA",signed(match("declined|decreased|fell",wages.group()).find()?"declined":"increased",wages.group(1)),null,"OFFICIAL_STATEMENT",wages.group(),List.of()));
            }
            case "BLS_PPI" -> change(metrics,"PPI_MOM","PPI 最终需求环比",period,"PERCENT_MOM_SA",text,"(?:The )?Producer Price Index for final demand\\s+","",true);
            case "BLS_JOLTS" -> {
                var table=match("LEVELS BY INDUSTRY \\(in thousands\\) Total\\s+"+NUMBER+"\\s+"+NUMBER+"\\s+"+NUMBER,text);
                if(table.find())metrics.add(new Metric("JOLTS","职位空缺",period,"COUNT",new BigDecimal(decimal(table.group(3))).multiply(BigDecimal.valueOf(1000)).toPlainString(),new BigDecimal(decimal(table.group(2))).multiply(BigDecimal.valueOf(1000)).toPlainString(),"OFFICIAL_TABLE_THOUSANDS",table.group(),List.of()));
                else {var openings=match("(?:The )?number of job openings.{0,75}?(?:at|to) "+NUMBER+" million",text);if(openings.find())metrics.add(new Metric("JOLTS","职位空缺",period,"COUNT",new BigDecimal(decimal(openings.group(1))).multiply(BigDecimal.valueOf(1000000)).toPlainString(),null,"OFFICIAL_ROUNDED_MILLIONS",openings.group(),List.of()));}
            }
            case "BEA_NEWS" -> {
                change(metrics,"PCE_MOM","PCE 物价环比",period,"PERCENT_MOM_SA",text,"From the preceding month, the PCE price index(?: for [A-Za-z]+)?\\s+","",false);
                change(metrics,"CORE_PCE_MOM","核心 PCE 环比",period,"PERCENT_MOM_SA",text,"From the preceding month, the PCE price index.{0,90}?Excluding food and energy, the PCE price index\\s+","",false);
                change(metrics,"PCE_YOY","PCE 物价同比",period,"PERCENT_YOY",text,"From the same month one year ago, the PCE price index(?: for [A-Za-z]+)?\\s+","",false);
                change(metrics,"CORE_PCE_YOY","核心 PCE 同比",period,"PERCENT_YOY",text,"From the same month one year ago, the PCE price index.{0,90}?Excluding food and energy, the PCE price index\\s+","",false);
            }
        }
        if(source.equals("BEA_NEWS")) {
            var gdp=match("Real gross domestic product \\(GDP\\) "+VERB+" at an annual rate of "+NUMBER+" percent in the (first|second|third|fourth) quarter of (20\\d{2})",text);
            if(gdp.find()){int quarter=List.of("first","second","third","fourth").indexOf(gdp.group(3).toLowerCase(Locale.ROOT))+1;
                String estimate=title.toLowerCase(Locale.ROOT).contains("third estimate")?"THIRD":title.toLowerCase(Locale.ROOT).contains("second estimate")?"SECOND":title.toLowerCase(Locale.ROOT).contains("advance estimate")?"ADVANCE":"UNSPECIFIED";
                metrics.add(new Metric("GDP_"+estimate,"GDP 年化季环比 · "+estimate,gdp.group(4)+"-Q"+quarter,"PERCENT_QOQ_ANNUALIZED_SA",signed(gdp.group(1),gdp.group(2)),null,"OFFICIAL_STATEMENT",gdp.group(),List.of()));}
        }
        return List.copyOf(metrics);
    }
    private static void change(List<Metric> target,String indicator,String label,String period,String unit,String text,String prefix,String suffix,boolean previous) {
        var matcher=match(prefix+VERB+" "+NUMBER+" percent"+suffix,text);
        if(matcher.find()) {
            String prior=null;String excerpt=matcher.group();
            if(previous){var before=match(".{0,100}?after (rising|increasing|decreasing|declining|falling|moving up|moving down) "+NUMBER+" percent",text.substring(matcher.end(),Math.min(text.length(),matcher.end()+180)));if(before.find()){
                String verb=switch(before.group(1).toLowerCase(Locale.ROOT)){case "decreasing","declining","falling","moving down"->"decreased";default->"increased";};prior=signed(verb,before.group(2));excerpt+=before.group();}}
            target.add(new Metric(indicator,label,period,unit,signed(matcher.group(1),matcher.group(2)),prior,"OFFICIAL_STATEMENT",excerpt,List.of()));
        } else {var flat=match(prefix+"(?:was |remained )?unchanged"+suffix,text);if(flat.find())target.add(new Metric(indicator,label,period,unit,"0",null,"OFFICIAL_STATEMENT",flat.group(),List.of()));}
    }
    private static List<Revision> payrollRevisions(String text) {
        List<Revision> result=new ArrayList<>();
        var m=match("(?:change in total nonfarm payroll employment|change) for "+MONTH+" was revised (?:up|down) by [\\d,]+, from "+NUMBER+" to "+NUMBER,text);
        while(m.find()){var before=new BigDecimal(decimal(m.group(2)));var after=new BigDecimal(decimal(m.group(3)));result.add(new Revision(m.group(1),before.toPlainString(),after.toPlainString(),after.subtract(before).toPlainString(),"COUNT",m.group()));}
        return List.copyOf(result);
    }
    public static String indicator(String title) {
        return switch(title) {
            case "CPI m/m"->"CPI_MOM";case "Core CPI m/m"->"CORE_CPI_MOM";case "CPI y/y"->"CPI_YOY";
            case "Core CPI y/y"->"CORE_CPI_YOY";case "Non-Farm Employment Change"->"NFP";case "Unemployment Rate"->"UNEMPLOYMENT";
            case "Average Hourly Earnings m/m"->"WAGES_MOM";case "PPI m/m"->"PPI_MOM";case "JOLTS Job Openings"->"JOLTS";
            case "Core PCE Price Index m/m"->"CORE_PCE_MOM";case "PCE Price Index m/m"->"PCE_MOM";
            case "Advance GDP q/q"->"GDP_ADVANCE";case "Prelim GDP q/q"->"GDP_SECOND";case "Final GDP q/q"->"GDP_THIRD";default->null;
        };
    }
    public static String unit(String indicator){return List.of("NFP","JOLTS").contains(indicator)?"COUNT":indicator.equals("UNEMPLOYMENT")?"PERCENT_LEVEL":indicator.startsWith("GDP_")?"PERCENT_QOQ_ANNUALIZED_SA":indicator.endsWith("YOY")?"PERCENT_YOY_NSA":"PERCENT_MOM_SA";}
    public static String forecast(String value,String unit) {
        if(value==null||value.isBlank())return null;var m=match("^([+-]?\\d+(?:\\.\\d+)?)([%KMB]?)$",value.strip());if(!m.matches())return null;
        String suffix=m.group(2).toUpperCase(Locale.ROOT);if(unit.equals("COUNT")&&suffix.equals("%")||!unit.equals("COUNT")&&!suffix.isEmpty()&&!suffix.equals("%"))return null;
        var multiplier=switch(suffix){case "K"->BigDecimal.valueOf(1000);case "M"->BigDecimal.valueOf(1000000);case "B"->BigDecimal.valueOf(1000000000);default->BigDecimal.ONE;};
        return new BigDecimal(m.group(1)).multiply(multiplier).stripTrailingZeros().toPlainString();
    }
}
