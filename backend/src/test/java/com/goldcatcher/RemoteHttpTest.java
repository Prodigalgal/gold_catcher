package com.goldcatcher;

import static org.assertj.core.api.Assertions.*;
import com.github.luben.zstd.Zstd;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class RemoteHttpTest {
    private final byte[] plain=("gold source "+"data ".repeat(500)).getBytes(StandardCharsets.UTF_8);
    @Test void decodesAllAdvertisedBrowserContentEncodingsAndEmptyConditionalResponses() throws Exception {
        var gzip=new ByteArrayOutputStream();try(var encoder=new GZIPOutputStream(gzip)){encoder.write(plain);}
        var deflate=new ByteArrayOutputStream();try(var encoder=new DeflaterOutputStream(deflate)){encoder.write(plain);}
        byte[] brotli=HexFormat.of().parseHex("1bcf09f8a541c2c8e8a4208cbc9605b4608a8dd60b002017");
        assertThat(RemoteHttp.decode(gzip.toByteArray(),"gzip",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(deflate.toByteArray(),"deflate",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(brotli,"br",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(Zstd.compress(plain),"zstd",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(new byte[0],"gzip",3000)).isEmpty();
    }
    @Test void capsDecompressedSizeAndRejectsUnsupportedOrCorruptResponses() throws Exception {
        assertThatThrownBy(()->RemoteHttp.decode(Zstd.compress(plain),"zstd",100)).hasMessage("RESPONSE_TOO_LARGE");
        assertThatThrownBy(()->RemoteHttp.decode(plain,"gzip",3000)).hasMessage("CONTENT_DECOMPRESSION_FAILED");
        assertThatThrownBy(()->RemoteHttp.decode(plain,"future-codec",3000)).hasMessage("CONTENT_ENCODING_UNSUPPORTED");
    }
}
