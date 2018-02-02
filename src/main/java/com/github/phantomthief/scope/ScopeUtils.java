package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.runWithExistScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

/**
 * @author w.vela
 */
public final class ScopeUtils {

    private ScopeUtils() {
    }

    private static Runnable wrapRunnableExistScope(@Nullable Scope scope,
            @Nonnull Runnable runnable) {
        return () -> runWithExistScope(scope, runnable::run);
    }

    private static <T> Supplier<T> wrapSupplierExistScope(@Nullable Scope scope,
            @Nonnull Supplier<T> supplier) {
        return () -> supplyWithExistScope(scope, supplier::get);
    }

    public static void runAsyncWithCurrentScope(@Nonnull Runnable runnable,
            @Nonnull Executor executor) {
        executor.execute(wrapRunnableExistScope(getCurrentScope(), runnable));
    }

    public static ListenableFuture<?> runAsyncWithCurrentScope(@Nonnull Runnable runnable,
            @Nonnull ListeningExecutorService executor) {
        return executor.submit(wrapRunnableExistScope(getCurrentScope(), runnable));
    }

    public static <U> Future<U> supplyAsyncWithCurrentScope(@Nonnull Supplier<U> supplier,
            @Nonnull ExecutorService executor) {
        return executor.submit(() -> wrapSupplierExistScope(getCurrentScope(), supplier).get());
    }

    public static <U> ListenableFuture<U> supplyAsyncWithCurrentScope(@Nonnull Supplier<U> supplier,
            @Nonnull ListeningExecutorService executor) {
        return executor.submit(() -> wrapSupplierExistScope(getCurrentScope(), supplier).get());
    }
}
