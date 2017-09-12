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
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author w.vela
 */
public class ScopeTest {

    private static final ScopeKey<Integer> TEST_KEY = allocate();

    @Test
    public void testScope() {
        ExecutorService executorService = newBlockingThreadPool(20, "main-%d");
        ExecutorService anotherExecutor = newBlockingThreadPool(20, "another-%d");
        for (int i = 0; i < 10; i++) {
            int j = i;
            supplyAsyncWithCurrentScope(() -> { //
                runWithNewScope(() -> {
                    System.out.println("i'm in a new scope, my id:" + j);
                    TEST_KEY.set(j);
                    Integer fromScope = TEST_KEY.get();
                    assertEquals(j, fromScope.intValue());
                    for (int k = 0; k < 10; k++) {
                        runAsyncWithCurrentScope(() -> {
                            Integer fromScope1 = TEST_KEY.get();
                            System.out.println(
                                    "i'm in exist scope id:" + j + ", from scope:" + fromScope1);
                            assertEquals(j, fromScope1.intValue());
                        }, anotherExecutor);
                    }
                });
                return "NONE";
            }, executorService);
            assertNull(TEST_KEY.get());
        }
        shutdownAndAwaitTermination(executorService, 1, DAYS);
        shutdownAndAwaitTermination(anotherExecutor, 1, DAYS);
        System.out.println("fin.");
    }

    @Test
    public void testInit() {
        ScopeKey<Object> test2 = withInitializer(Object::new);
        assertNull(test2.get());
        runWithNewScope(() -> {
            Object x = test2.get();
            System.out.println(x);
            assertEquals(test2.get(), x);
            Scope currentScope = getCurrentScope();
            assertEquals(currentScope.get(test2), x);
        });
    }

    @Test
    public void testDefaultValue() {
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
    public void testSet() {
        ScopeKey<String> key1 = allocate();
        assertFalse(key1.set("test"));
        assertNull(key1.get());
        runWithNewScope(() -> {
            assertTrue(key1.set("test"));
            assertEquals(key1.get(), "test");
        });
    }

    @Test
    public void testDuplicateStartScope() {
        runWithNewScope(() -> {
            try {
                runWithNewScope(() -> {
                    fail();
                });
            } catch (IllegalStateException e) {
                assertTrue(true);
            }
        });
    }

    @Test
    public void testRemoveKey() {
        runWithNewScope(() -> {
            TEST_KEY.set(999);
            assertEquals(TEST_KEY.get(), Integer.valueOf(999));
            TEST_KEY.set(null);
            assertNull(TEST_KEY.get());
        });
    }

    @Test
    public void testScopeOverride() {
        Scope[] scope = { null };
        Thread thread = new Thread(() -> {
            runWithNewScope(() -> {
                TEST_KEY.set(2);
                assertEquals(TEST_KEY.get(), Integer.valueOf(2));
                while (scope[0] == null) {
                    sleepUninterruptibly(10, MILLISECONDS);
                    runWithExistScope(scope[0], () -> { //
                        assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                    });
                }
                assertEquals(TEST_KEY.get(), Integer.valueOf(2));
                System.out.println("thread test ok.");
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
        System.out.println("main test ok.");
    }

    @Test
    public void testRestoreOldScope() {
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
    public void testScopeExecutor() throws Exception {
        ExecutorService executorService = ScopeThreadPoolExecutor.newFixedThreadPool(10);
        runWithNewScope(() -> {
            TEST_KEY.set(1);
            assertEquals(TEST_KEY.get(), Integer.valueOf(1));
            executorService.execute(() -> {
                System.out.println("execute:" + TEST_KEY.get());
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
            });
            System.out.println(executorService.submit(() -> {
                System.out.println("submit:" + TEST_KEY.get());
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                return 1;
            }).get());
            List<Callable<Integer>> callableList = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int j = i;
                callableList.add(() -> {
                    assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                    return j;
                });
            }
            System.out.println("invoke all:" + executorService.invokeAll(callableList).stream() //
                    .map(f -> throwing(f::get)) //
                    .collect(toList()));
            System.out.println("supply async:" + supplyAsync(() -> { //
                assertEquals(TEST_KEY.get(), Integer.valueOf(1));
                return 1;
            }, executorService).get());
        });
        shutdownAndAwaitTermination(executorService, 1, DAYS);
    }

    private ExecutorService newBlockingThreadPool(int thread, String name) {
        ExecutorService executor = newFixedThreadPool(thread, new ThreadFactoryBuilder() //
                .setNameFormat(name) //
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
                    new LinkedBlockingQueue<Runnable>());
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
}