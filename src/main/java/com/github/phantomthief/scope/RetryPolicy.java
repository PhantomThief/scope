package com.github.phantomthief.scope;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nonnegative;

/**
 * @author w.vela
 * Created on 2019-01-07.
 */
public interface RetryPolicy {

    long NO_RETRY = -1L;

    static RetryPolicy noRetry() {
        return retryNTimes(0);
    }

    static RetryPolicy retryNTimes(int times) {
        return retryNTimes(times, 0);
    }

    static RetryPolicy retryNTimes(int times, @Nonnegative long delayInMs) {
        return retryNTimes(times, delayInMs, true);
    }

    static RetryPolicy retryNTimes(int times, @Nonnegative long delayInMs, boolean hedge) {
        checkArgument(delayInMs >= 0, "delayInMs must be non-negative.");
        return new RetryPolicy() {

            @Override
            public long retry(int retryCount) {
                return retryCount <= times ? delayInMs : NO_RETRY;
            }

            @Override
            public boolean hedge() {
                return hedge;
            }
        };
    }

    /**
     * @param retryCount 当前重试的次数（1为第一次重试）
     * @return 下次重试的间隔时间，或者返回 {@link #NO_RETRY}
     */
    long retry(int retryCount);

    /**
     * 返回true则不cancel任何一次重试，重试过程中任何一次返回成功都拿来做最终结果
     * 返回false则开始下一次重试时，之前超时的请求就算后来结果成功返回也没有用
     */
    default boolean hedge() {
        return true;
    }

    default boolean triggerGetOnTimeout() {
        return true;
    }
}