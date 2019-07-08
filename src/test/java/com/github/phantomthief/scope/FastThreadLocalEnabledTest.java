package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.MyThreadLocalFactory.USE_FAST_THREAD_LOCAL;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * @author w.vela
 * Created on 2019-07-08.
 */
class FastThreadLocalEnabledTest {

    @Test
    void test() {
        System.setProperty(USE_FAST_THREAD_LOCAL, "true");
        assertTrue(Scope.fastThreadLocalEnabled());
    }
}
