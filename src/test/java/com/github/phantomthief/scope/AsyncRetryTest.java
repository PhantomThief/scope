package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.AsyncRetry.withRetry;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * @author wangzhiqian <wangzhiqian@kuaishou.com>
 * Created on 2019-01-20
 */
public class AsyncRetryTest {

    private static AtomicInteger idx = new AtomicInteger(0);
    private static final long[] delayTimeArray = { 100, 60, 20, 10 };

    private static void delaySomeTime() {
        Uninterruptibles.sleepUninterruptibly(delayTimeArray[idx.getAndIncrement()],
                TimeUnit.MILLISECONDS);
    }

    private static final ListeningExecutorService executor = MoreExecutors
            .listeningDecorator(Executors.newCachedThreadPool());

    @Test
    void test() throws Throwable {

        long ts = System.currentTimeMillis();

        ListenableFuture<String> future = withRetry(() -> executor.submit(() -> {
            delaySomeTime();
            //            throw new IllegalStateException();
            return "done!";
        }), 25, 2, 100);

        try {
            future.get();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        long tsEnd = System.currentTimeMillis();
        System.out.println(tsEnd - ts);
    }
}
