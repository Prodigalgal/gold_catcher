package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ResearchLanesTest {
    @Test void occupiedDeepLaneDoesNotReserveFastLaneForTheSameProduct(){
        try(var lanes=new ResearchLanes();var deep=lanes.acquire("XAU_USDT","LONG");var fast=lanes.acquire("XAU_USDT","SHORT")){
            assertThat(lanes.available("XAU_USDT","SHORT")).isFalse();
            assertThat(lanes.available("GATE:XAUUSD","SHORT")).isTrue();
            assertThatThrownBy(()->lanes.acquire("XAU_USDT","LONG")).hasMessage("RESEARCH_ALREADY_RUNNING");
            assertThat(lanes.rounds("SHORT")).isNotSameAs(lanes.rounds("LONG"));
            assertThat(lanes.calls("SHORT")).isNotSameAs(lanes.calls("LONG"));
        }
    }
    @Test void resizingDoesNotCancelActiveWorkOrCreateExtraPermits(){
        try(var lanes=new ResearchLanes()){
            var first=lanes.acquire("first","LONG");var second=lanes.acquire("second","LONG");
            lanes.configure(8,1);assertThat(lanes.available("third","LONG")).isFalse();
            first.close();first.close();assertThat(lanes.available("third","LONG")).isFalse();
            second.close();assertThat(lanes.available("third","LONG")).isTrue();
            try(var third=lanes.acquire("third","LONG")){assertThat(lanes.available("fourth","LONG")).isFalse();}
        }
    }
}
