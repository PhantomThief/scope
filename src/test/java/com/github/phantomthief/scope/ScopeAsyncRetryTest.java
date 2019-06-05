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
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.github.phantomthief.util.ThrowableSupplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

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
            ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10),
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
            ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10),
                    sleepySuccess(new long[] {300L, 200L, 50L}));
            assertEquals("2", future.get(350, MILLISECONDS));
        }
        endScope();
    }

    @Test
    void testBreak() {
        beginScope();
        initKey();
        for (int i = 0; i < 10; i++) {
            MySupplier1 func = sleepySuccess(new long[] {300L, 200L, 50L});
            ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10), func);
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
            ListenableFuture<Object> future = retrier.callWithRetry(100, retryNTimes(3, 10), () -> {
                assertContext();
                return executor.submit(() -> {
                    sleepUninterruptibly(500, MILLISECONDS);
                    throw new IllegalArgumentException("test");
                });
            });
            assertThrows(TimeoutException.class, () -> future.get(300, MILLISECONDS));
        }

        for (int i = 0; i < 10; i++) {
            ListenableFuture<Object> future2 = retrier.callWithRetry(100, retryNTimes(3, 10), () -> {
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
            ListenableFuture<Object> future3 = retrier.callWithRetry(20, retryNTimes(3, 10), () -> {
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
            ListenableFuture<Object> future4 = retrier.callWithRetry(20, retryNTimes(3, 10), () -> {
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
    private static final long[] delayTimeArray = {300, 900, 500, 900};

    private static void delaySomeTime() {
        sleepUninterruptibly(delayTimeArray[idx.getAndIncrement()],
                TimeUnit.MILLISECONDS);
    }

    @Test
    void testNotHedge() throws Throwable {
        beginScope();
        initKey();
        idx.set(0);

        AtomicInteger calledTimes = new AtomicInteger(0);

        ListenableFuture<String> future = retrier.callWithRetry(200, retryNTimes(3, 10, false),
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
            // ignore
        }
        assertNull(result);
        assertEquals(4, calledTimes.get());

        endScope();
    }

    @Test
    void testHedge() throws Throwable {
        beginScope();
        initKey();
        idx.set(0);

        AtomicInteger calledTimes = new AtomicInteger(0);

        ListenableFuture<String> future = retrier.callWithRetry(200, retryNTimes(3, 10, true),
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
            // ignore
        }
        assertEquals("1 -- done!", result);
        assertEquals(2, calledTimes.get());

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

    @Test
    void testAllTimeoutWithEachListener() {
        beginScope();
        initKey();
        AtomicInteger succNum = new AtomicInteger(0);
        AtomicInteger failedNum = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10),
                    () -> successAfter("test", 200), new FutureCallback<String>() {
                        @Override
                        public void onSuccess(@Nullable String result) {
                            succNum.incrementAndGet();
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            failedNum.incrementAndGet();
                        }
                    });
            assertThrows(TimeoutException.class,
                    () -> assertTimeout(ofMillis(60), () -> future.get(50, MILLISECONDS)));
        }
        endScope();
        sleepUninterruptibly(1, SECONDS);
        assertEquals(0, succNum.get());
        assertEquals(30, failedNum.get());
    }

    @Test
    void testWithEachListener() throws InterruptedException, ExecutionException, TimeoutException {
        beginScope();
        initKey();
        AtomicInteger succNum = new AtomicInteger(0);
        AtomicInteger failedNum = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(4, 10),
                    sleepySuccess(new long[] {300L, 200L, 50L, 100L}), new FutureCallback<String>() {
                        @Override
                        public void onSuccess(@Nullable String result) {
                            succNum.incrementAndGet();
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            failedNum.incrementAndGet();
                        }
                    });
            assertEquals("2", future.get(350, MILLISECONDS));
        }
        endScope();
        sleepUninterruptibly(1, SECONDS);
        assertEquals(10, succNum.get());
        assertEquals(20, failedNum.get());
    }

    @Test
    void testGetFuture() {
        beginScope();
        initKey();
        assertThrows(TimeoutException.class, () -> successAfter("test", 200).get(0, NANOSECONDS));
    }
}