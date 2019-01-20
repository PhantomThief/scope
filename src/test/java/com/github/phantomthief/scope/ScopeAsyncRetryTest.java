package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.RetryPolicy.retryNTimes;
import static com.github.phantomthief.scope.Scope.beginScope;
import static com.github.phantomthief.scope.Scope.endScope;
import static com.github.phantomthief.scope.ScopeAsyncRetry.shared;
import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.time.Duration.ofMillis;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.phantomthief.util.ThrowableSupplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * @author w.vela
 * Created on 2019-01-11.
 */
class ScopeAsyncRetryTest {

    private final ScopeAsyncRetry retrier = shared();
    private final ListeningExecutorService executor = listeningDecorator(newFixedThreadPool(10));
    private final ScopeKey<String> context = allocate();

    private void initKey() {
        context.set("test");
    }

    private void assertContext() {
        assertEquals("test", context.get());
    }

    @Test
    void testAllTimeout() {
        beginScope();
        initKey();
        for (int i = 0; i < 10; i++) {
            ListenableFuture<String> future = retrier.retry(100, retryNTimes(3, 10),
                    () -> successAfter("test", 200));
            assertThrows(TimeoutException.class,
                    () -> assertTimeout(ofMillis(60), () -> future.get(50, MILLISECONDS)));
        }
        endScope();
    }

    @Test
    void test() throws InterruptedException, ExecutionException, TimeoutException {
        beginScope();
        initKey();
        for (int i = 0; i < 10; i++) {
            ListenableFuture<String> future = retrier.retry(100, retryNTimes(3, 10),
                    sleepySuccess(new long[] { 300L, 200L, 50L }));
            assertEquals("2", future.get(350, MILLISECONDS));
        }
        endScope();
    }

    @Test
    void testBreak() {
        beginScope();
        initKey();
        for (int i = 0; i < 10; i++) {
            MySupplier1 func = sleepySuccess(new long[] { 300L, 200L, 50L });
            ListenableFuture<String> future = retrier.retry(100, retryNTimes(3, 10), func);
            assertThrows(TimeoutException.class, () -> future.get(50, MILLISECONDS));
            assertEquals(1, func.current);
        }
        endScope();
    }

    @Test
    void testException() {
        beginScope();
        initKey();
        for (int i = 0; i < 10; i++) {
            ListenableFuture<Object> future = retrier.retry(100, retryNTimes(3, 10), () -> {
                assertContext();
                return executor.submit(() -> {
                    sleepUninterruptibly(500, MILLISECONDS);
                    throw new IllegalArgumentException("test");
                });
            });
            assertThrows(TimeoutException.class, () -> future.get(300, MILLISECONDS));
        }

        for (int i = 0; i < 10; i++) {
            ListenableFuture<Object> future2 = retrier.retry(100, retryNTimes(3, 10), () -> {
                assertContext();
                return executor.submit(() -> {
                    sleepUninterruptibly(50, MILLISECONDS);
                    throw new IllegalArgumentException("test");
                });
            });
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future2.get(1600, MILLISECONDS));
            assertSame(IllegalArgumentException.class, exception.getCause().getClass());
        }

        for (int i = 0; i < 10; i++) {
            ListenableFuture<Object> future3 = retrier.retry(20, retryNTimes(3, 10), () -> {
                assertContext();
                return executor.submit(() -> {
                    sleepUninterruptibly(100, MILLISECONDS);
                    throw new IllegalArgumentException("test");
                });
            });
            ExecutionException exception3 = assertThrows(ExecutionException.class,
                    () -> future3.get(1600, MILLISECONDS));
            assertSame(TimeoutException.class, exception3.getCause().getClass());
        }

        for (int i = 0; i < 10; i++) {
            ListenableFuture<Object> future4 = retrier.retry(20, retryNTimes(3, 10), () -> {
                assertContext();
                throw new IllegalArgumentException("test");
            });
            ExecutionException exception4 = assertThrows(ExecutionException.class,
                    () -> future4.get(1600, MILLISECONDS));
            assertSame(IllegalArgumentException.class, exception4.getCause().getClass());
        }
        endScope();
    }

    private static AtomicInteger idx = new AtomicInteger(0);
    private static final long[] delayTimeArray = { 210, 900, 500, 900 };

    private static void delaySomeTime() {
        Uninterruptibles.sleepUninterruptibly(delayTimeArray[idx.getAndIncrement()],
                TimeUnit.MILLISECONDS);
    }

    @Test
    void testHedge() throws Throwable {
        beginScope();
        initKey();

        AtomicInteger calledTimes = new AtomicInteger(0);

        long ts = System.currentTimeMillis();
        ListenableFuture<String> future = retrier.retry(200, retryNTimes(3, 10, false),
                () -> executor.submit(() -> {
                    int id = calledTimes.incrementAndGet();
                    delaySomeTime();
                    //                    throw new IllegalStateException();
                    return id + " -- done!";
                }));
        String result = null;
        try {
            result = future.get();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        long tsEnd = System.currentTimeMillis();
        System.out.println(tsEnd - ts);
        System.out.println(calledTimes.get());
        System.out.println(result);

        endScope();
    }

    private ListenableFuture<String> successAfter(String expected, long timeout) {
        assertContext();
        return executor.submit(() -> {
            sleepUninterruptibly(timeout, MILLISECONDS);
            return expected;
        });
    }

    private MySupplier1 sleepySuccess(long[] sleepArray) {
        assertContext();
        return new MySupplier1(sleepArray);
    }

    private class MySupplier1 implements
                              ThrowableSupplier<ListenableFuture<String>, RuntimeException> {

        private final long[] sleepArray;
        private int current;

        public MySupplier1(long[] sleepArray) {
            this.sleepArray = sleepArray;
            current = 0;
        }

        @Override
        public ListenableFuture<String> get() {
            return executor.submit(() -> {
                int index = current++;
                long sleepFor = sleepArray[index];
                sleepUninterruptibly(sleepFor, MILLISECONDS);
                return index + "";
            });
        }
    }
}