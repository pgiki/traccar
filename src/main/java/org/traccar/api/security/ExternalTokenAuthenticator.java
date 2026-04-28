/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

@Singleton
public class ExternalTokenAuthenticator {

    public record IntrospectionResult(boolean active, String sub, String username, String email, Date expiration) {
    }

    private final Config config;
    private final Client client;
    private final ObjectMapper objectMapper;

    private volatile URI introspectionEndpoint;

    @Inject
    public ExternalTokenAuthenticator(Config config, Client client, ObjectMapper objectMapper) {
        this.config = config;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public IntrospectionResult introspect(String token) throws IOException {
        URI endpoint = resolveIntrospectionEndpoint();
        if (endpoint == null) {
            return null;
        }

        Form form = new Form();
        form.param("token", token);

        Invocation.Builder requestBuilder = client.target(endpoint).request(MediaType.APPLICATION_JSON_TYPE);
        String authorization = buildAuthorizationHeader();
        if (authorization != null) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        Response response = requestBuilder.post(Entity.entity(form, MediaType.APPLICATION_FORM_URLENCODED_TYPE));

        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            return null;
        }

        Map<?, ?> data = objectMapper.readValue(response.readEntity(String.class), Map.class);
        boolean active = toBoolean(data.get("active"));
        Date expiration = toDate(data.get("exp"));
        return new IntrospectionResult(
                active,
                toString(data.get("sub")),
                toString(data.get("username")),
                toString(data.get("email")),
                expiration);
    }

    private URI resolveIntrospectionEndpoint() throws IOException {
        URI endpoint = introspectionEndpoint;
        if (endpoint == null) {
            synchronized (this) {
                endpoint = introspectionEndpoint;
                if (endpoint == null) {
                    endpoint = discoverIntrospectionEndpoint();
                    introspectionEndpoint = endpoint;
                }
            }
        }
        return endpoint;
    }

    private URI discoverIntrospectionEndpoint() throws IOException {
        String issuer = config.getString(Keys.OPENID_ISSUER_URL);
        if (issuer == null || issuer.isBlank()) {
            return null;
        }

        String normalizedIssuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        URI endpoint = readIntrospectionEndpoint(normalizedIssuer + "/.well-known/openid-configuration");
        if (endpoint != null) {
            return endpoint;
        }
        endpoint = readIntrospectionEndpoint(normalizedIssuer + "/.well-known/oauth-authorization-server");
        if (endpoint != null) {
            return endpoint;
        }

        return URI.create(normalizedIssuer + "/introspect");
    }

    private URI readIntrospectionEndpoint(String metadataUrl) throws IOException {
        Response response = client.target(metadataUrl).request(MediaType.APPLICATION_JSON_TYPE).get();
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            return null;
        }
        Map<?, ?> metadata = objectMapper.readValue(response.readEntity(String.class), Map.class);
        String endpoint = toString(metadata.get("introspection_endpoint"));
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        return URI.create(endpoint);
    }

    private String buildAuthorizationHeader() {
        String clientId = config.getString(Keys.OPENID_CLIENT_ID);
        String clientSecret = config.getString(Keys.OPENID_CLIENT_SECRET);
        if (clientId == null || clientSecret == null) {
            return null;
        }
        String authValue = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(authValue.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private static Date toDate(Object value) {
        if (value instanceof Number numberValue) {
            return new Date(numberValue.longValue() * 1000);
        }
        if (value instanceof String stringValue) {
            try {
                return new Date(Long.parseLong(stringValue) * 1000);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String toString(Object value) {
        return value != null ? value.toString() : null;
    }
}
