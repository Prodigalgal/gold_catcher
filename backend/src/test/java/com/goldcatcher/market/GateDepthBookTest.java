package com.goldcatcher.market;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class GateDepthBookTest {
    @Test void matchingBaseBufferedRangeAbsoluteSizesAndGapAreVerified(){
        var f=new TestSupport();var book=new GateDepthBook();
        assertThat(book.update(f.mapper.readTree("{\"s\":\"XAU_USDT\",\"U\":10,\"u\":12,\"t\":1791417600000,\"b\":[{\"p\":\"100\",\"s\":\"0\"}],\"a\":[]}"),f.clock.instant())).isTrue();
        book.bootstrap(f.mapper.readTree("{\"id\":10,\"bids\":[{\"p\":\"100\",\"s\":\"200\"},{\"p\":\"99\",\"s\":\"300\"}],\"asks\":[{\"p\":\"101\",\"s\":\"400\"}]}"),new BigDecimal(".001"),f.clock.instant());
        var view=book.view(f.clock.instant());assertThat(view.get("state")).isEqualTo("SEQUENCED");assertThat(view.get("sequence")).isEqualTo(12L);
        assertThat(f.mapper.valueToTree(view.get("bids")).path(0).path("quantity").asText()).isEqualTo("0.300");
        assertThat(book.update(f.mapper.readTree("{\"s\":\"XAU_USDT\",\"U\":14,\"u\":15,\"t\":1791417600100,\"b\":[],\"a\":[]}"),f.clock.instant())).isTrue();
        assertThat(book.view(f.clock.instant()).get("state")).isEqualTo("SEQUENCE_GAP");assertThat(book.view(f.clock.instant()).get("fresh")).isEqualTo(false);
    }
}
