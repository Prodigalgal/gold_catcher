package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.analysis.AnalysisTypes.Metric;
import java.util.*;
import org.junit.jupiter.api.Test;

class EconomicExtractorTest {
    private Map<String,Metric> fields(List<Metric> values){Map<String,Metric> result=new HashMap<>();values.forEach(v->result.put(v.indicator(),v));return result;}
    @Test void distinguishesMonthlyAnnualCoreAndPriorFigures(){
        var result=fields(EconomicExtractor.extract("BLS_CPI","CPI", """
            Embargoed October 14, 2026. CONSUMER PRICE INDEX - SEPTEMBER 2026
            The Consumer Price Index for All Urban Consumers (CPI-U) decreased 0.2 percent on a seasonally
            adjusted basis in September after rising 0.3 percent in August.
            Over the last 12 months, the all items index increased 2.8 percent before seasonal adjustment.
            The index for all items less food and energy rose 0.1 percent after increasing 0.2 percent in August.
            The all items less food and energy index rose 3.0 percent over the year.
            """));
        assertThat(result).hasSize(4);assertThat(result.get("CPI_MOM").actual()).isEqualTo("-0.2");assertThat(result.get("CPI_MOM").previous()).isEqualTo("0.3");
        assertThat(result.get("CPI_YOY").actual()).isEqualTo("2.8");assertThat(result.get("CORE_CPI_MOM").actual()).isEqualTo("0.1");
        assertThat(result.get("CORE_CPI_YOY").actual()).isEqualTo("3");assertThat(result.get("CPI_MOM").period()).isEqualTo("2026-09");
    }
    @Test void unchangedIsExplicitZeroButMissingNumbersAreNotZero(){
        var result=fields(EconomicExtractor.extract("BLS_CPI","CPI","CONSUMER PRICE INDEX - SEPTEMBER 2026 The Consumer Price Index for All Urban Consumers (CPI-U) was unchanged. There was a 2026 methodology update."));
        assertThat(result.get("CPI_MOM").actual()).isEqualTo("0");assertThat(result).doesNotContainKey("CORE_CPI_MOM");
        assertThat(EconomicExtractor.extract("BLS_CPI","CPI","The gasoline index rose 3.5 percent.")).isEmpty();
    }
    @Test void payrollParenthesesAndRevisionsRetainSigns(){
        var result=fields(EconomicExtractor.extract("BLS_EMPLOYMENT","Jobs", """
            THE EMPLOYMENT SITUATION - SEPTEMBER 2026
            Both nonfarm payroll employment (-29,000) and the unemployment rate (4.2 percent) changed little.
            Average hourly earnings for all employees on private nonfarm payrolls rose by 12 cents, or 0.4 percent.
            The change in total nonfarm payroll employment for July was revised down by 2,000, from +19,000 to +17,000,
            and the change for August was revised up by 3,000, from -25,000 to -22,000.
            """));
        assertThat(result.get("NFP").actual()).isEqualTo("-29000");assertThat(result.get("NFP").revisions()).hasSize(2);
        assertThat(result.get("NFP").revisions().get(1).delta()).isEqualTo("3000");assertThat(result.get("UNEMPLOYMENT").actual()).isEqualTo("4.2");
        assertThat(result.get("WAGES_MOM").actual()).isEqualTo("0.4");
    }
    @Test void joltsUsesTablePrecisionInsteadOfRoundedHeadline(){
        var result=fields(EconomicExtractor.extract("BLS_JOLTS","JOLTS", """
            JOB OPENINGS AND LABOR TURNOVER � AUGUST 2026
            The number of job openings decreased to 7.1 million in August.
            LEVELS BY INDUSTRY (in thousands) Total 6,919 7,335 7,079 5,145 5,146 5,192
            """));
        assertThat(result.get("JOLTS").actual()).isEqualTo("7079000");assertThat(result.get("JOLTS").previous()).isEqualTo("7335000");
        assertThat(result.get("JOLTS").precision()).isEqualTo("OFFICIAL_TABLE_THOUSANDS");
    }
    @Test void pceDoesNotReadConsumptionSpendingAsPriceInflation(){
        var result=fields(EconomicExtractor.extract("BEA_NEWS","Personal Income and Outlays, August 2026", """
            Personal consumption expenditures (PCE) increased 0.9 percent.
            From the preceding month, the PCE price index for August increased 0.3 percent.
            Excluding food and energy, the PCE price index increased 0.2 percent.
            From the same month one year ago, the PCE price index for August increased 3.4 percent.
            Excluding food and energy, the PCE price index increased 3.0 percent from one year ago.
            """));
        assertThat(result).hasSize(4);assertThat(result.get("PCE_MOM").actual()).isEqualTo("0.3");assertThat(result.get("CORE_PCE_MOM").actual()).isEqualTo("0.2");
        assertThat(result.get("CORE_PCE_YOY").actual()).isEqualTo("3");
    }
    @Test void gdpEstimateDoesNotDependOnTheQuarterName(){
        var result=EconomicExtractor.extract("BEA_NEWS","GDP (Advance Estimate), Third Quarter 2026","Real gross domestic product (GDP) decreased at an annual rate of 1.2 percent in the third quarter of 2026.").getFirst();
        assertThat(result.indicator()).isEqualTo("GDP_ADVANCE");assertThat(result.period()).isEqualTo("2026-Q3");assertThat(result.actual()).isEqualTo("-1.2");assertThat(result.unit()).isEqualTo("PERCENT_QOQ_ANNUALIZED_SA");
    }
    @Test void unitAndUnavailableForecastsRemainDistinct(){
        assertThat(EconomicExtractor.forecast("7.15M","COUNT")).isEqualTo("7150000");assertThat(EconomicExtractor.forecast("-25K","COUNT")).isEqualTo("-25000");
        assertThat(EconomicExtractor.forecast("0.2%","PERCENT_MOM_SA")).isEqualTo("0.2");assertThat(EconomicExtractor.forecast("","COUNT")).isNull();
        assertThat(EconomicExtractor.forecast("3.5%","COUNT")).isNull();assertThat(EconomicExtractor.forecast("1M","PERCENT_MOM_SA")).isNull();assertThat(EconomicExtractor.indicator("ADP Weekly Employment Change")).isNull();
    }
}
