package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.runWithNewScope;
import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.github.phantomthief.scope.ScopeKey.withDefaultValue;
import static com.github.phantomthief.scope.ScopeKey.withInitializer;
import static com.github.phantomthief.scope.ScopeUtils.runAsyncWithCurrentScope;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
            executorService.execute(() -> { //
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
            });
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

    private ExecutorService newBlockingThreadPool(int thread, String name) {
        ExecutorService executor = newFixedThreadPool(thread, new ThreadFactoryBuilder() //
                .setNameFormat(name) //
                .build());
        ((ThreadPoolExecutor) executor).setRejectedExecutionHandler(new CallerRunsPolicy());
        return executor;
    }
}