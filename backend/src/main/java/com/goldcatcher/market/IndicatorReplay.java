package com.goldcatcher.market;

import static com.goldcatcher.market.MarketTypes.*;
import java.time.Instant;
import java.util.*;

/** Exact candle calculation inputs are captured alongside each output, before publishing the output. */
public final class IndicatorReplay {
    public static final String VERSION="INDICATOR_CALCULATION_V1";
    public record Calculation(String version,String frame,Instant cutoff,List<Candle> candles,Indicators output) {
        public Calculation {candles=List.copyOf(candles);}
    }
    public record Check(String frame,boolean supported,boolean matches,int candles,Instant cutoff) {}
    private IndicatorReplay() {}
    public static Calculation calculate(IndicatorEngine engine,String frame,List<Candle> candles,Instant cutoff){
        return new Calculation(VERSION,frame,cutoff,candles,engine.calculate(frame,candles,cutoff));
    }
    /** Pure bounded history transition; the caller owns publication to its concurrent map. */
    public static List<Calculation> append(List<Calculation> previous,Calculation next){
        var out=new ArrayList<Calculation>();out.add(next);if(previous!=null)for(var old:previous)if(!old.equals(next)&&out.size()<3)out.add(old);return List.copyOf(out);
    }
    public static List<Calculation> select(Map<String,List<Calculation>> available,Map<String,Indicators> frozen){
        var out=new ArrayList<Calculation>();for(String frame:new TreeSet<>(frozen.keySet())){
            var target=frozen.get(frame);available.getOrDefault(frame,List.of()).stream().filter(c->
                c.frame().equals(target.frame())&&Objects.equals(c.output().calculatedAt(),target.calculatedAt())&&c.output().values().equals(target.values())
                &&Objects.equals(c.output().closedThrough(),target.closedThrough())&&c.output().samples()==target.samples()).findFirst().ifPresent(out::add);
        }return List.copyOf(out);
    }
    public static Check replay(Calculation saved){if(!VERSION.equals(saved.version()))return new Check(saved.frame(),false,false,saved.candles().size(),saved.cutoff());
        var output=new IndicatorEngine().calculate(saved.frame(),saved.candles(),saved.cutoff());return new Check(saved.frame(),true,output.equals(saved.output()),saved.candles().size(),saved.cutoff());}
}
