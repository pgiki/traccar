# Traccar WebSocket API (`/api/socket`)

Live device, position, event, and optional log updates use the same JSON shape as the web app: each message is an object with any of `devices`, `positions`, `events`, and optionally `logs`.

## Authorization

- **Session cookie** — same browser session as the REST API (typically `JSESSIONID`).
- **Or query parameter** `token` — a JWT from `POST /api/session/token` (useful for native clients and scripts). Example:  
  `wss://host/api/socket?token=YOUR_JWT`

## Optional filters (query string on connect)

All parameters are optional. Omit them to receive the full stream (default behavior, unchanged from older Traccar).

| Parameter | Description |
|-----------|-------------|
| `minLat`, `maxLat`, `minLon`, `maxLon` | Bounding box in WGS84 degrees. All four are required to enable the bbox. Invalid GPS fixes are **dropped** while the bbox is active. Aliases: `minLatitude`, `maxLatitude`, `minLongitude`, `maxLongitude`. |
| `deviceId` | Repeat to restrict to one or more device IDs. Only IDs you are allowed to see are applied. |
| `groupId` | Single group ID; devices include that group and its child groups (same as elsewhere in Traccar). If `deviceId` and `groupId` are both set, the result is the **intersection** (devices that appear in both). |
| `protocol` | Only positions whose `protocol` field matches (e.g. `omniebike`). |
| `eventType` or `event` | Only events whose `type` matches (e.g. `alarm` / `deviceOnline`). |

Example:

```text
wss://example.com/api/socket?minLat=50&maxLat=51&minLon=3&maxLon=5&protocol=omniebike&eventType=alarm&token=...
```

## Updating filters over the socket (JSON text messages)

After connect, the client can send text frames with the same structure as before for logs, plus optional `positions` and `events` objects to **update** filters without reconnecting (merge semantics: bbox fields you omit keep their previous values if you are only changing the nested `bbox` or individual corners).

```json
{
  "logs": false,
  "positions": {
    "minLat": 50.0,
    "maxLat": 51.0,
    "minLon": 3.0,
    "maxLon": 5.0,
    "deviceId": [1, 2],
    "groupId": 3,
    "protocol": "omniebike"
  },
  "events": {
    "type": "alarm"
  }
```

Alternatively, nest bounds under `positions.bbox`:

```json
{
  "positions": {
    "bbox": {
      "minLat": 50,
      "maxLat": 51,
      "minLon": 3,
      "maxLon": 5
    }
  }
}
```

To clear a protocol filter, send a JSON field with an empty string or `null` for that field in a follow-up `positions` update (e.g. `"protocol": null` where supported by your client’s serializer).

## Message shape (unchanged)

```json
{
  "devices": [ ... ],
  "positions": [ ... ],
  "events": [ ... ],
  "logs": [ ... ]
}
```

Keepalive and empty objects `{}` are still used as before. Filtered clients simply receive **fewer** `positions` / `devices` / `events` update messages; security is still enforced (you only get devices you have access to; filters only narrow the stream further).
