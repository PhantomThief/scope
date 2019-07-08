package com.github.phantomthief.scope;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * @author w.vela
 * Created on 2019-07-08.
 */
public class FastThreadLocalExecutor extends ThreadPoolExecutor {

    private static final DefaultThreadFactory NETTY_FACTORY = new DefaultThreadFactory(FastThreadLocalExecutor.class);

    public FastThreadLocalExecutor(int nThread, String prefix) {
        super(nThread, nThread, 0L, MILLISECONDS, new LinkedBlockingQueue<>(), NETTY_FACTORY::newThread);
    }
}
