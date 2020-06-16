package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.RetryPolicy.retryNTimes;
import static com.github.phantomthief.scope.Scope.beginScope;
import static com.github.phantomthief.scope.Scope.endScope;
import static com.github.phantomthief.scope.ScopeAsyncRetry.createScopeAsyncRetry;
import static com.github.phantomthief.scope.ScopeAsyncRetry.shared;
import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.lang.Thread.MAX_PRIORITY;
import static java.time.Duration.ofMillis;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import com.github.phantomthief.util.ThrowableSupplier;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author w.vela
 * Created on 2019-01-11.
 */
class ScopeAsyncRetryTest {

    private final ScopeAsyncRetry retrier = shared();
    private final ListeningExecutorService executor =
            listeningDecorator(newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4));
    private final ScopeKey<String> context = allocate();
    private final ListeningScheduledExecutorService scheduler =
            listeningDecorator(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()));

    private void initKey() {
        context.set("test");
    }

    private void assertContext() {
        assertEquals("test", context.get());
    }

    @Test
    void testTimeout() throws Throwable {
        beginScope();
        try {
            initKey();
            ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10, false),
                    () -> successAfter("test", 200));
            try {
                future.get();
            } catch (Throwable t) {
                assertTrue(t instanceof ExecutionException);
                assertTrue(t.getCause() instanceof TimeoutException);
            }
        } finally {
            endScope();
        }
    }

    @Test
    void testTimeout2() throws Throwable {
        beginScope();
        try {
            initKey();
            ListenableFuture<String> future = retrier.callWithRetry(10, retryNTimes(3, 10),
                    () -> successAfter("test", 100));
            addCallback(future, new FutureCallback<String>() {
                @Override
                public void onSuccess(@Nullable String result) {
                    System.out.println(result);
                }

                @Override
                public void onFailure(Throwable t) {
                    t.printStackTrace();
                }
            }, directExecutor());
            try {
                future.get();
            } catch (Throwable t) {
                assertTrue(t instanceof ExecutionException);
                assertTrue(t.getCause() instanceof TimeoutException);
            }
        } finally {
            endScope();
        }
    }

    @Test
    void testAllTimeout() {
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10),
                        () -> successAfter("test", 200));
                assertThrows(TimeoutException.class,
                        () -> assertTimeout(ofMillis(60), () -> future.get(50, MILLISECONDS)));
            }
        } finally {
            endScope();
        }
    }

    @Test
    void test() throws InterruptedException, ExecutionException, TimeoutException {
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10),
                        sleepySuccess(new long[] {300L, 200L, 50L}));
                assertEquals("2", future.get(350, MILLISECONDS));
            }
        } finally {
            endScope();
        }
    }

    @Test
    void testBreak() {
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                MySupplier1 func = sleepySuccess(new long[] {300L, 200L, 50L});
                ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10), func);
                assertThrows(TimeoutException.class, () -> future.get(50, MILLISECONDS));
                future.cancel(false);
                sleepUninterruptibly(1, SECONDS);
                assertEquals(1, func.current.get());
            }
        } finally {
            endScope();
        }
    }

    @Test
    void testException() {
        beginScope();
        try {
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
        } finally {
            endScope();
        }
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
        try {
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
        } finally {
            endScope();
        }
    }

    @Test
    void testHedge() throws Throwable {
        beginScope();
        try {
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
        } finally {
            endScope();
        }
    }

    private final AtomicLong callTimes = new AtomicLong(0);

    private void clearCallTimes() {
        callTimes.set(0);
    }

    private long getCallTimes() {
        return callTimes.get();
    }

    private ListenableFuture<String> successAfter(String expected, long timeout) {
        callTimes.incrementAndGet();
        assertContext();
        return scheduler.schedule(() -> expected, timeout, MILLISECONDS);
    }

    private ListenableFuture<String> exceptionAfter(long timeout) {
        callTimes.incrementAndGet();
        return scheduler.schedule(() -> {
            throw new RuntimeException();
        }, timeout, MILLISECONDS);
    }

    private ListenableFuture<String> successAfterBySleep(String expected, long timeout) {
        callTimes.incrementAndGet();
        assertContext();
        return executor.submit(() -> {
            sleepUninterruptibly(timeout, MILLISECONDS);
            return expected;
        });
    }

    private MySupplier2 exceptionsAndThenSuccess(Throwable[] exceptionArray) {
        assertContext();
        return new MySupplier2(exceptionArray);
    }

    private class MySupplier2 implements ThrowableSupplier<ListenableFuture<String>, RuntimeException> {

        private final Throwable[] exceptionArray;
        private int current = 0;

        MySupplier2(Throwable[] exceptionArray) {
            this.exceptionArray = exceptionArray;
        }

        @Override
        public ListenableFuture<String> get() throws RuntimeException {
            callTimes.incrementAndGet();
            int idx = current++;
            if (idx < exceptionArray.length) {
                return Futures.immediateFailedFuture(exceptionArray[idx]);
            } else {
                return Futures.immediateFuture("test");
            }
        }
    }

    private MySupplier1 sleepySuccess(long[] sleepArray) {
        assertContext();
        return new MySupplier1(sleepArray);
    }

    private class MySupplier1 implements
            ThrowableSupplier<ListenableFuture<String>, RuntimeException> {

        private final long[] sleepArray;
        private AtomicInteger current = new AtomicInteger(0);

        public MySupplier1(long[] sleepArray) {
            this.sleepArray = sleepArray;
        }

        @Override
        public ListenableFuture<String> get() {
            callTimes.incrementAndGet();
            return executor.submit(() -> {
                int index = current.getAndIncrement();
                long sleepFor = sleepArray[index];
                sleepUninterruptibly(sleepFor, MILLISECONDS);
                return index + "";
            });
        }
    }

    @Test
    void testAllTimeoutWithEachListener() {
        clearCallTimes();
        beginScope();
        try {
            initKey();
            AtomicInteger succNum = new AtomicInteger(0);
            AtomicInteger failedNum = new AtomicInteger(0);
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10, false),
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
                        () -> assertTimeout(ofMillis(60), () -> future.get(5, MILLISECONDS)));
            }
            sleepUninterruptibly(1, SECONDS);
            assertEquals(40, getCallTimes());
            assertEquals(0, succNum.get());
            assertEquals(40, failedNum.get());
        } finally {
            endScope();
        }
    }

    @Test
    void testAllTimeoutWithEachListenerWithHedgeMode() {
        clearCallTimes();
        beginScope();
        try {
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
                        () -> assertTimeout(ofMillis(60), () -> future.get(5, MILLISECONDS)));
            }
            sleepUninterruptibly(1, SECONDS);
            assertEquals(20, getCallTimes());
            assertEquals(0, succNum.get());
            assertEquals(20, failedNum.get());
        } finally {
            endScope();
        }
    }

    @Test
    void testWithEachListener() throws InterruptedException, ExecutionException, TimeoutException {
        beginScope();
        try {
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
            sleepUninterruptibly(1, SECONDS);
            assertEquals(10, succNum.get());
            assertEquals(20, failedNum.get());
        } finally {
            endScope();
        }
    }

    @Test
    void testGetFuture() {
        beginScope();
        try {
            initKey();
            assertThrows(TimeoutException.class, () -> successAfter("test", 200).get(0, NANOSECONDS));
        } finally {
            endScope();
        }
    }

    private static class TimeoutListenableFuture<T> implements ListenableFuture<T> {

        private final ListenableFuture<T> originFuture;
        private final Runnable listener;

        TimeoutListenableFuture(ListenableFuture<T> originFuture, Runnable listener) {
            this.originFuture = originFuture;
            this.listener = listener;
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            originFuture.addListener(listener, executor);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return originFuture.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return originFuture.isCancelled();
        }

        @Override
        public boolean isDone() {
            return originFuture.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return originFuture.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return originFuture.get(timeout, unit);
            } catch (Throwable t) {
                if (t instanceof TimeoutException) {
                    listener.run();
                }
                throw t;
            }
        }
    }

    @Test
    void testTimeoutListenableFuture() {
        for (int i = 0; i < 10000; i++) {
            AtomicBoolean timeout = new AtomicBoolean(false);
            TimeoutListenableFuture<String> future = new TimeoutListenableFuture<>(executor.submit(() -> {
                sleepUninterruptibly(10, MILLISECONDS);
                return "haha";
            }), () -> timeout.set(true));
            try {
                future.get(1, NANOSECONDS);
            } catch (Throwable t) {
                // ignore
            }
            assertTrue(timeout.get());
        }
    }

    @Disabled // 目前这个测试用例还不稳定，后面还需要完善和回查
    @Test
    void testCallerTimeoutListener() throws InterruptedException, ExecutionException {
        String expectResult = "hahaha";
        for (int i = 0; i < 10000; i++) {
            System.out.println(i);
            AtomicBoolean timeoutListenerTriggered = new AtomicBoolean(false);
            try {
                String result = retrier.callWithRetry(1, retryNTimes(0, 0, false),
                        () -> new TimeoutListenableFuture<>(executor.submit(() -> {
                            sleepUninterruptibly(ThreadLocalRandom.current().nextInt(2), MILLISECONDS);
                            return expectResult;
                        }), () -> {
                            timeoutListenerTriggered.set(true);
                        })).get(ThreadLocalRandom.current().nextInt(3), MILLISECONDS);
                // 这里验证下没抛 TimeoutException 的时候一定没有调用 timeout listener
                assertEquals(expectResult, result);
                assertFalse(timeoutListenerTriggered.get());
                System.out.println("nothing.");
            } catch (TimeoutException e) {
                System.out.println("timeout by caller.");
            } catch (ExecutionException e) {
                if (Throwables.getRootCause(e) instanceof TimeoutException) {
                    System.out.println("timeout by retrier.");
                    // 这里验证下抛 TimeoutException 的时候一定都调用了 timeout listener
                    assertTrue(timeoutListenerTriggered.get());
                } else {
                    throw e;
                }
            }
        }
    }

    @Test
    void testRetryForException() throws Throwable {
        clearCallTimes();
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future =
                        retrier.callWithRetry(100, retryNTimes(3, 10, false),
                                sleepySuccess(new long[] {300L, 200L, 50L}));
                Assertions.assertDoesNotThrow((ThrowingSupplier<String>) future::get);
            }
            sleepUninterruptibly(1, SECONDS);
        } finally {
            endScope();
        }
        System.out.println(getCallTimes());
        assertEquals(30, getCallTimes());

        clearCallTimes();
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future =
                        retrier.callWithRetry(100, retryNTimes(3, 10, false),
                                sleepySuccess(new long[] {300L, 200L, 50L}));
                future.cancel(false);
            }
            sleepUninterruptibly(1, SECONDS);
        } finally {
            endScope();
        }
        System.out.println(getCallTimes());
        assertEquals(10, getCallTimes());

        clearCallTimes();
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future =
                        retrier.callWithRetry(1, retryNTimes(3, 10, false), exceptionsAndThenSuccess(
                                new Throwable[] {new RuntimeException(), new IllegalStateException()}));
                future.get();
            }
            sleepUninterruptibly(1, SECONDS);
        } finally {
            endScope();
        }
        System.out.println(getCallTimes());
        assertEquals(30, getCallTimes());

        clearCallTimes();
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future =
                        retrier.callWithRetry(100, retryNTimes(3, 10, false), exceptionsAndThenSuccess(
                                new Throwable[] {new RuntimeException(), new IllegalStateException(),
                                        new IllegalArgumentException()}));
                future.cancel(false);
            }
            sleepUninterruptibly(1, SECONDS);
        } finally {
            endScope();
        }
        System.out.println(getCallTimes());
        assertEquals(10, getCallTimes());
    }

    @Test
    void testNoMoreNewRetryAfterCancel() {
        clearCallTimes();
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10, false),
                        () -> successAfter("test", 200));
                assertThrows(TimeoutException.class,
                        () -> assertTimeout(ofMillis(60), () -> {
                            future.get(5, MILLISECONDS);
                        }));
            }
            sleepUninterruptibly(1, SECONDS);
        } finally {
            endScope();
        }
        System.out.println(getCallTimes());
        assertEquals(40, getCallTimes());

        clearCallTimes();
        beginScope();
        try {
            initKey();
            for (int i = 0; i < 10; i++) {
                ListenableFuture<String> future = retrier.callWithRetry(100, retryNTimes(3, 10, false),
                        () -> successAfter("test", 200));
                assertThrows(TimeoutException.class,
                        () -> assertTimeout(ofMillis(60), () -> {
                            future.get(5, MILLISECONDS);
                        }));
                future.cancel(false);
            }
            sleepUninterruptibly(1, SECONDS);
        } finally {
            endScope();
        }
        System.out.println(getCallTimes());
        assertEquals(10, getCallTimes());
    }

    private static final ScopeAsyncRetry directCallbackRetry =
            createScopeAsyncRetry(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(),
                    new ThreadFactoryBuilder() //
                            .setPriority(MAX_PRIORITY) //
                            .setNameFormat("default-directCallbackRetry-%d") //
                            .build()));

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    @Test
    void testPerformance() throws Throwable {
        beginScope();
        try {
            initKey();
            AtomicLong succCount = new AtomicLong(0);
            AtomicLong failCount = new AtomicLong(0);
            long calls = 100000;
            clearCallTimes();
            ConcurrentLinkedQueue<ListenableFuture<String>> futures = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < calls; i++) {
                EXECUTOR_SERVICE.submit(() -> {
                    ListenableFuture<String> future =
                            directCallbackRetry
                                    .callWithRetry(1000, retryNTimes(3, 10, false),
                                            () -> successAfterBySleep("test", 200));
                    addCallback(future, new FutureCallback<String>() {
                        @Override
                        public void onSuccess(@Nullable String result) {
                            succCount.incrementAndGet();
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            failCount.incrementAndGet();
                        }
                    }, directExecutor());
                    futures.add(future);
                });
            }
            sleepUninterruptibly(1, SECONDS);
            System.out.println(futures.size());
            while (futures.peek() != null) {
                Future tmpFuture = futures.poll();
                assertThrows(Throwable.class, tmpFuture::get);
            }
            System.out.println(getCallTimes());
            assertEquals(calls, failCount.get());
            assertEquals(0, succCount.get());
        } finally {
            endScope();
        }
    }

    private static class AbortRetryException extends RuntimeException {
    }

    @Test
    void testAbortRetry() {
        AtomicInteger callTime = new AtomicInteger(0);
        Supplier<ListenableFuture<String>> callFunction = () -> {
            if (callTime.incrementAndGet() > 3) {
                throw new AbortRetryException();
            } else {
                throw new RuntimeException();
            }
        };

        ListenableFuture<String> resultFuture = retrier.callWithRetry(100, new RetryPolicy() {
            private final AtomicInteger retryTime = new AtomicInteger();

            @Override
            public long retry(int retryCount) {
                int rt = retryTime.incrementAndGet();
                if (rt < 10) {
                    return rt * 100;
                } else {
                    return NO_RETRY;
                }
            }

            @Override
            public boolean abortRetry(Throwable t) {
                return t instanceof AbortRetryException;
            }
        }, callFunction::get);

        try {
            resultFuture.get();
        } catch (Throwable t) {
            Assertions.assertTrue(Throwables.getRootCause(t) instanceof AbortRetryException);
        }
        Assertions.assertEquals(4, callTime.get());
    }
}