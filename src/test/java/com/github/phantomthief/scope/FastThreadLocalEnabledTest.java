package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.MyThreadLocalFactory.USE_FAST_THREAD_LOCAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * @author w.vela
 * Created on 2019-07-08.
 */
class FastThreadLocalEnabledTest {

    /**
     * 由于netty依赖是optional的，所以这个测试用例只在IDEA中手工运行确认
     */
    @Disabled
    @Test
    void test() {
        System.setProperty(USE_FAST_THREAD_LOCAL, "true");
        assertTrue(Scope.fastThreadLocalEnabled());
        assertTrue(Scope.setFastThreadLocal(false));
        assertFalse(Scope.fastThreadLocalEnabled());
        assertTrue(Scope.tryEnableFastThreadLocal());
        assertTrue(Scope.fastThreadLocalEnabled());
    }
}
