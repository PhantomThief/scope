package com.github.phantomthief.scope;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.phantomthief.util.ThrowableRunnable;
import com.github.phantomthief.util.ThrowableSupplier;
import com.google.common.annotations.Beta;

/**
 * 自定义Scope，支持如下功能：
 *
 * <ul>
 *  <li>开启一个自定义的Scope，在Scope范围内，可以通过 {@link Scope} 各个方法读写数据</li>
 *  <li>可以通过 {@link #supplyWithExistScope} 或者 {@link #runWithExistScope} 绑定已经存在的scope</li>
 * </ul>
 *
 * 举个栗子：
 * <pre> {@code
 * ScopeKey&lt;String&gt; TEST_KEY = allocate();
 *
 * runWithNewScope(() -> {
 *      TEST_KEY.set("abc");
 *      String result = TEST_KEY.get(); // get "abc"
 *
 *      Scope scope = getCurrentScope();
 *      executor.execute(wrapRunnableExistScope(scope, () -> {
 *          String resultInScope = TEST_KEY.get(); // get "abc"
 *      });
 * });
 * }</pre>
 *
 * TODO: 当前实现是一种比较简易的方式，直接把所有Scope放到一个ThreadLocal里。
 * 实际上这样在使用过程中会有二次hash查询的问题，对性能会有些许的影响，更好的做法是：
 * 直接使用ThreadLocal（也就是使用内部的ThreadLocalMap），同时在Scope拷贝和清理时，维护一个额外的Set，进行ThreadLocal拷贝。
 *
 * 这样的优化考虑是：Scope正常访问的频率很高，而线程切换拷贝的概率比较低。
 * 目前这个实现参考了 GRPC 的 Context API 以及 Spring 的 RequestContext，
 * 相对比较简单，目前效率也可以接受。等到需要榨取性能时再对这个实现动手吧。
 *
 * @author w.vela
 */
public final class Scope {

    private static final Logger logger = LoggerFactory.getLogger(Scope.class);

    private static final SubstituteThreadLocal<Scope> SCOPE_THREAD_LOCAL = MyThreadLocalFactory.create();

    private final ConcurrentMap<ScopeKey<?>, Object> values = new ConcurrentHashMap<>();

    private final ConcurrentMap<ScopeKey<?>, Boolean> enableNullProtections = new ConcurrentHashMap<>();

    @Beta
    public static boolean fastThreadLocalEnabled() {
        try {
            return SCOPE_THREAD_LOCAL.getRealThreadLocal() instanceof NettyFastThreadLocal;
        } catch (Error e) {
            return false;
        }
    }

    /**
     * @return {@code true} if fast thread local was enabled.
     */
    @Beta
    public static boolean tryEnableFastThreadLocal() {
        return setFastThreadLocal(true);
    }

    static boolean setFastThreadLocal(boolean usingFastThreadLocal) {
        // no lock need here, for benchmark friendly
        // unnecessary to copy content of thread local. it's only for switch on initial stage or testing.
        if (usingFastThreadLocal) {
            try {
                if (!(SCOPE_THREAD_LOCAL.getRealThreadLocal() instanceof NettyFastThreadLocal)) {
                    SCOPE_THREAD_LOCAL.setRealThreadLocal(new NettyFastThreadLocal<>());
                    logger.info("change current scope's implements to fast thread local.");
                }
            } catch (Error e) {
                logger.warn("fail to change scope's implements to fast thread local.");
                return false;
            }
        } else {
            if (!(SCOPE_THREAD_LOCAL.getRealThreadLocal() instanceof JdkThreadLocal)) {
                SCOPE_THREAD_LOCAL.setRealThreadLocal(new JdkThreadLocal<>());
                logger.info("change current scope's implements to jdk thread local.");
            }
        }
        return true;
    }

    public static <X extends Throwable> void runWithExistScope(@Nullable Scope scope,
            ThrowableRunnable<X> runnable) throws X {
        supplyWithExistScope(scope, () -> {
            runnable.run();
            return null;
        });
    }

    public static <T, X extends Throwable> T supplyWithExistScope(@Nullable Scope scope,
            ThrowableSupplier<T, X> supplier) throws X {
        Scope oldScope = SCOPE_THREAD_LOCAL.get();
        SCOPE_THREAD_LOCAL.set(scope);
        try {
            return supplier.get();
        } finally {
            if (oldScope != null) {
                SCOPE_THREAD_LOCAL.set(oldScope);
            } else {
                SCOPE_THREAD_LOCAL.remove();
            }
        }
    }

    public static <X extends Throwable> void runWithNewScope(@Nonnull ThrowableRunnable<X> runnable)
            throws X {
        supplyWithNewScope(() -> {
            runnable.run();
            return null;
        });
    }

    public static <T, X extends Throwable> T
            supplyWithNewScope(@Nonnull ThrowableSupplier<T, X> supplier) throws X {
        beginScope();
        try {
            return supplier.get();
        } finally {
            endScope();
        }
    }

    /**
     * 正常应该优先使用 {@link #supplyWithNewScope} 或者 {@link #runWithNewScope}
     *
     * 手工使用beginScope和endScope的场景只有在：
     * <ul>
     *  <li>上面两个方法当需要抛出多个正交异常时会造成不必要的try/catch代码</li>
     *  <li>开始scope和结束scope不在一个代码块中</li>
     * </ul>
     *
     * @throws IllegalStateException if try to start a new scope in an exist scope.
     */
    @Nonnull
    public static Scope beginScope() {
        Scope scope = SCOPE_THREAD_LOCAL.get();
        if (scope != null) {
            throw new IllegalStateException("start a scope in an exist scope.");
        }
        scope = new Scope();
        SCOPE_THREAD_LOCAL.set(scope);
        return scope;
    }

    /**
     * @see #beginScope
     */
    public static void endScope() {
        SCOPE_THREAD_LOCAL.remove();
    }

    /**
     * @return 返回当前请求的 {@link Scope}，当请求线程不在 {@link Scope} 绑定状态时，返回 {@code null}
     */
    @Nullable
    public static Scope getCurrentScope() {
        return SCOPE_THREAD_LOCAL.get();
    }

    public <T> void set(@Nonnull ScopeKey<T> key, T value) {
        if (value != null) {
            values.put(key, value);
        } else {
            values.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(@Nonnull ScopeKey<T> key) {
        T value = (T) values.get(key);
        if (value == null && key.initializer() != null) {
            // 这里不使用computeIfAbsent保证原子性，是因为computeIfAbsent会有几率造成同桶冲撞
            // 而实际上，这里的原子性意义不大，就不浪费时间和精力了
            if (enableNullProtections.containsKey(key)) {
                return null;
            }
            value = key.initializer().get();
            if (value != null) {
                values.put(key, value);
            } else {
                if (key.enableNullProtection()) {
                    enableNullProtections.put(key, true);
                }
            }
        }
        return value == null ? key.defaultValue() : value;
    }
}
