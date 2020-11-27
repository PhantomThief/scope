package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.runWithExistScope;
import static com.github.phantomthief.scope.Scope.runWithNewScope;
import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.github.phantomthief.scope.ScopeKey.withDefaultValue;
import static com.github.phantomthief.scope.ScopeKey.withInitializer;
import static com.github.phantomthief.scope.ScopeUtils.runAsyncWithCurrentScope;
import static com.github.phantomthief.scope.ScopeUtils.supplyAsyncWithCurrentScope;
import static com.github.phantomthief.util.MoreFunctions.throwing;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author w.vela
 */
class ScopeTest {

    private static final Logger logger = LoggerFactory.getLogger(ScopeTest.class);
    private static final ScopeKey<Integer> TEST_KEY = allocate();

    @Test
    void testScope() {
        ExecutorService executorService = newBlockingThreadPool(20, "main-%d");
        ExecutorService anotherExecutor = newBlockingThreadPool(20, "another-%d");
        ListeningExecutorService anotherExecutor2 = listeningDecorator(
                newBlockingThreadPool(20, "another-%d"));
        for (int i = 0; i < 10; i++) {
            int j = i;
            supplyAsyncWithCurrentScope(() -> {
                runWithNewScope(() -> {
                    TEST_KEY.set(j);
                    Integer fromScope = TEST_KEY.get();
                    assertEquals(j, fromScope.intValue());
                    for (int k = 0; k < 10; k++) {
                        runAsyncWithCurrentScope(() -> {
                            Integer fromScope1 = TEST_KEY.get();
                            assertEquals(j, fromScope1.intValue());
                        }, anotherExecutor);
                        runAsyncWithCurrentScope(() -> {
                            Integer fromScope1 = TEST_KEY.get();
                            assertEquals(j, fromScope1.intValue());
                        }, anotherExecutor2);
                    }
                });
                return "NONE";
            }, executorService);
            assertNull(TEST_KEY.get());
        }
        shutdownAndAwaitTermination(executorService, 1, DAYS);
        shutdownAndAwaitTermination(anotherExecutor, 1, DAYS);
        shutdownAndAwaitTermination(anotherExecutor2, 1, DAYS);
    }

    @Test
    void testInit() {
        ScopeKey<Object> test2 = withInitializer(Object::new);
        assertNull(test2.get());
        runWithNewScope(() -> {
            Object x = test2.get();
            assertEquals(test2.get(), x);
            Scope currentScope = getCurrentScope();
            assertEquals(currentScope.get(test2), x);
        });
    }

    @Test
    void testDefaultValue() {
        ScopeKey<String> key1 = withDefaultValue("test");
        assertEquals(key1.get(), "test"); // anyway, default value is ok.
        runWithNewScope(() -> {
            assertEquals(key1.get(), "test");
            Scope currentScope = getCurrentScope();
            assertEquals(currentScope.get(key1), "test");
            key1.set("t1");
            assertEquals(key1.get(), "t1");
            assertEquals(currentScope.get(key1), "t1");
        });
    }

    @Test
    void testSet() {
        ScopeKey<String> key1 = allocate();
        assertFalse(key1.set("test"));
        assertNull(key1.get());
        runWithNewScope(() -> {
            assertTrue(key1.set("test"));
            assertEquals(key1.get(), "test");
        });
    }

    @Test
    void testDuplicateStartScope() {
        runWithNewScope(() -> {
            assertThrows(IllegalStateException.class, () -> runWithNewScope(() -> {
                Assertions.fail("");
            }));
        });
    }

    @Test
    void testRemoveKey() {
        runWithNewScope(() -> {
            TEST_KEY.set(999);
            assertEquals(TEST_KEY.get(), Integer.valueOf(999));
            TEST_KEY.set(null);
            assertNull(TEST_KEY.get());
        });
    }

