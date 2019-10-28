package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.runWithExistScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;
import static com.github.phantomthief.util.MoreSuppliers.lazy;
import static java.lang.Boolean.TRUE;
import static java.lang.System.nanoTime;
import static java.lang.Thread.MIN_PRIORITY;
import static java.time.Duration.ofNanos;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.MapMaker;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author w.vela
 */
public final class ScopeUtils {

    private static final Logger logger = LoggerFactory.getLogger(ScopeUtils.class);
    private static final ConcurrentMap<LongCostTrackImpl, Boolean> MAP = new MapMaker()
            .weakKeys()
            .concurrencyLevel(64)
            .makeMap();

    private static final int CHECK_PERIOD = 1;

    private static final Supplier<ScheduledFuture<?>> SCHEDULER = lazy(() ->
            newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("long-cost-track")
                    .setPriority(MIN_PRIORITY)
                    .build())
                    .scheduleWithFixedDelay(ScopeUtils::doReport, CHECK_PERIOD, CHECK_PERIOD, SECONDS));

    private ScopeUtils() {
    }

    private static Runnable wrapRunnableExistScope(@Nullable Scope scope,
            @Nonnull Runnable runnable) {
        return () -> runWithExistScope(scope, runnable::run);
    }

    private static <T> Supplier<T> wrapSupplierExistScope(@Nullable Scope scope,
            @Nonnull Supplier<T> supplier) {
        return () -> supplyWithExistScope(scope, supplier::get);
    }

    public static void runAsyncWithCurrentScope(@Nonnull Runnable runnable,
            @Nonnull Executor executor) {
        executor.execute(wrapRunnableExistScope(getCurrentScope(), runnable));
    }

    @Nonnull
    public static ListenableFuture<?> runAsyncWithCurrentScope(@Nonnull Runnable runnable,
            @Nonnull ListeningExecutorService executor) {
        return executor.submit(wrapRunnableExistScope(getCurrentScope(), runnable));
    }

    @Nonnull
    public static <U> Future<U> supplyAsyncWithCurrentScope(@Nonnull Supplier<U> supplier,
            @Nonnull ExecutorService executor) {
        return executor.submit(() -> wrapSupplierExistScope(getCurrentScope(), supplier).get());
    }

    @Nonnull
    public static <U> ListenableFuture<U> supplyAsyncWithCurrentScope(@Nonnull Supplier<U> supplier,
            @Nonnull ListeningExecutorService executor) {
        return executor.submit(() -> wrapSupplierExistScope(getCurrentScope(), supplier).get());
    }

    /**
     * @param onTimeoutReportRunnable accept a time duration in nano-seconds.
     */
    public static LongCostTrack trackLongCost(Duration timeoutForReport, Consumer<Duration> onTimeoutReportRunnable) {
        SCHEDULER.get();
        Scope scope = getCurrentScope();
        long nano = nanoTime();
        LongCostTrackImpl context =
                new LongCostTrackImpl(onTimeoutReportRunnable, nano, nano + timeoutForReport.toNanos(), scope);
        MAP.put(context, TRUE);
        return context;
    }

    private static void doReport() {
        Iterator<Entry<LongCostTrackImpl, Boolean>> iterator = MAP.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<LongCostTrackImpl, Boolean> entry = iterator.next();
            LongCostTrackImpl key = entry.getKey();
            if (key.closed) {
                iterator.remove();
                continue;
            }
            long now = nanoTime();
            long currentCost = now - key.deadline;
            if (currentCost > 0) {
                runWithExistScope(key.scope, () -> {
                    try {
                        key.runnable.accept(ofNanos(now - key.start));
                    } catch (Throwable e) {
                        logger.error("", e);
                    } finally {
                        iterator.remove();
                    }
                });
            }
        }
    }

    private static class LongCostTrackImpl implements LongCostTrack {

        private final Consumer<Duration> runnable;
        private final long start;
        private final long deadline;
        private final Scope scope;
        private volatile boolean closed;

        private LongCostTrackImpl(Consumer<Duration> runnable, long start, long deadline, Scope scope) {
            this.runnable = runnable;
            this.start = start;
            this.deadline = deadline;
            this.scope = scope;
        }

        @Override
        public void close() {
            closed = true;
            // 希望业务调用这个，这样可以更早的回收，而不用等到GC阶段
            MAP.remove(this);
        }
    }
}
