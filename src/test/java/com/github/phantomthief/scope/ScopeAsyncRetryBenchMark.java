package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.RetryPolicy.retryNTimes;
import static com.github.phantomthief.scope.Scope.beginScope;
import static com.github.phantomthief.scope.Scope.endScope;
import static com.github.phantomthief.scope.ScopeAsyncRetry.shared;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

/**
 * @author myco
 * Created on 2019-06-05
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 5, time = 4)
@Threads(8)
@Fork(1)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ScopeAsyncRetryBenchMark {

    private static ListeningScheduledExecutorService listeningScheduledExecutorService = listeningDecorator(
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()));

    private ListenableFuture<String> successAfter(String expected, long timeout) {
        return listeningScheduledExecutorService.schedule(() -> expected, timeout, TimeUnit.MILLISECONDS);
    }

    private static final ScopeAsyncRetry retrier = shared();

    @Setup
    public static void init() {
        beginScope();
    }

    @TearDown
    public static void destroy() {
        endScope();
        String[] a = {null};
    }

    @Benchmark
    public void testAllTimeout() {
        retrier.callWithRetry(1000, retryNTimes(3, 10),
                () -> successAfter("test", 10000));
    }

    @Benchmark
    public void testAllTimeout2() {
        retrier.callWithRetry(10, new RetryPolicy() {
                    @Override
                    public long retry(int retryCount) {
                        return retryCount <= 3 ? 10 : NO_RETRY;
                    }

                    @Override
                    public boolean triggerGetOnTimeout() {
                        return true;
                    }
                },
                () -> successAfter("test", 10000));
    }

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder()
                .include(ScopeAsyncRetryBenchMark.class.getName())
                //                .addProfiler("stack", "lines=10")
                .build();
        new Runner(options).run();
    }
}
