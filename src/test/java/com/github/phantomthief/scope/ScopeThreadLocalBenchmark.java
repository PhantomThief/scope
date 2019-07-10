package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.setFastThreadLocal;
import static com.github.phantomthief.scope.ScopeKey.withDefaultValue;
import static com.github.phantomthief.scope.ScopeKey.withInitializer;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

import java.util.Set;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import com.google.common.collect.ImmutableSet;

/**
 * 测试说明
 * 通过设置jvm参数来决定是否启用 {@link FastThreadLocalExecutor} ：
 *
 * com.github.phantomthief.scope.ScopeThreadLocalBenchmark.* --jvmArgs "-Djmh.executor=CUSTOM -Djmh.executor.class=com.github.phantomthief.scope.FastThreadLocalExecutor"
 *
 * @author w.vela
 * Created on 2019-07-08.
 */
@BenchmarkMode(Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 3)
@Threads(8)
@Fork(1)
@OutputTimeUnit(MILLISECONDS)
@State(Scope.Benchmark)
public class ScopeThreadLocalBenchmark {

    private static ScopeKey<Long> longScopeKey = withDefaultValue(0L);
    private static ScopeKey<String> stringScopeKey = withDefaultValue("asdasdasd");
    private static ScopeKey<Integer> intScopeKey = withDefaultValue(122);
    private static ScopeKey<Set<String>> setScopeKey = withInitializer(() -> ImmutableSet.of("11", "22", "33"));

    @Benchmark
    public void benchmarkGet() {
        setFastThreadLocal(false);
        longScopeKey.get();
        stringScopeKey.get();
        intScopeKey.get();
        setScopeKey.get();
    }

    @Benchmark
    public void benchmarkFastGet() {
        setFastThreadLocal(true);
        longScopeKey.get();
        stringScopeKey.get();
        intScopeKey.get();
        setScopeKey.get();
    }
}
