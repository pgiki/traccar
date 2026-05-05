# External Token Introspection

Traccar supports authenticating API and WebSocket requests with external OAuth2 access tokens.

This is useful when Traccar runs as a resource server behind an external authorization server.

## Configuration

Use existing OpenID settings:

- `openid.issuerUrl`
- `openid.clientId` (optional)
- `openid.clientSecret` (optional)

No dedicated introspection URL is required.

## Introspection Endpoint Resolution

When validating an external bearer token, Traccar resolves introspection endpoint in this order:

1. `{openid.issuerUrl}/.well-known/openid-configuration` -> `introspection_endpoint`
2. `{openid.issuerUrl}/.well-known/oauth-authorization-server` -> `introspection_endpoint`
3. Fallback: `{openid.issuerUrl}/introspect`

If `openid.clientId` and `openid.clientSecret` are configured, Traccar uses HTTP Basic auth for introspection requests.

## Authentication Flow

For bearer token authentication:

1. Traccar first attempts local token verification.
2. If local verification fails, Traccar performs external token introspection.
3. If token is active, Traccar maps identity in order:
  - `username`
  - `email`
  - `sub`

If no principal can be resolved or token is inactive, authentication is rejected.

## User Mapping and Auto-Provisioning

For active external tokens:

1. Traccar tries to find an existing user by `login` or `email`.
2. If no user is found, Traccar auto-provisions a user on first login.

Auto-provisioned user fields:

- `login`: resolved principal (`username`, then `email`, then `sub`)
- `name`: `username` if available, otherwise resolved principal
- `email`:
  - introspection `email` claim, if available
  - otherwise synthetic email fallback: `<username-or-sub>@localhost`
- `fixedEmail`: `true`

Example synthetic email:

- `newuser@localhost`

## WebSocket Support

The same logic applies to WebSocket connections using token query parameter:

- `ws://<host>/api/socket?token=<access_token>`

WebSocket and HTTP bearer authentication both use the same token login service path.