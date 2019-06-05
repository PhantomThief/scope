package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.lang.Thread.MAX_PRIORITY;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.phantomthief.util.ThrowableSupplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * 支持 {@link Scope} 级联，并且支持单次调用独立设置超时的异步重试封装
 * <p>
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
 *     ListenableFuture&lt;String&gt; future = retrier.callWithRetry(100, retryNTimes(3), () -> asyncCall());
 *     String unwrapped = getUninterruptibly(future, 200, MILLISECONDS);
 *     System.out.println("result is:" + unwrapped);
 *   }
 * }
 *
 * }
 * </pre>
 * <p>
 * 注意: 如果最终外部的ListenableFuture.get(timeout)没有超时，但是内部请求都失败了，则上抛
 * 会上抛 {@link java.util.concurrent.ExecutionException} 并包含最后一次重试的结果
 * 特别的，如果最后一次请求超时 {@link java.util.concurrent.ExecutionException#getCause()} 为 {@link TimeoutException}
 * <p>
 * 注意: 需要重试的方法应该是幂等的操作，不应有任何副作用。
 *
 * @author myco
 * Created on 2019-01-20
 */
public class ScopeAsyncRetry {

    private final ListeningScheduledExecutorService scheduler;
    private final ListeningExecutorService callbackExecutor;

    /**
     * 新建一个 ScopeAsyncRetry 实例
     */
    public static ScopeAsyncRetry createScopeAsyncRetry(@Nonnegative ScheduledExecutorService executor) {
        return new ScopeAsyncRetry(executor);
    }

    /**
     * 共享的 ScopeAsyncRetry 实例
     * <p>
     * 建议不同业务使用不同的实例，因为其中通过 ScheduledExecutorService 来检测超时 & 实现间隔重试
     * 大量使用共享实例，这里可能成为瓶颈
     */
    public static ScopeAsyncRetry shared() {
        return LazyHolder.INSTANCE;
    }

    private ScopeAsyncRetry(ScheduledExecutorService scheduler) {
        this(scheduler, newDirectExecutorService());
    }

    private ScopeAsyncRetry(ScheduledExecutorService scheduler, ExecutorService callbackExecutor) {
        this.scheduler = listeningDecorator(scheduler);
        this.callbackExecutor = listeningDecorator(callbackExecutor);
    }

    /**
     * 内部工具方法，将future结果代理到另一个SettableFuture上
     */
    private static <T> FutureCallback<T> setAllResultToOtherSettableFuture(SettableFuture<T> target) {
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

    private static <T> FutureCallback<T> cancelOtherSettableFuture(SettableFuture<T> target,
            boolean mayInterruptIfRunning) {
        return new FutureCallback<T>() {

            @Override
            public void onSuccess(@Nullable T result) {
                target.cancel(mayInterruptIfRunning);
            }

            @Override
            public void onFailure(Throwable t) {
                target.cancel(mayInterruptIfRunning);
            }
        };
    }

    private static <T> FutureCallback<T> setSuccessResultToOtherSettableFuture(SettableFuture<T> target) {
        return new FutureCallback<T>() {

            @Override
            public void onSuccess(@Nullable T result) {
                target.set(result);
            }

            @Override
            public void onFailure(Throwable t) {
            }
        };
    }

    private static <T> void addCallbackWithDirectExecutor(ListenableFuture<T> future,
            FutureCallback<? super T> callback) {
        addCallback(future, callback, directExecutor());
    }

    private static class RetryConfig {

        private final long retryInterval;
        private final boolean hedge;

        private RetryConfig(long retryInterval, boolean hedge) {
            this.retryInterval = retryInterval;
            this.hedge = hedge;
        }
    }

    /**
     * 带重试的调用
     *
     * @param singleCallTimeoutMs 单次调用超时限制，单位：ms
     * @param func 需要重试的调用
     * @return 带重试的future
     */
    @Nonnull
    public <T, X extends Throwable> ListenableFuture<T> callWithRetry(long singleCallTimeoutMs,
            RetryPolicy retryPolicy, @Nonnull ThrowableSupplier<ListenableFuture<T>, X> func) {
        return callWithRetry(singleCallTimeoutMs, retryPolicy, func, null);
    }

    @Nonnull
    public <T, X extends Throwable> ListenableFuture<T> callWithRetry(long singleCallTimeoutMs,
            RetryPolicy retryPolicy, @Nonnull ThrowableSupplier<ListenableFuture<T>, X> func,
            FutureCallback<T> eachRetryCallback) {
        // 用来保存最终的结果
        SettableFuture<T> resultFuture = SettableFuture.create();

        AtomicInteger retryTime = new AtomicInteger(0);
        Supplier<RetryConfig> retryConfigSupplier = () -> new RetryConfig(
                retryPolicy.retry(retryTime.incrementAndGet()), retryPolicy.hedge());

        Scope scope = getCurrentScope();
        ThrowableSupplier<ListenableFuture<T>, X> scopeWrappedFunc = () -> supplyWithExistScope(
                scope, func);

        return callWithRetry(scopeWrappedFunc, singleCallTimeoutMs, retryConfigSupplier,
                resultFuture, eachRetryCallback);
    }

    /**
     * 内部递归方法，返回值是最终的挂了多个retry callback的future
     */
    private <T, X extends Throwable> SettableFuture<T> callWithRetry(
            @Nonnull ThrowableSupplier<ListenableFuture<T>, X> func, long singleCallTimeoutMs,
            Supplier<RetryConfig> retryConfigSupplier, SettableFuture<T> resultFuture,
            FutureCallback<T> eachRetryCallback) {

        RetryConfig retryConfig = retryConfigSupplier.get();

        // 开始当前一次调用尝试
        final SettableFuture<T> currentTry = SettableFuture.create();
        if (eachRetryCallback != null) {
            addCallback(currentTry, eachRetryCallback, callbackExecutor);
        }
        ListenableFuture<T> callingFuture = null;
        try {
            callingFuture = func.get();
            addCallbackWithDirectExecutor(callingFuture,
                    setAllResultToOtherSettableFuture(currentTry));
        } catch (Throwable t) {
            currentTry.setException(t);
        }
        if (callingFuture != null) {
            // 看是先超时还是先执行完成或者执行抛异常
            scheduler.schedule(() -> {
                currentTry.setException(new TimeoutException());
                if (!retryConfig.hedge) {
                    // 普通模式下，这次重试超时就把这次的future cancel掉
                    currentTry.cancel(false);
                } else {
                    // hedge模式下，这次重试等到最终结果确定下来之后再cancel
                    addCallbackWithDirectExecutor(resultFuture,
                            cancelOtherSettableFuture(currentTry, false));
                }
            }, singleCallTimeoutMs, MILLISECONDS);
        }

        if (retryConfig.hedge && callingFuture != null) {
            // hedge模式下，不cancel之前的尝试，之前的调用一旦成功就set到最终结果里
            addCallbackWithDirectExecutor(callingFuture,
                    setSuccessResultToOtherSettableFuture(resultFuture));
        }

        if (retryConfig.retryInterval < 0) {
            // 如果不会再重试了，那就不管什么结果都set到最终结果里吧
            addCallbackWithDirectExecutor(currentTry,
                    setAllResultToOtherSettableFuture(resultFuture));
        } else {
            // 本次尝试如果成功，直接给最终结果set上；超时或者异常的话，后边的重试操作都挂在catching里
            addCallbackWithDirectExecutor(currentTry,
                    setSuccessResultToOtherSettableFuture(resultFuture));
        }

        // hedge模式下，resultFuture可能被之前的调用成功set值，所里这里不仅检查是否需要重试，也检查下是否已经取到了最终结果
        if (!resultFuture.isDone() && retryConfig.retryInterval >= 0) {
            // 没拿到最终结果，且重试次数还没用完，那我们接着加重试callback
            addCallbackWithDirectExecutor(currentTry, new FutureCallback<T>() {

                @Override
                public void onSuccess(@Nullable T result) {
                    // 只有失败了才需要再进行重试，所以这里就啥也不干了
                }

                @Override
                public void onFailure(Throwable t) {
                    // 不管之前是超时还是执行失败了，只要最终结果没拿到，且重试次数还没用完，就会到这里来
                    if (retryConfig.retryInterval > 0) {
                        // 延迟一会儿再重试
                        scheduler.schedule(() -> {
                            callWithRetry(func, singleCallTimeoutMs, retryConfigSupplier,
                                    resultFuture, eachRetryCallback);
                        }, retryConfig.retryInterval, MILLISECONDS);
                    } else {
                        // 直接重试
                        callWithRetry(func, singleCallTimeoutMs, retryConfigSupplier, resultFuture, eachRetryCallback);
                    }
                }
            });
        }

        return resultFuture;
    }

    private static final class LazyHolder {

        private static final ScopeAsyncRetry INSTANCE = createScopeAsyncRetry(
                newScheduledThreadPool(Runtime.getRuntime().availableProcessors(),
                        new ThreadFactoryBuilder() //
                                .setPriority(MAX_PRIORITY) //
                                .setNameFormat("default-retrier-%d") //
                                .build()));
    }
}
