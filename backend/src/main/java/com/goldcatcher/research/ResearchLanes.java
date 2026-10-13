package com.goldcatcher.research;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission and workers are independent; a deep run cannot occupy a fast run's slot. */
final class ResearchLanes implements AutoCloseable {
    static final int FAST_REQUEST_SECONDS=120;
    static String name(String horizon){return "SHORT".equals(horizon)?"fast_lane":"deep_lane";}
    private static final class Slots extends Semaphore {
        Slots(int permits){super(permits,true);}
        void resize(int difference){if(difference>0)release(difference);else if(difference<0)reducePermits(-difference);}
    }
    private static final class Lane {
        final Slots slots;
        final ExecutorService admissions=Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        final ExecutorService rounds=Executors.newVirtualThreadPerTaskExecutor();
        final ExecutorService calls=Executors.newVirtualThreadPerTaskExecutor();
        final Set<String> products=ConcurrentHashMap.newKeySet();
        volatile int capacity;
        Lane(int capacity){this.capacity=capacity;slots=new Slots(capacity);}
        synchronized void capacity(int value){slots.resize(value-capacity);capacity=value;}
    }
    private final Lane fast=new Lane(4),deep=new Lane(2);
    private Lane lane(String horizon){return "SHORT".equals(horizon)?fast:deep;}
    synchronized void configure(int shortCapacity,int longCapacity){fast.capacity(shortCapacity);deep.capacity(longCapacity);}
    Lease acquire(String symbol,String horizon){
        var lane=lane(horizon);
        if(!lane.products.add(symbol))throw new IllegalArgumentException("RESEARCH_ALREADY_RUNNING");
        if(!lane.slots.tryAcquire()){lane.products.remove(symbol);throw new IllegalArgumentException("RESEARCH_LANE_FULL");}
        return new Lease(lane,symbol);
    }
    boolean available(String symbol,String horizon){var lane=lane(horizon);return lane.slots.availablePermits()>0&&!lane.products.contains(symbol);}
    ExecutorService rounds(String horizon){return lane(horizon).rounds;}
    ExecutorService admissions(String horizon){return lane(horizon).admissions;}
    ExecutorService calls(String horizon){return lane(horizon).calls;}
    List<Map<String,Object>> status(){return List.of(status("SHORT",fast),status("LONG",deep));}
    private Map<String,Object> status(String horizon,Lane lane){return Map.of("horizon",horizon,"lane",name(horizon),"capacity",lane.capacity,
        "running",lane.products.size(),"available",Math.max(0,lane.slots.availablePermits()),"maximumRequestSeconds",horizon.equals("SHORT")?120:900,
        "maximumRunSeconds",7200,"roundBudgetSource","FROZEN_UI_POLICY");}
    static final class Lease implements AutoCloseable {
        private final Lane lane;private final String symbol;private final AtomicBoolean closed=new AtomicBoolean();
        Lease(Lane lane,String symbol){this.lane=lane;this.symbol=symbol;}
        public void close(){if(closed.compareAndSet(false,true)){lane.products.remove(symbol);lane.slots.release();}}
    }
    public void close(){for(var lane:List.of(fast,deep)){lane.admissions.shutdownNow();lane.rounds.shutdownNow();lane.calls.shutdownNow();}
        for(var lane:List.of(fast,deep))for(var executor:List.of(lane.admissions,lane.rounds,lane.calls))try{executor.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}
}