    @Test
    void testScopeOverride() {
        Scope[] scope = { null };
        Thread thread = new Thread(() -> {
            runWithNewScope(() -> {
                TEST_KEY.set(2);
                assertEquals(TEST_KEY.get(), Integer.valueOf(2));
                while (scope[0] == null) {
                    sleepUninterruptibly(10, MILLISECONDS);
                    runWithExistScope(scope[0], () -> {
                        assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                    });
                }
                assertEquals(TEST_KEY.get(), Integer.valueOf(2));
            });
        });
        thread.start();
        try {
            runWithNewScope(() -> {
                TEST_KEY.set(1);
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                Scope currentScope = getCurrentScope();
                scope[0] = currentScope;
                thread.join();
            });
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    void testRestoreOldScope() {
        Scope[] scope = { null };
        new Thread(() -> {
            runWithNewScope(() -> {
                TEST_KEY.set(2);
                scope[0] = getCurrentScope();
            });
        }).start();
        runWithNewScope(() -> {
            TEST_KEY.set(1);
            assertEquals(TEST_KEY.get(), Integer.valueOf(1));
            while (scope[0] == null) {
                sleepUninterruptibly(10, MILLISECONDS);
            }
            runWithExistScope(scope[0], () -> assertEquals(TEST_KEY.get(), Integer.valueOf(2)));
            assertEquals(TEST_KEY.get(), Integer.valueOf(1));
        });
    }

    @Test
    void testScopeExecutor() throws Exception {
        ExecutorService executorService = ScopeThreadPoolExecutor.newFixedThreadPool(10);
        runWithNewScope(() -> {
            TEST_KEY.set(1);
            assertEquals(TEST_KEY.get(), Integer.valueOf(1));
            executorService.execute(() -> {
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
            });
            executorService.submit(() -> {
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                return 1;
            }).get();
            List<Callable<Integer>> callableList = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int j = i;
                callableList.add(() -> {
                    assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                    return j;
                });
            }
            logger.info("invoke all:{}", executorService.invokeAll(callableList).stream()
                    .map(f -> throwing(f::get))
                    .collect(toList()));
            logger.info("supply async:{}", supplyAsync(() -> {
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                return 1;
            }, executorService).get());
        });
        shutdownAndAwaitTermination(executorService, 1, DAYS);
    }

    private ExecutorService newBlockingThreadPool(int thread, String name) {
        ExecutorService executor = newFixedThreadPool(thread, new ThreadFactoryBuilder()
                .setNameFormat(name)
                .build());
        ((ThreadPoolExecutor) executor).setRejectedExecutionHandler(new CallerRunsPolicy());
        return executor;
    }

    private static class ScopeThreadPoolExecutor extends ThreadPoolExecutor {

        ScopeThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                TimeUnit unit, BlockingQueue<Runnable> workQueue) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        }

        static ScopeThreadPoolExecutor newFixedThreadPool(int nThreads) {
            return new ScopeThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            Scope scope = getCurrentScope();
            assertNotNull(scope);
            super.execute(() -> {
                Scope scope1 = getCurrentScope();
                assertNull(scope1);
                runWithExistScope(scope, command::run);
            });
        }
    }

    @Test
    void testFutureCallback() throws Throwable {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean flag = new AtomicBoolean(false);

        runWithNewScope(() -> {
            TEST_KEY.set(1);
            SettableFuture<Boolean> future = SettableFuture.create();
            Futures.addCallback(future, ScopeUtils.wrapWithScope(new FutureCallback<Boolean>() {
                @Override
                public void onSuccess(@Nullable Boolean aBoolean) {
                    try {
                        Integer integer = TEST_KEY.get();
                        if (integer == 1) {
                            flag.set(true);
                        }
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(Throwable throwable) {
                    try {
                        Integer integer = TEST_KEY.get();
                        if (integer == 1) {
                            flag.set(true);
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            }), executor);
            future.setException(new Throwable());
            latch.await();
            Assertions.assertTrue(flag.get());
        });
    }
}