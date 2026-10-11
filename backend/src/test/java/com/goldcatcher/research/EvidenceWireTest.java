package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
class EvidenceWireTest {
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
}
