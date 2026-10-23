package dev.kawa.client;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A scripted stand-in for Kawa: answers every request with the next queued response and records
 * what the client sent, so tests can check both sides of the conversation.
 */
final class FakeKawaServer implements AutoCloseable {

    record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }

    private record Scripted(int status, String contentType, String body) {
    }

    private final HttpServer server;
    private final List<Scripted> responses = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();

    FakeKawaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Scripted reply;
            synchronized (this) {
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body));
                reply = responses.isEmpty() ? new Scripted(500, "text/plain", "no response scripted") : responses.remove(0);
            }
            if (reply.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    synchronized FakeKawaServer reply(int status, String json) {
        responses.add(new Scripted(status, "application/json", json));
        return this;
    }

    synchronized FakeKawaServer replyProblem(int status, String title, String detail) {
        responses.add(new Scripted(status, "application/problem+json",
                "{\"status\":" + status + ",\"title\":\"" + title + "\",\"detail\":\"" + detail + "\"}"));
        return this;
    }

    synchronized FakeKawaServer replyEmpty(int status) {
        responses.add(new Scripted(status, null, ""));
        return this;
    }

    synchronized FakeKawaServer replyRaw(int status, String contentType, String body) {
        responses.add(new Scripted(status, contentType, body));
        return this;
    }

    synchronized Recorded lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
