package com.github.phantomthief.scope;

import io.netty.util.concurrent.FastThreadLocal;

/**
 * @author w.vela
 * Created on 2019-07-03.
 */
class NettyFastThreadLocal<T> implements MyThreadLocal<T> {

    private final FastThreadLocal<T> fastThreadLocal = new FastThreadLocal<>();

    @Override
    public T get() {
        return fastThreadLocal.get();
    }

    @Override
    public void set(T value) {
        fastThreadLocal.set(value);
    }

    @Override
    public void remove() {
        fastThreadLocal.remove();
    }
}
