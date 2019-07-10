package com.github.phantomthief.scope;

import javax.annotation.Nonnull;

/**
 * @author w.vela
 * Created on 2019-07-09.
 */
class SubstituteThreadLocal<T> implements MyThreadLocal<T> {

    private MyThreadLocal<T> realThreadLocal;

    SubstituteThreadLocal(@Nonnull MyThreadLocal<T> realThreadLocal) {
        this.realThreadLocal = realThreadLocal;
    }

    @Nonnull
    MyThreadLocal<T> getRealThreadLocal() {
        return realThreadLocal;
    }

    void setRealThreadLocal(@Nonnull MyThreadLocal<T> realThreadLocal) {
        this.realThreadLocal = realThreadLocal;
    }

    @Override
    public T get() {
        return realThreadLocal.get();
    }

    @Override
    public void set(T value) {
        realThreadLocal.set(value);
    }

    @Override
    public void remove() {
        realThreadLocal.remove();
    }
}
