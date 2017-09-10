package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.runWithExistScope;
import static com.github.phantomthief.scope.Scope.runWithNewScope;
import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.github.phantomthief.scope.ScopeKey.withDefaultValue;
import static com.github.phantomthief.scope.ScopeKey.withInitializer;
import static com.github.phantomthief.scope.ScopeUtils.runAsyncWithCurrentScope;
import static com.github.phantomthief.scope.ScopeUtils.supplyAsyncWithCurrentScope;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;

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
                Scope currentScope = Scope.getCurrentScope();
                scope[0] = currentScope;
                thread.join();
            });
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("main test ok.");
    }

    private ExecutorService newBlockingThreadPool(int thread, String name) {
        ExecutorService executor = newFixedThreadPool(thread, new ThreadFactoryBuilder() //
                .setNameFormat(name) //
                .build());
        ((ThreadPoolExecutor) executor).setRejectedExecutionHandler(new CallerRunsPolicy());
        return executor;
    }
}