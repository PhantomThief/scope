package com.github.phantomthief.scope;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nonnegative;

/**
 * @author w.vela
 * Created on 2019-01-07.
 */
public interface RetryPolicy {

    long NO_RETRY = -1L;

    static RetryPolicy retryNTimes(int times) {
        return retryNTimes(times, 0);
    }

    static RetryPolicy retryNTimes(int times, @Nonnegative long delayInMs) {
        checkArgument(delayInMs >= 0, "delayInMs must be non-negative.");
        return (retryCount) -> retryCount <= times ? delayInMs : NO_RETRY;
    }

    /**
     * @param retryCount 当前重试的次数（1为第一次重试）
     * @return 下次重试的间隔时间，或者返回 {@link #NO_RETRY}
     */
    long retry(int retryCount);

    default boolean cancelExceptionalFuture() {
        return true;
    }
}