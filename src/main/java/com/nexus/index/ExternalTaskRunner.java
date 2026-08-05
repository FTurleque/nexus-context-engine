package com.nexus.index;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Exécute les intégrations externes dans un worker daemon borné en temps.
 *
 * <p>Contrairement à la fermeture bloquante d'un executor en try-with-resources,
 * un timeout rend immédiatement le contrôle à NEXUS après interruption du worker.
 * Une intégration qui ignore l'interruption peut continuer en arrière-plan sur son
 * thread daemon ; elle ne peut toutefois plus bloquer l'indexation ni empêcher
 * l'arrêt de la JVM. Les providers de processus doivent en plus nettoyer leur
 * processus enfant lorsqu'ils observent l'interruption.</p>
 */
public final class ExternalTaskRunner {

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

        String safeName = taskName.replaceAll("[^A-Za-z0-9._-]", "-");
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
                Thread.ofPlatform()
                        .daemon(true)
                        .name("nexus-external-" + safeName)
                        .unstarted(runnable));
        Future<T> future = executor.submit(task);
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
        } finally {
            executor.shutdownNow();
        }
    }
}
