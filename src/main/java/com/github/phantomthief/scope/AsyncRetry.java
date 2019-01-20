package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;
import static com.google.common.util.concurrent.Futures.catchingAsync;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.SettableFuture;

/**
 * @author wangzhiqian <wangzhiqian@kuaishou.com>
 * Created on 2019-01-20
 */
public class AsyncRetry {

    private static ListeningScheduledExecutorService scheduler = listeningDecorator(
            Executors.newScheduledThreadPool(10));

    private static <T> FutureCallback setResultToOtherSettableFuture(SettableFuture<T> target) {
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
     * @param func 需要重试的调用
     * @param singleCallTimeout 单次调用超时限制，单位：ms
     * @param retryTime 重试次数
     * @param retryInterval 重试间隔，单位：ms
     * @return 带重试的future
     */
    public static <T> ListenableFuture<T> withRetry(@Nonnull Supplier<ListenableFuture<T>> func,
            long singleCallTimeout, int retryTime, long retryInterval) {
        SettableFuture<T> resultFuture = SettableFuture.create();
        return callWithRetry(func, singleCallTimeout, retryTime, retryInterval, resultFuture);
    }

    /**
     * 内部递归方法，返回值是最终的挂了多个retry callback的future
     */
    private static <T> SettableFuture<T> callWithRetry(@Nonnull Supplier<ListenableFuture<T>> func,
            long singleCallTimeout, int retryTime, long retryInterval,
            SettableFuture<T> resultFuture) {

        Scope scope = getCurrentScope();
        // 开始当前一次调用尝试
        final SettableFuture<T> currentTry = supplyWithExistScope(scope, () -> {
            SettableFuture<T> future = SettableFuture.create();
            try {
                Futures.addCallback(func.get(), setResultToOtherSettableFuture(future));
            } catch (Throwable t) {
                future.setException(t);
            }
            return future;
        });
        // 看是先超时还是先执行完成或者执行抛异常
        scheduler.schedule(() -> {
            currentTry.setException(new TimeoutException());
            currentTry.cancel(false);
        }, singleCallTimeout, TimeUnit.MILLISECONDS);

        if (retryTime <= 0) {
            // 如果不会再重试了，那就不管什么结果都set到最终结果里吧
            Futures.addCallback(currentTry, setResultToOtherSettableFuture(resultFuture));
        } else {
            // 本次尝试如果成功，直接给最终结果set上；超时或者异常的话，后边的重试操作都挂在catching里
            Futures.transform(currentTry, resultFuture::set);
        }

        // 为了用lambda ...
        SettableFuture<T> resultFuture0 = resultFuture;
        if (retryTime > 0) {
            // SettableFuture类型不好处理，后边也用不到set了
            ListenableFuture<T> currentTry0 = currentTry;
            if (retryInterval > 0) {
                // 需要等一会儿再重试的话，挂一个重试任务
                currentTry0 = catchingAsync(currentTry0, RetryIntervalSignal.class,
                        x -> scheduler.schedule(() -> {
                            throw new RetryIntervalSignal();
                        }, retryInterval, TimeUnit.MILLISECONDS));
            }

            catchingAsync(currentTry0, Throwable.class, x -> callWithRetry(func, singleCallTimeout,
                    retryTime - 1, retryInterval, resultFuture0));
        }

        return resultFuture;
    }

    private static class RetryIntervalSignal extends TimeoutException {
    }
}
