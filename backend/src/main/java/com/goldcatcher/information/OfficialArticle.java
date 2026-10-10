package com.goldcatcher.information;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Extracts the published article region; navigation, scripts and related stories are excluded. */
final class OfficialArticle {
    record Extracted(String text,String linkedMinutes,Instant releaseAt,String format) {}

    static boolean allowed(String source,URI uri) {
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null
            ||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
        Set<String> hosts=switch(source.startsWith("BLS_")?"BLS_NEWS":source) {
            case "FED_PRESS","FED_SPEECH" -> Set.of("www.federalreserve.gov","federalreserve.gov");
            case "BEA_NEWS" -> Set.of("www.bea.gov","bea.gov");
            case "ECB_PRESS" -> Set.of("www.ecb.europa.eu");
            case "BOE_NEWS","BOE_SPEECH" -> Set.of("www.bankofengland.co.uk");
            case "BOJ_NEWS" -> Set.of("www.boj.or.jp");
            case "EIA_PRESS","EIA_ENERGY","EIA_NEW" -> Set.of("www.eia.gov","eia.gov");
            case "BLS_NEWS" -> Set.of("www.bls.gov","bls.gov");
            default -> Set.of();
        };
        return hosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
    }

    static Extracted extract(String source,byte[] bytes,String link) {
        if(bytes.length>=4&&bytes[0]=='P'&&bytes[1]=='K'&&link.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            try{return new Extracted(SpreadsheetArticle.extract(bytes),null,null,"XLSX_SOURCE_CELLS");}
            catch(IllegalStateException failure){throw failure;}
            catch(Exception failure){throw new IllegalStateException("ARTICLE_XLSX_PARSE_FAILED");}
        }
        if(bytes.length>=5&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) {
            try(var pdf=Loader.loadPDF(bytes)) {
                if(!pdf.getCurrentAccessPermission().canExtractContent())throw new IllegalStateException("ARTICLE_PDF_EXTRACTION_RESTRICTED");
                if(pdf.getNumberOfPages()>100)throw new IllegalStateException("ARTICLE_PDF_PAGE_LIMIT");
                var stripper=new PDFTextStripper();stripper.setSortByPosition(true);String text=stripper.getText(pdf).strip();
                if(text.length()<80)throw new IllegalStateException("ARTICLE_PDF_TEXT_MISSING");
                return new Extracted(text,null,null,"PDF_TEXT");
            }catch(java.io.IOException failure){throw new IllegalStateException("ARTICLE_PDF_PARSE_FAILED");}
        }
        Document document=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),link);
        Element article=switch(source.startsWith("BLS_")?"BLS_NEWS":source) {
            case "FED_PRESS","FED_SPEECH" -> document.selectFirst("#article");
            case "BEA_NEWS" -> document.selectFirst("article .field--name-body");
            case "ECB_PRESS" -> document.selectFirst("main > .section");
            case "BOE_NEWS","BOE_SPEECH" -> document.selectFirst(".page-content");
            case "BOJ_NEWS" -> document.selectFirst("#contents, #main-content");
            case "EIA_ENERGY" -> document.selectFirst(".tie-article");
            case "EIA_PRESS" -> document.selectFirst(".pagecontent .main_col");
            case "BLS_NEWS" -> document.selectFirst("#bodytext, .news-release");
            default -> null;
        };
        if(article==null)throw new IllegalStateException("ARTICLE_FORMAT_CHANGED");
        String minutes=null;
        if(source.equals("FED_PRESS"))for(Element anchor:article.select("a[href]")) {
            String candidate=anchor.absUrl("href");
            if(candidate.matches("https://www\\.federalreserve\\.gov/monetarypolicy/fomcminutes[0-9]{8}\\.htm")) {
                minutes=candidate;break;
            }
        }
        article.select("script,style,nav,aside,form,.share,.article__time,.releaseTime,.content-box,.footnotes").remove();
        // Minified HTML omits whitespace between rendered blocks; retain paragraph and table boundaries.
        for(var block:article.select("p,div,section,article,h1,h2,h3,h4,h5,h6,li,table,tr,blockquote,pre,ul,ol")) {
            block.prependText("\n");block.appendText("\n");
        }
        for(var cell:article.select("td,th")){cell.prependText(" ");cell.appendText(" ");}
        String text=article.wholeText().replace('\u00a0',' ').replaceAll("[\\t ]+"," ")
            .replaceAll(" *\\r?\\n *","\n").replaceAll("\\n{3,}","\n\n").trim();
        if(text.length()<80)throw new IllegalStateException("ARTICLE_CONTENT_TOO_SHORT");
        Instant releaseAt=source.startsWith("BLS_")?blsReleaseTime(text):null;
        return new Extracted(text,minutes,releaseAt,"HTML_TEXT");
    }
    private static Instant blsReleaseTime(String text) {
        var match=java.util.regex.Pattern.compile("(?i)(\\d{1,2}):(\\d{2})\\s*([ap])\\.?m\\.?\\s*\\(ET\\)\\s*[,;]?\\s*[A-Za-z]+,\\s*([A-Za-z]+ \\d{1,2}, 20\\d{2})")
            .matcher(text.substring(0,Math.min(text.length(),2000)));
        if(!match.find())return null;
        try {
            int hour=Integer.parseInt(match.group(1))%12+(match.group(3).equalsIgnoreCase("p")?12:0);
            var date=LocalDate.parse(match.group(4),DateTimeFormatter.ofPattern("MMMM d, yyyy",Locale.US));
            return date.atTime(hour,Integer.parseInt(match.group(2))).atZone(ZoneId.of("America/New_York")).toInstant();
        }catch(DateTimeException invalid){return null;}
    }

    static String excerpt(String text,int maximum) {
        // Prefer paragraphs carrying macro facts, including numeric values, over headings and introductions.
        var paragraphs=text.split("\\n+");String selected=java.util.Arrays.stream(paragraphs)
            .filter(p->p.length()>60&&p.matches("(?is).*(inflation|interest rate|economic|employment|price index|GDP|personal income|monetary policy|outlook|percent).*"))
            .limit(3).collect(java.util.stream.Collectors.joining(" "));
        if(selected.isBlank())selected=text.replaceAll("\\s+"," ");
        int points=selected.codePointCount(0,selected.length());
        return points<=maximum?selected:selected.substring(0,selected.offsetByCodePoints(0,maximum))+" [原文摘录，非全文]";
    }
}
