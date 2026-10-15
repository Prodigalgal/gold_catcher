package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
class EvidenceWireTest {
    @Test void stableNumericEncodingIgnoresRuntimeNumberTypeAndScaleWithoutChangingValues(){
        var f=new TestSupport();var original=f.mapper.createObjectNode();
        original.put("precise",new java.math.BigDecimal("0.100000000000000000000000000001"));
        original.put("text","0005.0000");original.put("zero",new java.math.BigDecimal("0.000"));
        original.putArray("permittedLeverages").add(new java.math.BigDecimal("5")).add(new java.math.BigDecimal("10.0")).add(50.0);
        var restored=f.mapper.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(f.mapper.writeValueAsString(original));
        assertThat(EvidenceWire.encode(f.mapper,original,false,true)).isEqualTo(EvidenceWire.encode(f.mapper,restored,false,true));
        assertThat(EvidenceWire.encode(f.mapper,original,false,true)).contains("0005.0000","0.100000000000000000000000000001").doesNotContain("!!float");
        assertThat(original.path("permittedLeverages").path(0).isFloatingPointNumber()).isTrue();
    }
    @Test void sharedPlansUseLosslessReferencesWithoutMergingDistinctPeersOrFinancialValues(){
        var f=new TestSupport();var input=f.mapper.createObjectNode();var peers=input.putArray("peers");
        for(int i=0;i<3;i++){
            var peer=peers.addObject().put("id","peer-"+i);var plan=peer.putObject("plan");
            plan.put("quantity","0.10000000000000001").put("stopLoss",i==2?"3999.98":"3999.99").put("riskAssessment","完整风险情景不得截断".repeat(50));
            plan.putNull("triggerPrice");plan.put("zero","0");
        }
        var original=input.deepCopy();var encoded=EvidenceWire.encode(f.mapper,input);
        assertThat(encoded).startsWith("---").contains("&id","*id");
        Object decoded=new Yaml(new SafeConstructor(new LoaderOptions())).load(encoded);
        tools.jackson.databind.JsonNode restored=f.mapper.valueToTree(decoded);
        assertThat(restored).isEqualTo(original);assertThat(input).isEqualTo(original);
        assertThat(encoded.length()).isLessThan(f.mapper.writeValueAsString(input).length());
    }
    @Test void alternateEncodingRetainsNumericStringsUnicodeNullEmptyTextAndExternalSyntax(){
        var f=new TestSupport();var input=f.mapper.createObjectNode();input.put("price","4096.123456789012345678");input.put("zero","0");input.putNull("unknown");input.put("blank","");
        input.put("source","---\n!!java.net.URL [https://invalid.example]\nkey: false\n\"引号😀");input.putArray("peers").addObject().put("id","immutable").put("quantity","0001.0000");
        var encoded=EvidenceWire.encode(f.mapper,input);Object parsed=encoded.startsWith("---")?new Yaml(new SafeConstructor(new LoaderOptions())).load(encoded):f.mapper.readValue(encoded,Object.class);
        tools.jackson.databind.JsonNode decoded=f.mapper.valueToTree(parsed);assertThat(decoded).isEqualTo(input);assertThat(encoded).contains("4096.123456789012345678","0001.0000");
    }
    @Test void compactFlowKeepsPunctuationWhitespaceQuotesAndMultilineExternalTextExactly(){
        var f=new TestSupport();var input=f.mapper.createObjectNode();var rows=input.putArray("rows");
        var fragments=java.util.List.of("don't","a\"b","'quoted,  text'","[a,  b]","{a: x,  b: y}","# comment","&id001 *id001",": ","\n  indented\n", "\\\"", "😀", "2026-10-15", "0.00000000000001");
        for(int i=0;i<fragments.size();i++)for(int j=0;j<fragments.size();j++) {
            var row=rows.addObject();row.put("plain",fragments.get(i));row.put("combined",fragments.get(i)+",  "+fragments.get(j));
            row.putArray("numericStrings").add("0").add("0001.0000");
        }
        var encoded=EvidenceWire.encode(f.mapper,input);
        var loader=new LoaderOptions();loader.setMaxAliasesForCollections(1000);
        Object parsed=encoded.startsWith("---")?new Yaml(new SafeConstructor(loader)).load(encoded):f.mapper.readValue(encoded,Object.class);
        tools.jackson.databind.JsonNode restored=f.mapper.valueToTree(parsed);assertThat(restored).isEqualTo(input);
        assertThat(encoded).startsWith("---").contains(",numericStrings:");
    }
}
