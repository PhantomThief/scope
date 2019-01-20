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

    public static <T> ListenableFuture<T> withRetry(@Nonnull Supplier<ListenableFuture<T>> func,
            long singleCallTimeout, int retryTime) {
        SettableFuture<T> resultFuture = SettableFuture.create();
        return callWithRetry(func, singleCallTimeout, retryTime, resultFuture);
    }

    /**
     * 内部递归方法，返回值是最终的挂了多个retry callback的future
     */
    private static <T> SettableFuture<T> callWithRetry(@Nonnull Supplier<ListenableFuture<T>> func,
            long singleCallTimeout, int retryTime, SettableFuture<T> resultFuture) {

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
            ListenableFuture<T> tmp = catchingAsync(currentTry, Throwable.class,
                    x -> callWithRetry(func, singleCallTimeout, retryTime - 1, resultFuture0));
        }

        return resultFuture;
    }
}
