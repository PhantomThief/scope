package com.github.phantomthief.scope;

/**
 * 当请求结束时，调用{@link #close()} 关闭追踪
 *
 * @author w.vela
 * Created on 2019-10-22.
 */
public interface LongCostTrack extends AutoCloseable {

    @Override
    void close(); // override for remove exception declaration.
}
