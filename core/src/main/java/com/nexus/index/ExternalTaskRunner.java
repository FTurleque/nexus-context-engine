package com.nexus.index;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
 *
 * <p>Un circuit-breaker est maintenu par nom de tâche : lorsqu'un worker a dépassé son timeout et
 * reste vivant, NEXUS refuse de relancer la même intégration jusqu'à sa terminaison réelle. Cela
 * empêche un provider non coopératif de consommer plusieurs permits globaux par relances répétées.
 * {@link #status()} expose l'occupation et le nombre de workers timeoutés pour la supervision.</p>
 */
public final class ExternalTaskRunner {

    static final int MAX_CONCURRENT_TASKS = 8;
    static final Duration MAX_TIMEOUT = Duration.ofHours(1);
    private static final Semaphore CAPACITY = new Semaphore(MAX_CONCURRENT_TASKS);
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();
    private static final ConcurrentMap<String, Thread> TIMED_OUT_TASKS = new ConcurrentHashMap<>();

    private final Duration timeout;

    public ExternalTaskRunner(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "timeout must be greater than zero and at most " + MAX_TIMEOUT.toSeconds() + " seconds");
        }
    }

    public Duration timeout() {
        return timeout;
    }

    public static Status status() {
        pruneCompletedTimedOutTasks();
        return new Status(
                MAX_CONCURRENT_TASKS - CAPACITY.availablePermits(),
                TIMED_OUT_TASKS.size());
    }

    public <T> T run(String taskName, Callable<T> task) throws IOException {
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(task, "task");

        Thread timedOutWorker = liveTimedOutWorker(taskName);
        if (timedOutWorker != null) {
            throw new IOException(
                    "Circuit-breaker ouvert pour la tâche externe " + taskName
                            + " : le worker précédent a dépassé son timeout et n'est pas encore terminé");
        }

        if (!CAPACITY.tryAcquire()) {
            throw new IOException(
                    "Capacité des tâches externes saturée (maximum " + MAX_CONCURRENT_TASKS
                            + " tâches simultanées) ; réessayez après la fin des providers actifs");
        }

        FutureTask<T> result = new FutureTask<>(task);
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("nexus-external-" + THREAD_SEQUENCE.incrementAndGet())
                .unstarted(() -> execute(taskName, result));
        try {
            worker.start();
        } catch (RuntimeException | Error startupFailure) {
            CAPACITY.release();
            throw startupFailure;
        }

        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            TIMED_OUT_TASKS.put(taskName, worker);
            worker.interrupt();
            if (!worker.isAlive()) {
                TIMED_OUT_TASKS.remove(taskName, worker);
            }
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

    private static Thread liveTimedOutWorker(String taskName) {
        Thread worker = TIMED_OUT_TASKS.get(taskName);
        if (worker == null) {
            return null;
        }
        if (!worker.isAlive()) {
            TIMED_OUT_TASKS.remove(taskName, worker);
            return null;
        }
        return worker;
    }

    private static void pruneCompletedTimedOutTasks() {
        TIMED_OUT_TASKS.forEach((taskName, worker) -> {
            if (!worker.isAlive()) {
                TIMED_OUT_TASKS.remove(taskName, worker);
            }
        });
    }

    private static void execute(String taskName, FutureTask<?> task) {
        try {
            task.run();
        } finally {
            TIMED_OUT_TASKS.remove(taskName, Thread.currentThread());
            CAPACITY.release();
        }
    }

    public record Status(int activeTasks, int timedOutTasks) {
        public Status {
            if (activeTasks < 0 || activeTasks > MAX_CONCURRENT_TASKS) {
                throw new IllegalArgumentException("activeTasks out of range: " + activeTasks);
            }
            if (timedOutTasks < 0 || timedOutTasks > activeTasks) {
                throw new IllegalArgumentException("timedOutTasks out of range: " + timedOutTasks);
            }
        }
    }
}
