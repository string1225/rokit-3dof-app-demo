package com.rokid.stabledemo.power;

interface BenchmarkWorkload {
    interface Callback { void ready(String detail); void failed(String reason); }
    interface Stopped { void stopped(String error); }
    void start(Callback callback) throws Exception;
    default String healthFailure() { return null; }
    /** Completes only after resource release and media finalization. Null error means clean shutdown. */
    void stop(Stopped callback);
}
