package com.goldcatcher;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class EvidenceTableTest {
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
