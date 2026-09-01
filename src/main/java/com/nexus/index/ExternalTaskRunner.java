package com.nexus.index;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exécute les intégrations externes dans des workers daemon bornés en temps et en concurrence.
 *
 * <p>Un timeout interrompt le worker et rend immédiatement le contrôle à NEXUS. Une intégration
 * tierce peut ignorer l'interruption ; le sémaphore global empêche toutefois ce comportement de
 * créer un nombre non borné de threads. La capacité n'est rendue que lorsque le worker termine
 * réellement. Une fois la limite atteinte, les nouvelles tâches sont rejetées explicitement au
 * lieu d'épuiser progressivement la JVM.</p>
 */
public final class ExternalTaskRunner {

    static final int MAX_CONCURRENT_TASKS = 8;
    private static final Semaphore CAPACITY = new Semaphore(MAX_CONCURRENT_TASKS);
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private final Duration timeout;

    public ExternalTaskRunner(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
    }

    public Duration timeout() {
        return timeout;
    }

    public <T> T run(String taskName, Callable<T> task) throws IOException {
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(task, "task");

        if (!CAPACITY.tryAcquire()) {
            throw new IOException(
                    "Capacité des tâches externes saturée (maximum " + MAX_CONCURRENT_TASKS
                            + " tâches simultanées) ; réessayez après la fin des providers actifs");
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("nexus-external-" + THREAD_SEQUENCE.incrementAndGet())
                .unstarted(() -> execute(task, result));
        try {
            worker.start();
        } catch (RuntimeException | Error startupFailure) {
            CAPACITY.release();
            throw startupFailure;
        }

        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            worker.interrupt();
            throw new IOException(
                    "La tâche externe " + taskName + " a dépassé le timeout global de "
                            + timeout.toMillis() + " ms",
                    timeoutFailure);
        } catch (InterruptedException interrupted) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("La tâche externe " + taskName + " a été interrompue", interrupted);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("La tâche externe " + taskName + " a échoué", cause);
        }
    }

    private static <T> void execute(Callable<T> task, CompletableFuture<T> result) {
        try {
            result.complete(task.call());
        } catch (Exception failure) {
            result.completeExceptionally(failure);
        } catch (Error error) {
            result.completeExceptionally(error);
        } finally {
            CAPACITY.release();
        }
    }
}
