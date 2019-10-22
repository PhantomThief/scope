package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.runWithNewScope;
import static com.github.phantomthief.scope.ScopeUtils.trackLongCost;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author w.vela
 * Created on 2019-10-22.
 */
class ScopeUtilsTest {

    private static final Logger logger = LoggerFactory.getLogger(ScopeUtilsTest.class);
    private final ScopeKey<String> key = ScopeKey.allocate();
    private final AtomicLong track = new AtomicLong();

    @Test
    void test() {
        runWithNewScope(() -> {
            key.set("test");
            assertEquals("test", key.get());
            logger.info("starting...");

            try (LongCostTrack context = trackLongCost(ofSeconds(3), this::setAtomicLong)) {
                longCost(1);
            }
            assertEquals(track.get(), 0);

            try (LongCostTrack context = trackLongCost(ofSeconds(3), this::setAtomicLong)) {
                longCost(5);
            }
            assertTrue(track.get() > 0);
        });
    }

    private void setAtomicLong(Duration t) {
        logger.info("setting track:{}", t);
        assertTrue(t.toNanos() > SECONDS.toNanos(3));
        assertEquals("test", key.get());
        track.set(t.toNanos());
    }

    private void longCost(long seconds) {
        sleepUninterruptibly(seconds, SECONDS);
    }
}