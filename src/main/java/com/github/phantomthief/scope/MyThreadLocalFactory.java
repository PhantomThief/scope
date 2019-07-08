package com.github.phantomthief.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author w.vela
 * Created on 2019-07-03.
 */
class MyThreadLocalFactory {

    private static final Logger logger = LoggerFactory.getLogger(MyThreadLocalFactory.class);

    static final String USE_FAST_THREAD_LOCAL = "USE_FAST_THREAD_LOCAL";

    static <T> MyThreadLocal<T> create() {
        if (Boolean.getBoolean(USE_FAST_THREAD_LOCAL)) {
            try {
                NettyFastThreadLocal<T> nettyFastThreadLocal = new NettyFastThreadLocal<>();
                logger.info("using fast thread local as scope implements.");
                return nettyFastThreadLocal;
            } catch (Error e) {
                logger.warn("cannot use fast thread local as scope implements.");
            }
        }
        // TODO auto adaptive thread local between jdk thread local and netty fast thread local?
        return new JdkThreadLocal<>();
    }

    static boolean fastThreadLocalEnabled() {
        if (Boolean.getBoolean(USE_FAST_THREAD_LOCAL)) {
            try {
                new NettyFastThreadLocal<>();
                return true;
            } catch (Error e) {
                // ignore
            }
        }
        return false;
    }
}
