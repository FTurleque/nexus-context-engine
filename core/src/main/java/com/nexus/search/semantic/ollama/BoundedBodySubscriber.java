package com.nexus.search.semantic.ollama;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Corps HTTP borné avant accumulation ; annulation sans thread de lecture bloqué. */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() { return body; }

    @Override
    public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null || body.isDone()) {
            value.cancel();
            return;
        }
        subscription = value;
        value.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        long bytes = output.size();
        for (ByteBuffer buffer : buffers) bytes += buffer.remaining();
        if (bytes > limit) {
            fail(new ResponseLimitException(limit));
            return;
        }
        byte[] chunk = new byte[Math.min(16 * 1024, (int) bytes - output.size())];
        for (ByteBuffer buffer : buffers) {
            while (buffer.hasRemaining()) {
                int length = Math.min(chunk.length, buffer.remaining());
                buffer.get(chunk, 0, length);
                output.write(chunk, 0, length);
            }
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable failure) { fail(failure); }

    @Override
    public synchronized void onComplete() { body.complete(output.toByteArray()); }

    synchronized void cancel() { fail(new IOException("Appel Ollama annulé")); }

    private void fail(Throwable failure) {
        body.completeExceptionally(failure);
        if (subscription != null) subscription.cancel();
    }

    static final class ResponseLimitException extends IOException {
        ResponseLimitException(int limit) {
            super("Ollama /api/embed exceeded the " + limit + " byte limit");
        }
    }
}
