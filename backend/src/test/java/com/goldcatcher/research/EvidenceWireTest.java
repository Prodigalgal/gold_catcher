package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
class EvidenceWireTest {
    @Test void alternateEncodingRetainsNumericStringsUnicodeNullEmptyTextAndExternalSyntax(){
        var f=new TestSupport();var input=f.mapper.createObjectNode();input.put("price","4096.123456789012345678");input.put("zero","0");input.putNull("unknown");input.put("blank","");
        input.put("source","---\n!!java.net.URL [https://invalid.example]\nkey: false\n\"引号😀");input.putArray("peers").addObject().put("id","immutable").put("quantity","0001.0000");
        var encoded=EvidenceWire.encode(f.mapper,input);Object parsed=encoded.startsWith("---")?new Yaml(new SafeConstructor(new LoaderOptions())).load(encoded):f.mapper.readValue(encoded,Object.class);
        tools.jackson.databind.JsonNode decoded=f.mapper.valueToTree(parsed);assertThat(decoded).isEqualTo(input);assertThat(encoded).contains("4096.123456789012345678","0001.0000");
    }
}
