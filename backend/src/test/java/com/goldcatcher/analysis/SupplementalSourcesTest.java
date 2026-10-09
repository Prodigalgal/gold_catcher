package com.goldcatcher.analysis;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;import java.math.BigDecimal;import java.time.*;import org.junit.jupiter.api.Test;
class SupplementalSourcesTest {
    TestSupport f=new TestSupport();
    @Test void issuerDatesStayFieldSpecificAndMissingOrFutureValuesFailClosed(){
        String html="";for(String field:new String[]{"ounces","tonnes","sharesOutstanding","totalNetAssetsFundLevel"}){String label=switch(field){case "ounces"->"Ounces in Trust";case "tonnes"->"Tonnes in Trust";case "sharesOutstanding"->"Shares Outstanding";default->"Net Assets of Fund";};html+="<div data-id='keyFundFacts-row-"+field+"'><span data-id='keyFundFacts-"+field+"-label'>"+label+"</span><span data-id='keyFundFacts-"+field+"-data'>"+(field.equals("tonnes")?"463.87":"14,913,604.92")+"</span><span data-id='keyFundFacts-"+field+"-asOf'>as of "+(field.equals("tonnes")?"Oct 06, 2026":"Oct 07, 2026")+"</span></div>";}
        var rows=SupplementalSources.parseIau(html,f.clock.instant(),"h");assertThat(rows).hasSize(2);assertThat(rows.getFirst().period()).isEqualTo("2026-10-06");assertThat(rows.getFirst().values()).containsOnlyKeys("tonnes");assertThat(rows.getFirst().values()).containsEntry("tonnes","463.87");
        String future=html.replace("Oct 06, 2026","Oct 09, 2026"),missing=html.replace("463.87","AWAITING");assertThatThrownBy(()->SupplementalSources.parseIau(future,f.clock.instant(),"h")).hasMessage("IAU_ASOF_FUTURE");assertThatThrownBy(()->SupplementalSources.parseIau(missing,f.clock.instant(),"h")).hasMessage("IAU_NUMBER_INVALID");
    }
    @Test void longCensusIdentifiersPersistWithoutLosingFirstKnowledgeOrRevisionHistory() throws Exception {
        var sources=new SupplementalSources(f.settings,new RemoteHttp(),f.mapper,f.store,null,f.clock,new EventHub());
        String xml="<rss><channel><item><title>Retail and Food Services</title><description>August 2026: +0.5 % Change</description><link>https://www.census.gov/retail/</link><pubDate>Wed, 07 Oct 2026 10:00:00 -0400</pubDate></item></channel></rss>";
        var rows=SupplementalSources.parseCensus(xml.getBytes(),f.clock.instant(),"first");sources.saveObservations(rows);Instant first=f.clock.instant();f.clock.advance(60000);
        sources.saveObservations(SupplementalSources.parseCensus(xml.getBytes(),f.clock.instant(),"same"));assertThat(sources.view(f.clock.instant()).observations().getFirst().receivedAt()).isEqualTo(first);
        sources.saveObservations(SupplementalSources.parseCensus(xml.replace("+0.5","+0.6").getBytes(),f.clock.instant(),"revised"));
        assertThat(sources.view(first).observations().getFirst().values()).containsEntry("reportedChangePct","+0.5");assertThat(sources.view(f.clock.instant()).observations().getFirst().values()).containsEntry("reportedChangePct","+0.6");assertThat(f.store.list("public-observation-version",SupplementalSources.Observation.class,10)).hasSize(2);
    }
    @Test void unrevisedClaimsCannotBorrowContinuedClaimsRevision(){
        String text="8:30 A.M. (Eastern) Thursday, October 8, 2026 SEASONALLY ADJUSTED DATA In the week ending October 3, the advance figure for seasonally adjusted initial claims was 197,000, a decrease of 2,000. The previous week's level was unrevised at 199,000. The 4-week moving average was 202,000. The advance number for seasonally adjusted insured unemployment during the week ending September 26 was 1,716,000, an increase of 17,000. The previous week's level was revised down by 2,000 from 1,701,000 to 1,699,000. UNADJUSTED DATA";
        var rows=SupplementalSources.parseClaims(text,Instant.parse("2026-10-09T00:00:00Z"),"hash");assertThat(rows).hasSize(2);assertThat(rows.getFirst().values()).containsEntry("previous","199000").containsEntry("previousBeforeRevision","199000");
    }
    @Test void depthConvertsContractsExactlyAndRejectsCrossedOrUnorderedBooks(){
        var at=f.clock.instant();var json=f.mapper.readTree("{\"id\":123,\"current\":"+at.getEpochSecond()+".123,\"update\":"+at.getEpochSecond()+".120,\"bids\":[{\"p\":\"4000.01\",\"s\":120},{\"p\":\"4000\",\"s\":80}],\"asks\":[{\"p\":\"4000.02\",\"s\":100}]}");
        var d=SupplementalSources.parseDepth(json,new BigDecimal("0.0001"),at.plusSeconds(1));
        assertThat(d.bids().getFirst().get("quantity")).isEqualTo("0.012");assertThat(d.imbalance()).startsWith("0.333333");assertThat(d.responseAt().getNano()).isEqualTo(123000000);
        ((tools.jackson.databind.node.ObjectNode)json.path("asks").path(0)).put("p","4000.00");assertThatThrownBy(()->SupplementalSources.parseDepth(json,new BigDecimal("0.0001"),at)).hasMessage("DEPTH_CROSSED_OR_EMPTY");
    }
    @Test void tradeSampleDeduplicatesRejectsWrongProductAndKeepsSignedNativeQuantity(){
        var row=f.mapper.createObjectNode().put("id","1").put("contract","XAU_USDT").put("create_time",f.clock.instant().getEpochSecond()+".100").put("size","-120").put("price","4000.02");var list=f.mapper.createArrayNode().add(row);
        var t=SupplementalSources.parseTrades(list,new BigDecimal("0.0001"),f.clock.instant().plusSeconds(1));assertThat(t.volume()).isEqualTo("0.012");assertThat(t.vwap()).isEqualTo("4000.02");assertThat(t.rows().getFirst().get("signedContracts")).isEqualTo("-120");assertThat(t.coverage()).contains("NOT_FIXED_TIME_WINDOW");
        list.add(row.deepCopy());assertThatThrownBy(()->SupplementalSources.parseTrades(list,new BigDecimal("0.0001"),f.clock.instant().plusSeconds(1))).hasMessage("TRADE_ID_DUPLICATE");
    }
    @Test void cotKeepsTuesdayPeriodAndComputesNetWithDecimalArithmetic(){
        var json=f.mapper.readTree("[{\"cftc_contract_market_code\":\"088691\",\"report_date_as_yyyy_mm_dd\":\"2026-09-29T00:00:00.000\",\"open_interest_all\":\"406456\",\"m_money_positions_long_all\":\"131711\",\"m_money_positions_short_all\":\"11393\",\"m_money_positions_spread\":\"36294\",\"prod_merc_positions_long\":\"18200\",\"prod_merc_positions_short\":\"39463\"}]");
        var o=SupplementalSources.parseCot(json,f.clock.instant(),"hash").getFirst();assertThat(o.values()).containsEntry("managedNet","120318");assertThat(o.period()).isEqualTo("2026-09-29");assertThat(o.publishedAt()).isNull();
    }
    @Test void claimsExtractSeasonallyAdjustedCountsRevisionsAndEmbargo(){
        String text="8:30 A.M. (Eastern) Thursday, October 8, 2026 SEASONALLY ADJUSTED DATA In the week ending October 3, the advance figure for seasonally adjusted initial claims was 197,000, a decrease of 2,000 from the previous week's revised level. The previous week's level was revised up by 2,000 from 197,000 to 199,000. The advance number for seasonally adjusted insured unemployment during the week ending September 26 was 1,716,000, an increase of 17,000 from the previous week's revised level. The previous week's level was revised down by 2,000 from 1,701,000 to 1,699,000. UNADJUSTED DATA";
        var rows=SupplementalSources.parseClaims(text,Instant.parse("2026-10-09T00:00:00Z"),"hash");assertThat(rows).hasSize(2);assertThat(rows.getFirst().values()).containsEntry("actual","197000").containsEntry("previous","199000").containsEntry("previousBeforeRevision","197000");assertThat(rows.get(1).values()).containsEntry("actual","1716000");assertThat(rows.getFirst().period()).isEqualTo("2026-10-03");assertThat(rows.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-08T12:30:00Z"));
        assertThatThrownBy(()->SupplementalSources.parseClaims(text,Instant.parse("2026-10-08T12:29:00Z"),"hash")).hasMessage("CLAIMS_EMBARGOED");
    }
    @Test void censusKeepsReportedChangeDefinitionAndRejectsFuturePublicationAndXxe() throws Exception {
        String xml="<rss><channel><item><title>Wholesale Inventories</title><description>August 2026: +0.5 % Change in Inventories July 2026 (r): +1.4 % Change</description><link>https://www.census.gov/wholesale/</link><pubDate>Thu, 08 Oct 2026 10:00:00 -0400</pubDate></item></channel></rss>";
        assertThat(SupplementalSources.parseCensus(xml.getBytes(),Instant.parse("2026-10-08T13:59:00Z"),"h")).isEmpty();var row=SupplementalSources.parseCensus(xml.getBytes(),Instant.parse("2026-10-09T00:00:00Z"),"h").getFirst();assertThat(row.unit()).isEqualTo("PERCENT_REPORTED_CHANGE");assertThat(row.values()).containsEntry("reportedChangePct","+0.5");
        assertThatThrownBy(()->SupplementalSources.parseCensus("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>".getBytes(),f.clock.instant(),"h")).isInstanceOf(Exception.class);
    }
    @Test void futureAuctionNeverContainsKnownResultOrInventedPublicationTimestamp(){
        var json=f.mapper.readTree("{\"data\":[{\"cusip\":\"abc\",\"security_type\":\"Note\",\"security_term\":\"10-Year\",\"announcemt_date\":\"2026-10-06\",\"auction_date\":\"2026-10-09\",\"closing_time_comp\":\"01:00 PM\",\"high_yield\":\"5.3\",\"bid_to_cover_ratio\":\"2.7\",\"offering_amt\":\"42000000000\"}]}");
        var row=SupplementalSources.parseAuctions(json,Instant.parse("2026-10-09T12:00:00Z"),"h").getFirst();assertThat(row.values()).doesNotContainKeys("high_yield","bid_to_cover_ratio");assertThat(row.values()).containsEntry("competitiveCloseUtc","2026-10-09T17:00:00Z");assertThat(row.publishedAt()).isNull();
    }
}
