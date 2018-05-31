package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.ScopeKey.allocate;
import static com.github.phantomthief.scope.ScopeKey.withDefaultValue;
import static com.github.phantomthief.scope.ScopeKeyTest.TestEnum.ABC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * @author w.vela
 * Created on 2018-05-30.
 */
class ScopeKeyTest {

    private static final ScopeKey<Integer> INT_SCOPE_KEY = withDefaultValue(1);
    private static final ScopeKey<Long> LONG_SCOPE_KEY = withDefaultValue(1L);
    private static final ScopeKey<Double> DOUBLE_SCOPE_KEY = withDefaultValue(0.0D);
    private static final ScopeKey<String> STRING_SCOPE_KEY = withDefaultValue("abc");
    private static final ScopeKey<TestEnum> ENUM_SCOPE_KEY = withDefaultValue(ABC);
    private static final ScopeKey<Boolean> BOOLEAN_SCOPE_KEY = withDefaultValue(true);
    private static final ScopeKey<String> SOME_SCOPE_KEY = allocate();

    @Test
    void testApiCompile() {
        assertEquals(Integer.valueOf(1), INT_SCOPE_KEY.get());
        assertEquals(Long.valueOf(1), LONG_SCOPE_KEY.get());
        assertEquals(Double.valueOf(0.0D), DOUBLE_SCOPE_KEY.get());
        assertEquals("abc", STRING_SCOPE_KEY.get());
        assertSame(ABC, ENUM_SCOPE_KEY.get());
        assertTrue(BOOLEAN_SCOPE_KEY.get());
        assertNull(SOME_SCOPE_KEY.get());
    }

    enum TestEnum {
        ABC
    }
}
