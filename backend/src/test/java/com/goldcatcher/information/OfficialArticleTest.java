package com.goldcatcher.information;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OfficialArticleTest {
    @Test void extractsOnlyOfficialRegionAndFollowsKnownMinutesDocument() {
        String html="<nav>unrelated headline</nav><div id='article'><p>The monetary policy committee considered inflation and employment and published the minutes of its latest meeting.</p><a href='/monetarypolicy/fomcminutes20260916.htm'>HTML minutes</a><script>secret command</script></div><aside>Related news</aside>";
        var article=OfficialArticle.extract("FED_PRESS",html.getBytes(StandardCharsets.UTF_8),"https://www.federalreserve.gov/newsevents/pressreleases/monetary20261007a.htm");
        assertThat(article.text()).doesNotContain("unrelated","secret","Related news");
        assertThat(article.linkedMinutes()).isEqualTo("https://www.federalreserve.gov/monetarypolicy/fomcminutes20260916.htm");
        assertThat(article.releaseAt()).isNull();
    }
    @Test void allowedHostsRemainExactAndRejectRedirectsToOtherServices() {
        assertThat(OfficialArticle.allowed("BLS_CPI",URI.create("https://www.bls.gov/news.release/a.htm"))).isTrue();
        for(String url:new String[]{"http://www.bls.gov/a","https://www.bls.gov.evil.example/a","https://user@www.bls.gov/a","https://www.bls.gov:8443/a","https://127.0.0.1/a","https://www.bea.gov/a"})
            assertThat(OfficialArticle.allowed("BLS_CPI",URI.create(url))).isFalse();
    }
    @Test void minifiedBlocksAndTableCellsNeverMergeWordsOrFinancialValues() {
        String html="<div id='article'><h2>Inflation</h2><p>The price index increased 0.4 percent while employment conditions remained stable in the latest monthly figures.</p><table><tr><td>110.2</td><td>112.3</td></tr></table><h2>Employment</h2><p>Interest rate policy remains sensitive to wage increases and the economic outlook.</p></div>";
        var text=OfficialArticle.extract("FED_PRESS",html.getBytes(StandardCharsets.UTF_8),"https://www.federalreserve.gov/a").text();
        assertThat(text).contains("Inflation\n","110.2 112.3","0.4 percent").doesNotContain("110.2112.3","InflationThe");
    }
    @Test void declaredBlsPublicationUsesEasternDaylightSaving() {
        for(var entry:java.util.Map.of("October 8, 2026","2026-10-08T12:30:00Z","November 12, 2026","2026-11-12T13:30:00Z").entrySet()) {
            String html="<pre id='bodytext'>Transmission embargoed until 8:30 a.m. (ET) Thursday, "+entry.getKey()+"\nThe employment and inflation figures include revised values from earlier periods.</pre>";
            assertThat(OfficialArticle.extract("BLS_EMPLOYMENT",html.getBytes(StandardCharsets.UTF_8),"https://www.bls.gov/a").releaseAt()).isEqualTo(Instant.parse(entry.getValue()));
        }
    }
    @Test void pdfSpeechIsExtractedFromItsTextLayerInsteadOfHtmlSelectors() throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(50,700);content.showText("Monetary policy and inflation expectations reflect changes in interest rates and economic conditions.");content.endText();
            }
            pdf.save(output);
        }
        var result=OfficialArticle.extract("ECB_PRESS",output.toByteArray(),"https://www.ecb.europa.eu/press/speech.pdf");
        assertThat(result.format()).isEqualTo("PDF_TEXT");assertThat(result.text()).contains("inflation expectations");
    }
}
