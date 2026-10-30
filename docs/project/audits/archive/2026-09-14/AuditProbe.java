import com.nexus.security.SensitiveContentRedactor;
import com.nexus.config.NexusPaths;
import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

public class AuditProbe {
    public static void main(String[] args) throws Exception {
        String secret = "auditSyntheticSecret123!";
        String plain = "password = \"" + secret + "\"";
        String json = "{\"password\": \"" + secret + "\", \"api_key\": \"" + secret + "\"}";
        System.out.println("plain_assignment_secret_present=" + SensitiveContentRedactor.redact(plain).contains(secret));
        System.out.println("json_assignment_secret_present=" + SensitiveContentRedactor.redact(json).contains(secret));
        var check = NexusPaths.class.getDeclaredMethod("isTrustedStoragePrincipal", String.class, String.class);
        check.setAccessible(true);
        for (String name : new String[]{"NT AUTHORITY\\SYSTEM", "AUTORITE NT\\Système", "BUILTIN\\ADMINISTRATORS", "BUILTIN\\Administrateurs"}) {
            System.out.println("trusted[" + name + "]=" + check.invoke(null, name, "audit-user"));
        }
        var executor = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/embed", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                try { Thread.sleep(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                output.write("\"embeddings\":[[1.0]]}".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var provider = new OllamaEmbeddingProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "audit", 1, Duration.ofMillis(500));
            long start = System.nanoTime();
            try {
                float[] vector = provider.embed("audit synthetic text");
                System.out.println("ollama_success=true configured_timeout_ms=500 elapsed_ms=" + (System.nanoTime()-start)/1_000_000 + " dimensions=" + vector.length);
            } catch (Exception e) {
                System.out.println("ollama_success=false configured_timeout_ms=500 elapsed_ms=" + (System.nanoTime()-start)/1_000_000 + " exception=" + e.getClass().getSimpleName());
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
