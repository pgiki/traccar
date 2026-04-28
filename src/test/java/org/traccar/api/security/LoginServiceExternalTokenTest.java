package org.traccar.api.security;

import org.junit.jupiter.api.Test;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.security.GeneralSecurityException;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LoginServiceExternalTokenTest {

    @Test
    public void testFallbackToExternalToken() throws Exception {
        Config config = new Config();
        Storage storage = mock(Storage.class);
        TokenManager tokenManager = mock(TokenManager.class);
        ExternalTokenAuthenticator externalTokenAuthenticator = mock(ExternalTokenAuthenticator.class);

        when(tokenManager.verifyToken("external-token")).thenThrow(new SecurityException("invalid local token"));

        Date expiration = new Date(System.currentTimeMillis() + 60000);
        when(externalTokenAuthenticator.introspect("external-token")).thenReturn(
                new ExternalTokenAuthenticator.IntrospectionResult(true, "sub-value", "alice", "alice@example.com", expiration));

        User user = new User();
        user.setId(10);
        user.setEmail("alice@example.com");
        user.setLogin("alice");
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user);

        LoginService loginService = new LoginService(config, storage, tokenManager, null, externalTokenAuthenticator);

        LoginResult result = loginService.login("external-token");
        assertNotNull(result);
        assertEquals(10, result.getUser().getId());
        assertEquals(expiration, result.getExpiration());
    }

    @Test
    public void testExternalInactiveTokenRejected() throws Exception {
        Config config = new Config();
        Storage storage = mock(Storage.class);
        TokenManager tokenManager = mock(TokenManager.class);
        ExternalTokenAuthenticator externalTokenAuthenticator = mock(ExternalTokenAuthenticator.class);

        when(tokenManager.verifyToken("inactive-token")).thenThrow(new GeneralSecurityException("bad"));
        when(externalTokenAuthenticator.introspect("inactive-token")).thenReturn(
                new ExternalTokenAuthenticator.IntrospectionResult(false, "sub", null, null, null));

        LoginService loginService = new LoginService(config, storage, tokenManager, null, externalTokenAuthenticator);
        assertNull(loginService.login("inactive-token"));
    }

    @Test
    public void testAutoProvisionExternalUserWithoutEmail() throws Exception {
        Config config = new Config();
        config.setString(Keys.OPENID_ISSUER_URL, "https://issuer.example.com");
        Storage storage = mock(Storage.class);
        TokenManager tokenManager = mock(TokenManager.class);
        ExternalTokenAuthenticator externalTokenAuthenticator = mock(ExternalTokenAuthenticator.class);

        when(tokenManager.verifyToken("new-user-token")).thenThrow(new SecurityException("invalid local token"));
        when(externalTokenAuthenticator.introspect("new-user-token")).thenReturn(
                new ExternalTokenAuthenticator.IntrospectionResult(true, "subject-001", "newuser", null, null));
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(null);
        when(storage.addObject(any(User.class), any(Request.class))).thenReturn(42L);

        LoginService loginService = new LoginService(config, storage, tokenManager, null, externalTokenAuthenticator);

        LoginResult result = loginService.login("new-user-token");
        assertNotNull(result);
        assertEquals(42L, result.getUser().getId());
        assertEquals("newuser", result.getUser().getLogin());
        assertEquals("newuser@issuer.example.com", result.getUser().getEmail());

        verify(storage).addObject(any(User.class), any(Request.class));
    }
}
