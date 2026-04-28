package org.traccar.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExternalTokenAuthenticatorTest {

    private HttpServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void testDiscoveryIntrospectionEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("client:secret".getBytes(StandardCharsets.UTF_8));

        server.createContext("/issuer/.well-known/openid-configuration", exchange ->
                writeJson(exchange, 200, "{\"introspection_endpoint\":\"http://localhost:" + port + "/introspect-real\"}"));
        server.createContext("/introspect-real", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(expectedAuth, exchange.getRequestHeaders().getFirst("Authorization"));
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(form.contains("token=abc123"));
            writeJson(exchange, 200, "{\"active\":true,\"username\":\"alice\",\"exp\":1893456000}");
        });
        server.start();

        Config config = new Config();
        config.setString(Keys.OPENID_ISSUER_URL, "http://localhost:" + port + "/issuer");
        config.setString(Keys.OPENID_CLIENT_ID, "client");
        config.setString(Keys.OPENID_CLIENT_SECRET, "secret");
        Client client = ClientBuilder.newClient();

        ExternalTokenAuthenticator authenticator =
                new ExternalTokenAuthenticator(config, client, new ObjectMapper());

        ExternalTokenAuthenticator.IntrospectionResult result = authenticator.introspect("abc123");
        assertNotNull(result);
        assertTrue(result.active());
        assertEquals("alice", result.username());
        assertNotNull(result.expiration());
    }

    @Test
    public void testFallbackToIssuerIntrospect() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/issuer/.well-known/openid-configuration", exchange ->
                writeJson(exchange, 200, "{\"issuer\":\"http://localhost:" + port + "/issuer\"}"));
        server.createContext("/issuer/.well-known/oauth-authorization-server", exchange ->
                writeJson(exchange, 404, "{\"error\":\"not_found\"}"));
        server.createContext("/issuer/introspect", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(form.contains("token=fallbackToken"));
            writeJson(exchange, 200, "{\"active\":true,\"email\":\"user@example.com\"}");
        });
        server.start();

        Config config = new Config();
        config.setString(Keys.OPENID_ISSUER_URL, "http://localhost:" + port + "/issuer/");
        Client client = ClientBuilder.newClient();

        ExternalTokenAuthenticator authenticator =
                new ExternalTokenAuthenticator(config, client, new ObjectMapper());

        ExternalTokenAuthenticator.IntrospectionResult result = authenticator.introspect("fallbackToken");
        assertNotNull(result);
        assertTrue(result.active());
        assertEquals("user@example.com", result.email());
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
