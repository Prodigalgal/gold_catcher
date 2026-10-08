package com.goldcatcher.network;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/** Source navigation profile verified against Edge 154 requests and production official endpoints. */
public final class BrowserSourceRequest {
    public static final String USER_AGENT="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36 Edg/154.0.0.0";
    private BrowserSourceRequest() {}
    public static HttpRequest.Builder navigation(URI uri,Duration timeout) {
        return HttpRequest.newBuilder(uri).timeout(timeout)
            .header("User-Agent",USER_AGENT)
            .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .header("Accept-Encoding","gzip, deflate, br, zstd")
            .header("Accept-Language","zh-CN")
            .header("Sec-CH-UA","\"Chromium\";v=\"154\", \"Microsoft Edge\";v=\"154\", \"Not A(Brand\";v=\"99\"")
            .header("Sec-CH-UA-Mobile","?0").header("Sec-CH-UA-Platform","\"Windows\"")
            .header("Sec-Fetch-Dest","document").header("Sec-Fetch-Mode","navigate")
            .header("Sec-Fetch-Site","none").header("Sec-Fetch-User","?1")
            .header("Upgrade-Insecure-Requests","1").header("Priority","u=0, i");
    }
}
