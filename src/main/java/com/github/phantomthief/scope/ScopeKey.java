package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;

import java.util.function.Supplier;

import javax.annotation.Nonnull;

/**
 * 强类型数据读写的封装
 *
 * <p>
 *  如果多个线程共享了一个Scope，那么他们对于{@link ScopeKey}的get/set调用是操作的同一个值，这一点和{@link ThreadLocal}不一样，{@link ThreadLocal} 每个线程总是访问自己的一份变量。
 *  因而，{@link ScopeKey}也不能在所有场合都无脑替换{@link ThreadLocal}。
 *  更多信息参考 {@link Scope}的文档。
 * </p>
 *
 * @author w.vela
 */
public final class ScopeKey<T> {

    private final T defaultValue;
    private final Supplier<T> initializer;
    private final boolean enableNullProtection;

    private ScopeKey(T defaultValue, Supplier<T> initializer) {
        this(defaultValue, initializer, false);
    }

    private ScopeKey(T defaultValue, Supplier<T> initializer, boolean enableNullProtection) {
        this.defaultValue = defaultValue;
        this.initializer = initializer;
        this.enableNullProtection = enableNullProtection;
    }

    @Nonnull
    public static <T> ScopeKey<T> allocate() {
        return withDefaultValue0(null);
    }

    @Nonnull
    private static <T> ScopeKey<T> withDefaultValue0(T defaultValue) {
        return new ScopeKey<>(defaultValue, null);
    }

    @Nonnull
    public static ScopeKey<Boolean> withDefaultValue(boolean defaultValue) {
        return withDefaultValue0(defaultValue);
    }

    @Nonnull
    public static ScopeKey<Integer> withDefaultValue(int defaultValue) {
        return withDefaultValue0(defaultValue);
    }

    @Nonnull
    public static ScopeKey<Long> withDefaultValue(long defaultValue) {
        return withDefaultValue0(defaultValue);
    }

    @Nonnull
    public static ScopeKey<Double> withDefaultValue(double defaultValue) {
        return withDefaultValue0(defaultValue);
    }

    @Nonnull
    public static ScopeKey<String> withDefaultValue(String defaultValue) {
        return withDefaultValue0(defaultValue);
    }

    @Nonnull
    public static <T extends Enum<T>> ScopeKey<T> withDefaultValue(T defaultValue) {
        return withDefaultValue0(defaultValue);
    }

    /**
     * @param initializer 初始化ScopeKey（仅在Scope有效时）
     * <p>
     * 等效代码:（调用 {@link #get} 时）
     * <p>
     * <pre> {@code
     * T obj = SCOPE_KEY.get();
     * if (obj == null) {
     *     obj = initializer.get();
     *     SCOPE_KEY.set(obj);
     * }
     * return obj;
     * }</pre>
     * <p>
     * 注意，如果 initializer 返回 {@code null}，每次访问时都会重复初始化执行；
     * 虽然该问题是预期外的，但是考虑到业务如果刚好依赖了此 bug，可能直接修复会产生行为异常，并因此产生不容易发现的意外，所以提供了重载版本修正：
     * 对于可能返回 {@code null} 的场景，请使用 {@link #withInitializer(boolean, Supplier)} 版本，并传递参数 {@code true}
     */
    @Nonnull
    public static <T> ScopeKey<T> withInitializer(Supplier<T> initializer) {
        return withInitializer(false, initializer);
    }

    /**
     * @param initializer 初始化ScopeKey（仅在Scope有效时）
     * <p>
     * 等效代码:（调用 {@link #get} 时）
     * <p>
     * <pre> {@code
     * T obj = SCOPE_KEY.get();
     * if (obj == null) {
     *     obj = initializer.get();
     *     SCOPE_KEY.set(obj);
     * }
     * return obj;
     * }</pre>
     */
    @Nonnull
    public static <T> ScopeKey<T> withInitializer(boolean enableNullProtection, Supplier<T> initializer) {
        return new ScopeKey<>(null, initializer, enableNullProtection);
    }

    public T get() {
        Scope currentScope = getCurrentScope();
        if (currentScope == null) {
            return defaultValue();
        }
        return currentScope.get(this);
    }

    Supplier<T> initializer() {
        return initializer;
    }

    T defaultValue() {
        return defaultValue;
    }

    boolean enableNullProtection() {
        return enableNullProtection;
    }

    /**
     * @return {@code true} if in a scope and set success.
     */
    public boolean set(T value) {
        Scope currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.set(this, value);
            return true;
        } else {
            return false;
        }
    }
}