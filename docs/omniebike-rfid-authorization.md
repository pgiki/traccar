# OmniEbike RFID authorization

This document describes how OmniEbike RFID must be operated with Traccar so that **every card tap is authorized by the events server**, not by the bike locally.

## Security finding

If a bank card or arbitrary NFC/RFID card can lock/unlock the bike **without** a preceding `C0` message in Traccar logs, the IoT is using **local/offline card authorization**. That mode is unsafe for shared fleets:

- The protocol exposes only a UID (no EMV / cryptographic card auth).
- Bank-card UIDs are not secrets and can collide or be cloned.
- Traccar and the events server never learn which card was used.

Local/offline RFID acceptance **must be disabled** on the Omni reader/ECU before relying on server-side control.

## Required hardware configuration

1. Confirm with Omni that the unit supports RFID and the TCP `C0` command.
2. Disable local/offline card unlock/lock in the reader or ECU configuration (vendor tooling / support).
3. Use **purpose-issued** Type A RFID/NFC cards only. Do **not** enroll bank cards.
4. Verify with a tap:
   - Traccar receives `*SCOR,OM,<IMEI>,C0,<request>,<cardType>,<cardId>,0#`
   - Lock state does **not** change until the events server authorizes and Traccar sends `R0` → `L0`/`L1`
5. Rejection check: an unknown or bank card must produce either no unlock, or a `C0` that the events server denies (bike stays locked).

## Traccar behavior

When `C0` arrives, Traccar:

1. Stores standard attributes `card` and `driverUniqueId` (same UID), plus Omni extras `rfidRequest` and `rfidCardType`.
2. Emits a **`cardRead`** event for every tap (no deduplication). `driverChanged` is not used because it only fires when the id changes.
3. Forwards the event to `event.forward.url` when configured.
4. Does **not** unlock automatically.

Vehicle lock state on heartbeats (`H0`), vehicle data (`S6`), and lock results (`L0`/`L1`) uses the standard attribute **`lock`** (boolean). `L0`/`L1` also include `operationUserId` / `operationSequence` for correlation and emit `commandResult`.

Controller faults (`E0`) emit a standard **`alarm`** event with alarm type `fault` and `status` set to the controller error code.

## Events-server workflow

```text
C0 tap → cardRead event → authorize → custom R0 → (decoder relays L0/L1) → commandResult
```

### 1. Receive `cardRead`

Event payload includes:

| Field | Meaning |
|-------|---------|
| `type` | `cardRead` |
| `deviceId` | Traccar device id |
| attributes.`card` | Hex card UID (standard Traccar attribute) |
| attributes.`driverUniqueId` | Same UID (standard RFID/driver attribute) |
| attributes.`rfidRequest` | `0` unlock, `1` lock |
| attributes.`rfidCardType` | `0` Type A (default), `1` Type B |

### 2. Authorize

Deny by default when any of these fail:

- Card unknown / revoked / expired
- Card not allowed for this tenant/fleet/device
- Active ride already exists (for unlock)
- Stale tap (authorization timeout exceeded)
- Rate limit exceeded
- Events server unhealthy

Store card identifiers as **keyed HMAC tokens**, compare in constant time, transmit only over TLS, and never store PAN/payment data.

### 3. Send documented server unlock/lock

Do **not** send `L0`/`L1` first. Send a Traccar **custom** command using the Omni `R0` challenge:

- RFID unlock: `R0,2,20,<userId>,<operationSequence>`
- RFID lock: `R0,3,20,<userId>,<operationSequence>`

Where:

- `2` / `3` are Omni RFID unlock / lock ops
- `20` is KEY validity seconds (adjust as needed)
- `userId` is your internal rider/user id (0–4294967295)
- `operationSequence` is a unique unix-second (or monotonic) value for replay protection

Traccar’s Omni decoder receives the IoT `R0` reply (with generated KEY) and automatically sends authenticated `L0` or `L1`.

Example Traccar API body:

```json
{
  "deviceId": 2,
  "type": "custom",
  "attributes": {
    "data": "R0,2,20,1001,1710000000"
  }
}
```

### 4. Close the transaction on `commandResult`

Match `operationUserId` / `operationSequence` (and device) to the pending authorization.

- Success (`result` = `"0"`): mark ride unlocked/locked and write audit log
- Failure / timeout / mismatch: leave or restore locked state; audit the denial

## Acceptance checklist

- [ ] Unknown card tap → `cardRead` (if hardware reports `C0`) and **no** unlock
- [ ] Authorized card → one `cardRead`, one `R0`/`L0` or `R0`/`L1` exchange, one successful `commandResult`
- [ ] Same card tapped twice → two `cardRead` events
- [ ] Events server down → bike stays locked
- [ ] No card can unlock without a preceding forwarded `C0` and an events-server decision
