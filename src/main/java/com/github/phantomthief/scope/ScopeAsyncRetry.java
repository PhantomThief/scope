package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;
import static com.google.common.util.concurrent.Futures.catchingAsync;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.lang.Thread.MAX_PRIORITY;
import static java.util.concurrent.Executors.newScheduledThreadPool;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.phantomthief.util.ThrowableSupplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author myco
 * Created on 2019-01-20
 */
public class ScopeAsyncRetry {

    private final ListeningScheduledExecutorService scheduler;

    public static ScopeAsyncRetry create(@Nonnegative ScheduledExecutorService executor) {
        return new ScopeAsyncRetry(executor);
    }

    public static ScopeAsyncRetry shared() {
        return LazyHolder.INSTANCE;
    }

    private ScopeAsyncRetry(ScheduledExecutorService scheduler) {
        this.scheduler = listeningDecorator(scheduler);
    }

    private static <T> FutureCallback<T> setResultToOtherSettableFuture(SettableFuture<T> target) {
        return new FutureCallback<T>() {

            @Override
            public void onSuccess(@Nullable T result) {
                target.set(result);
            }

            @Override
            public void onFailure(Throwable t) {
                target.setException(t);
            }
        };
    }

    /**
     * 带重试的调用
     *
     * @param singleCallTimeout 单次调用超时限制，单位：ms
     * @param func 需要重试的调用
     * @return 带重试的future
     */
    public <T, X extends Throwable> ListenableFuture<T> retry(long singleCallTimeout,
            RetryPolicy retryPolicy, @Nonnull ThrowableSupplier<ListenableFuture<T>, X> func) {
        SettableFuture<T> resultFuture = SettableFuture.create();

        AtomicInteger retryTime = new AtomicInteger(0);
        LongSupplier retryIntervalSupplier = () -> retryPolicy.retry(retryTime.incrementAndGet());

        Scope scope = getCurrentScope();
        ThrowableSupplier<ListenableFuture<T>, X> scopeWarppedFunc = () -> supplyWithExistScope(
                scope, func);

        return callWithRetry(scopeWarppedFunc, singleCallTimeout, retryIntervalSupplier,
                resultFuture);
    }

    /**
     * 内部递归方法，返回值是最终的挂了多个retry callback的future
     */
    private <T, X extends Throwable> SettableFuture<T> callWithRetry(
            @Nonnull ThrowableSupplier<ListenableFuture<T>, X> func, long singleCallTimeout,
            LongSupplier retryIntervalSupplier, SettableFuture<T> resultFuture) {

        // 开始当前一次调用尝试
        final SettableFuture<T> currentTry = SettableFuture.create();
        try {
            Futures.addCallback(func.get(), setResultToOtherSettableFuture(currentTry));
        } catch (Throwable t) {
            currentTry.setException(t);
        }
        // 看是先超时还是先执行完成或者执行抛异常
        scheduler.schedule(() -> {
            currentTry.setException(new TimeoutException());
            currentTry.cancel(false);
        }, singleCallTimeout, TimeUnit.MILLISECONDS);

        long retryInterval = retryIntervalSupplier.getAsLong();

        if (retryInterval < 0) {
            // 如果不会再重试了，那就不管什么结果都set到最终结果里吧
            Futures.addCallback(currentTry, setResultToOtherSettableFuture(resultFuture));
        } else {
            // 本次尝试如果成功，直接给最终结果set上；超时或者异常的话，后边的重试操作都挂在catching里
            Futures.transform(currentTry, resultFuture::set);
        }

        // 为了用lambda ...
        SettableFuture<T> resultFuture0 = resultFuture;
        if (retryInterval >= 0) {
            // SettableFuture类型不好处理，后边也用不到set了
            ListenableFuture<T> currentTry0 = currentTry;
            if (retryInterval > 0) {
                // 需要等一会儿再重试的话，挂一个等待任务
                currentTry0 = catchingAsync(currentTry0, RetryIntervalSignal.class,
                        x -> scheduler.schedule(() -> {
                            throw new RetryIntervalSignal();
                        }, retryInterval, TimeUnit.MILLISECONDS));
            }
            // 挂上重试任务
            catchingAsync(currentTry0, Throwable.class, x -> callWithRetry(func, singleCallTimeout,
                    retryIntervalSupplier, resultFuture0));
        }

        return resultFuture;
    }

    private static class RetryIntervalSignal extends TimeoutException {
    }

    private static final class LazyHolder {

        private static final ScopeAsyncRetry INSTANCE = create(
                newScheduledThreadPool(Runtime.getRuntime().availableProcessors(),
                        new ThreadFactoryBuilder() //
                                .setPriority(MAX_PRIORITY) //
                                .setNameFormat("default-retrier-%d") //
                                .build()));
    }
}
