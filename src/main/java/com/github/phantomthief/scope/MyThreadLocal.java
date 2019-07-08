package com.github.phantomthief.scope;

/**
 * @author w.vela
 * Created on 2019-07-03.
 */
interface MyThreadLocal<T> {

    T get();

    void set(T value);

    void remove();
}
