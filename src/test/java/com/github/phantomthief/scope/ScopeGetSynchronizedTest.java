package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.SCOPE_USE_SYNCHRONIZED_PARAM;
import static com.github.phantomthief.scope.Scope.runWithNewScope;
import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.github.phantomthief.scope.ScopeKey.withInitializer;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.phantomthief.scope.ScopeTest.ScopeThreadPoolExecutor;

/**
 * @author lixinyan <lixinyan@kuaishou.com>
 * Created on 2023-05-16
 */
public class ScopeGetSynchronizedTest {

    @BeforeAll
    public static void setUp() {
        System.setProperty(SCOPE_USE_SYNCHRONIZED_PARAM, "true");
    }

    @Test
    void testGetSynchronized() {
        AtomicInteger counter = new AtomicInteger();
        ScopeKey<Object> scopeKey = withInitializer(false, () -> {
            try {
                Thread.sleep(10);
                counter.incrementAndGet();
            } catch (Exception ignored) {

            }
            return "abc";
        });
        assertNull(scopeKey.get());
        assertEquals(0, counter.get());
        ExecutorService executorService = ScopeThreadPoolExecutor.newFixedThreadPool(100);
        runWithNewScope(() -> {
            IntStream.range(0, 100).forEach(i -> {
                executorService.execute(() -> {
                    assertEquals("abc", scopeKey.get());
                });
            });
            shutdownAndAwaitTermination(executorService, 1, DAYS);
        });
        assertEquals(1, counter.get());

        ScopeKey<String> scopeKey2 = allocate();
        assertFalse(scopeKey2.set("def"));
        assertNull(scopeKey2.get());
        runWithNewScope(() -> {
            assertTrue(scopeKey2.set("def"));
            assertEquals(scopeKey2.get(), "def");
        });
    }
}
