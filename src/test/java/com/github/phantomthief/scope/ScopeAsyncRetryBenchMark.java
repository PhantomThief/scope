package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.RetryPolicy.retryNTimes;
import static com.github.phantomthief.scope.Scope.beginScope;
import static com.github.phantomthief.scope.Scope.endScope;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.lang.Thread.MAX_PRIORITY;

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
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author myco
 * Created on 2019-06-05
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 5, time = 1)
@Threads(8)
@Fork(1)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ScopeAsyncRetryBenchMark {

    private static ListeningScheduledExecutorService listeningScheduledExecutorService = listeningDecorator(
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()));

    private static ListenableFuture<String> successAfter(String expected, long timeout) {
        return listeningScheduledExecutorService.schedule(() -> expected, timeout, TimeUnit.MILLISECONDS);
    }

    private static ScopeAsyncRetry retrier;

    @Setup
    public static void init() {
        retrier = new ScopeAsyncRetry(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 100,
                new ThreadFactoryBuilder() //
                        .setPriority(MAX_PRIORITY) //
                        .setNameFormat("default-retrier-%d") //
                        .build()));
        beginScope();
    }

    @TearDown
    public static void destroy() {
        endScope();
    }

    @Benchmark
    public static void testAllTimeout() {
        retrier.callWithRetry(10, retryNTimes(3, 10),
                () -> successAfter("test", 1000));
    }

    @Benchmark
    public static void testAllTimeout2() {
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
                () -> successAfter("test", 1000));
    }

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder()
                .include(ScopeAsyncRetryBenchMark.class.getName())
                .build();
        new Runner(options).run();
    }
}
