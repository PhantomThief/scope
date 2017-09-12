package com.github.phantomthief.scope;

import static com.github.phantomthief.scope.Scope.getCurrentScope;
import static com.github.phantomthief.scope.Scope.runWithExistScope;
import static com.github.phantomthief.scope.Scope.supplyWithExistScope;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    /**
     * replace for {@link Executor#execute(Runnable)}, {@link java.util.concurrent.ExecutorService#submit(Runnable)},
     * {@link java.util.concurrent.ExecutorService#submit(Runnable, Object)} or {@link CompletableFuture#runAsync(Runnable, Executor)}
     */
    public static CompletableFuture<Void> runAsyncWithCurrentScope(@Nonnull Runnable runnable,
            @Nonnull Executor executor) {
        return runAsync(wrapRunnableExistScope(getCurrentScope(), runnable), executor);
    }

    /**
     * replace for {@link java.util.concurrent.ExecutorService#submit(Callable)} or {@link CompletableFuture#supplyAsync(Supplier, Executor)}
     */
    public static <U> CompletableFuture<U>
            supplyAsyncWithCurrentScope(@Nonnull Supplier<U> supplier, @Nonnull Executor executor) {
        return supplyAsync(wrapSupplierExistScope(getCurrentScope(), supplier), executor);
    }
}
