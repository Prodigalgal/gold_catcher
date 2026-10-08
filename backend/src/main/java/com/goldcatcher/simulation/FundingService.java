package com.goldcatcher.simulation;

import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class FundingService {
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;
    private final SimulationLedger ledger;private final DocumentStore store;private final Clock clock;private final EventHub events;
    private volatile Instant lastSuccess;private volatile String state="NOT_FETCHED",error;
    public FundingService(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,SimulationLedger ledger,DocumentStore store,Clock clock,EventHub events) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.ledger=ledger;this.store=store;this.clock=clock;this.events=events;
    }
    @Scheduled(fixedDelay=60000,initialDelay=3000) void fetch() {
        if(!settings.enabled("GOLD_MARKET_ENABLED"))return;
        try {
            var url=settings.text("GOLD_GATE_HTTP_URL","https://api.gateio.ws/api/v4")+"/futures/usdt/funding_rate?contract=XAU_USDT&limit=200";
            var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build(),150_000);
            if(response.statusCode()!=200)throw new IllegalStateException("HTTP_"+response.statusCode());
            var data=mapper.readTree(response.body());if(!data.isArray())throw new IllegalStateException("FUNDING_RESPONSE_ERROR");
            List<SimulationLedger.FundingRate> rates=new ArrayList<>();
            for(var item:data) {
                var at=Instant.ofEpochSecond(Long.parseLong(item.path("t").asText()));
                var rate=new BigDecimal(item.path("r").asText());if(at.isAfter(clock.instant())||rate.abs().compareTo(BigDecimal.ONE)>=0)throw new IllegalStateException("INVALID_FUNDING_RECORD");
                rates.add(new SimulationLedger.FundingRate(at,rate,clock.instant()));
            }
            store.save("funding-history","XAU_USDT",rates);ledger.acceptFundingHistory(rates);
            lastSuccess=clock.instant();state="CURRENT";error=null;events.changed();
        }catch(Exception failure){state="FETCH_FAILED";error=failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]+")?failure.getMessage():"FUNDING_FETCH_FAILED";events.changed();}
    }
    public Map<String,Object> status() {
        var status=new LinkedHashMap<String,Object>();status.put("state",state);status.put("lastSuccess",lastSuccess);status.put("error",error);
        status.put("history",store.find("funding-history","XAU_USDT",tools.jackson.databind.JsonNode.class).orElse(mapper.createArrayNode()));
        status.put("pricingMethod","NEAR_BOUNDARY_MARK_ASSUMPTION");return status;
    }
}
