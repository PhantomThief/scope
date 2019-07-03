package com.github.phantomthief.scope;

/**
 * @author w.vela
 * Created on 2019-07-03.
 */
class JdkThreadLocal<T> implements MyThreadLocal<T> {

    private final ThreadLocal<T> threadLocal = new ThreadLocal<>();

    @Override
    public T get() {
        return threadLocal.get();
    }

    @Override
    public void set(T value) {
        threadLocal.set(value);
    }

    @Override
    public void remove() {
        threadLocal.remove();
    }
}
