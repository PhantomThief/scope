package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.RetryPolicy.NO_RETRY;
import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.Thread.MAX_PRIORITY;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.phantomthief.concurrent.TimeoutListenableFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * 支持 {@link Scope} 级联，并且支持单次调用独立设置超时的异步重试封装
 *
 * 使用方法:
 * <pre>{@code
 *
 * class MyClass {
 *
 *   private final ScopeAsyncRetry retrier = ScopeAsyncRetry.shared();
 *   private final ListeningExecutorService executor = listeningDecorator(newFixedThreadPool(10);
 *
 *   ListenableFuture&lt;String&gt; asyncCall() {
 *     return executor.submit(() -> {
 *       sleepUninterruptibly(ThreadLocalRandom.current().nextLong(150L), MILLISECONDS);
 *       return "myTest"
 *     });
 *   }
 *
 *   void foo() throws ExecutionException, TimeoutException {
 *     ListenableFuture&lt;String&gt; future = retrier.retry(100, retryNTimes(3), () -> asyncCall());
 *     String unwrapped = getUninterruptibly(future, 200, MILLISECONDS);
 *     System.out.println("result is:" + unwrapped);
 *   }
 * }
 *
 * }
 * </pre>
 *
 * 注意: 如果最终外部的ListenableFuture.get(timeout)没有超时，但是内部请求都失败了，则上抛
 * 会上抛 {@link ExecutionException} 并包含最后一次重试的结果
 * 特别的，如果最后一次请求超时 {@link ExecutionException#getCause()} 为 {@link TimeoutException}
 *
 * 注意: 请不要在 func 内加入同步阻塞逻辑，不然会造成重试 ScheduledExecutorService 内的线程快速耗尽
 *
 * @author w.vela
 * Created on 2019-01-07.
 */
public class ScopeAsyncRetry {

    private final ScheduledExecutorService scheduler;

    private ScopeAsyncRetry(ScheduledExecutorService scheduler) {
        this.scheduler = checkNotNull(scheduler);
    }

    public static ScopeAsyncRetry create(@Nonnegative ScheduledExecutorService executor) {
        return new ScopeAsyncRetry(executor);
    }

    public static ScopeAsyncRetry shared() {
        return LazyHolder.INSTANCE;
    }

    private <T> void handleRetry(long singleTimeoutInMs, int[] retryTimes, boolean[] outterTimeout,
            RetryPolicy retryPolicy, SettableFuture<T> settableFuture, Throwable e,
            Supplier<ListenableFuture<T>> func, Scope scope) {
        retryTimes[0]++;
        long retryDelay = retryPolicy.retry(retryTimes[0], e);
        if (retryDelay == NO_RETRY) {
            setException(e, settableFuture);
            return;
        }
        if (retryDelay > 0) {
            scheduler.schedule(() -> startNewCall(singleTimeoutInMs, retryPolicy, func,
                    settableFuture, outterTimeout, retryTimes, scope), retryDelay, MILLISECONDS);
        } else {
            startNewCall(singleTimeoutInMs, retryPolicy, func, settableFuture, outterTimeout,
                    retryTimes, scope);
        }
    }

    private void setException(Throwable e, SettableFuture<?> future) {
        if (e instanceof ExecutionException) {
            future.setException(e.getCause());
        } else {
            future.setException(e);
        }
    }

    /**
     * @param func 注意，请不要在此代码中包含同步阻塞调用
     */
    @Nonnull
    public <T> ListenableFuture<T> retry(@Nonnegative long singleTimeoutInMs, @Nonnull RetryPolicy retryPolicy,
            @Nonnull Supplier<ListenableFuture<T>> func) {
        checkArgument(singleTimeoutInMs >= 0);
        checkNotNull(retryPolicy);
        checkNotNull(func);

        Scope scope = getCurrentScope();
        SettableFuture<T> finalResult = SettableFuture.create();
        boolean[] outterTimeout = { false };
        TimeoutListenableFuture<T> timeoutListenableFuture = new TimeoutListenableFuture<>(
                finalResult).addTimeoutListener(() -> outterTimeout[0] = true);
        int[] retryTimes = { 0 };
        startNewCall(singleTimeoutInMs, retryPolicy, func, finalResult, outterTimeout, retryTimes,
                scope);
        return timeoutListenableFuture;
    }

    private <T> void startNewCall(long singleTimeoutInMs, RetryPolicy retryPolicy,
            Supplier<ListenableFuture<T>> func, SettableFuture<T> finalResult,
            boolean[] outterTimeout, int[] retryTimes, Scope scope) {
        ListenableFuture<T> realFuture = supplyWithExistScope(scope, () -> {
            try {
                return func.get();
            } catch (Throwable e) {
                handleRetry(singleTimeoutInMs, retryTimes, outterTimeout, retryPolicy, finalResult,
                        e, func, scope);
                return null;
            }
        });
        if (realFuture == null) {
            return;
        }
        AtomicBoolean resulted = new AtomicBoolean();
        addCallback(realFuture, new FutureCallback<T>() {

            @Override
            public void onSuccess(@Nullable T result) {
                if (resulted.compareAndSet(false, true)) {
                    finalResult.set(result);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                if (resulted.compareAndSet(false, true)) {
                    handleRetry(singleTimeoutInMs, retryTimes, outterTimeout, retryPolicy,
                            finalResult, t, func, scope);
                    cancelIfPossible(retryPolicy, realFuture);
                }
            }
        }, directExecutor());
        scheduler.schedule(() -> {
            if (resulted.compareAndSet(false, true)) {
                try {
                    finalResult.set(getDone(realFuture));
                } catch (ExecutionException e) {
                    handleRetry(singleTimeoutInMs, retryTimes, outterTimeout, retryPolicy,
                            finalResult, e, func, scope);
                    cancelIfPossible(retryPolicy, realFuture);
                } catch (IllegalStateException e) {
                    handleRetry(singleTimeoutInMs, retryTimes, outterTimeout, retryPolicy,
                            finalResult,
                            new TimeoutException("timeout after " + singleTimeoutInMs + "ms."),
                            func, scope);
                    cancelIfPossible(retryPolicy, realFuture);
                }
            }
        }, singleTimeoutInMs, MILLISECONDS);
    }

    private void cancelIfPossible(RetryPolicy retryPolicy, ListenableFuture<?> realFuture) {
        if (retryPolicy.cancelExceptionalFuture()) {
            realFuture.cancel(false);
        }
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
