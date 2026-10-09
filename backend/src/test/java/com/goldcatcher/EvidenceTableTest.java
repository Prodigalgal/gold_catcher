package com.goldcatcher;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class EvidenceTableTest {
    @Test void factorsOnlyIdenticalPresentValuesWithoutChangingNumericOrNanosecondFacts(){
        var f=new TestSupport();String at="2026-10-08T00:00:00.000000123Z";var rows=f.mapper.createArrayNode();rows.addObject().put("at",at).put("state","READY").put("price","4000.00000000000000001");rows.addObject().put("at",at).put("state","READY").put("price","4001.00000000000000001");
        var result=EvidenceTable.packFactored(f.mapper,rows,List.of("at","state","price","missing"),List.of("at","state","price","missing"));var common=f.mapper.valueToTree(result.get("common"));assertThat(common.path("at").asText()).isEqualTo(at);assertThat(common.path("state").asText()).isEqualTo("READY");assertThat(common.has("price")).isFalse();assertThat(result.get("csv")).isEqualTo("4000.00000000000000001,\n4001.00000000000000001,");assertThat(result.get("columns")).isEqualTo(List.of("price","missing"));
    }
    @Test void preservesExactDecimalTextAndTimesAndDistinguishesNullFromZeroAndEmpty(){
        var f=new TestSupport();var rows=f.mapper.readTree("[{\"price\":\"4000.00000000000000001\",\"at\":\"2026-10-08T00:00:00.000000123Z\",\"missing\":null,\"zero\":\"0\",\"empty\":\"\"}]");
        var columns=List.of("price","at","missing","zero","empty");var result=EvidenceTable.pack(f.mapper,rows,columns);
        assertThat(result.get("columns")).isEqualTo(columns);assertThat(result.get("csv")).isEqualTo("4000.00000000000000001,2026-10-08T00:00:00.000000123Z,,0,\"\"");
    }
    @Test void quotesUntrustedCommaQuoteAndNewlineWithoutDroppingSourceText(){
        var f=new TestSupport();var rows=f.mapper.createArrayNode();rows.addObject().put("text","声明,\"引号\"\n新行😀").put("id","econ:CPI_MOM");
        assertThat(EvidenceTable.pack(f.mapper,rows,List.of("id","text")).get("csv")).isEqualTo("econ:CPI_MOM,\"声明,\"\"引号\"\"\n新行😀\"");
    }
}
