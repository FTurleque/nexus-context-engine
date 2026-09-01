package com.nexus.index;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exécute les intégrations externes dans un pool partagé, daemon, borné en temps et en concurrence.
 *
 * <p>Un timeout interrompt le worker et rend immédiatement le contrôle à NEXUS. Une intégration
 * tierce peut ignorer l'interruption ; le pool global empêche toutefois ce comportement de créer
 * un nombre non borné de threads. Une fois la capacité atteinte, les nouvelles tâches externes
 * sont rejetées explicitement au lieu d'épuiser progressivement la JVM. Les providers de processus
 * doivent en plus nettoyer leur processus enfant lorsqu'ils observent l'interruption.</p>
 */
public final class ExternalTaskRunner {

    static final int MAX_CONCURRENT_TASKS = 8;
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0,
            MAX_CONCURRENT_TASKS,
            30L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> Thread.ofPlatform()
                    .daemon(true)
                    .name("nexus-external-" + THREAD_SEQUENCE.incrementAndGet())
                    .unstarted(runnable),
            new ThreadPoolExecutor.AbortPolicy());

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

        Future<T> future;
        try {
            future = EXECUTOR.submit(task);
        } catch (RejectedExecutionException saturated) {
            throw new IOException(
                    "Capacité des tâches externes saturée (maximum " + MAX_CONCURRENT_TASKS
                            + " tâches simultanées) ; réessayez après la fin des providers actifs",
                    saturated);
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new IOException(
                    "La tâche externe " + taskName + " a dépassé le timeout global de "
                            + timeout.toMillis() + " ms",
                    timeoutFailure);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
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
}
