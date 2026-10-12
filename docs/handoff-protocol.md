# LibrePods My Bluetooth handoff v1

Goal: the most recently observed **new media playback start** owns AirPods audio.
Stopping playback never restores the previous owner. Monitoring starts from a
baseline, so startup, pairing, reconnect and route changes must not claim ownership.
No media samples, track titles or application names are transmitted.

## Transport and pairing

Mac is a CoreBluetooth peripheral; Android is the central. No IP, Internet server,
camera, notification-listener, accessibility permission or third-party dependency.
Service UUID `739d1a20-6c32-4b18-a8e5-bdc4010a2000`;
RX (phone -> Mac) ends `2001` (write with response);
TX (Mac -> phone) ends `2002` (notify).
Frames: unsigned 16-bit big-endian byte length + UTF-8 JSON; maximum 8192 bytes.
GATT fragments are queued serially; reject oversized/malformed frames. One phone
session at a time, finite handshake timeout. Never log secrets or decrypted traffic.

Each install has a P-256 ECDH private identity: macOS Keychain and Android Keystore.
Public identities use standard SPKI DER (91 bytes, prefix hex
`3059301306072a8648ce3d020106082a8648ce3d030107034200` + 65-byte X9.63 key).
Phone first writes plaintext `{type:"hello",v:1,publicKey:<base64 DER>,nonce:<base64 32 random bytes>,name:<display name>}`.
Mac responds with the same fields for its own identity and fresh random nonce.
Transcript = mac DER || phone DER || mac nonce || phone nonce.
Salt = SHA256(transcript). HKDF-SHA256(32-byte ECDH shared secret, salt, info) yields
32-byte keys. Info strings are exactly `LPH1 mac-to-phone`, `LPH1 phone-to-mac`,
and `LPH1 pairing`. SAS: first ten bytes of the pairing key encoded as 16 uppercase Crockford Base32
characters using alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, grouped four with
hyphens (`XXXX-XXXX-XXXX-XXXX`). This is an 80-bit visual confirmation, not a code
typed or transmitted. The longer comparison prevents feasible adaptive nonce
grinding in this protocol without an additional commitment exchange.
The user compares that code on BOTH screens and explicitly approves on both.
After both approve authenticated `ready` messages, pin the peer public identity.
Pinned peers auto-authenticate on reconnection, never silently replace pins.
Known pins plus valid authenticated messages prove possession of the private key.
First pairing requires both humans' code check; reject unexpected peers until an
explicit Forget action. Forget removes pairing, not the install's private identity.

Secure envelope: `{type:"secure",seq:<positive integer>,data:<base64>}`.
Data = 12-byte random AES-GCM nonce || ciphertext || 16-byte tag.
AAD = UTF-8 `LPH1:<seq>`; direction-specific key as above. Strict per-session
sequence starts at 1, increments by one; replay/gaps/auth failures close the logical
session. Reject plaintext messages other than the initial hello/rejection while
negotiating. Authenticated ready messages are the first secure message each way.
Phone sends `{type:"ready"}` on local approval; Mac sends
`{type:"ready",owner:"none|mac|phone",revision:<counter>}` only after both approve.
Session keys are fresh for every BLE connection and never persisted.

## Ownership and acknowledgements

Mac serializes events; its revision counter persists across app restarts. Events
arriving during a transition replace/cancel the previous transition. Near-simultaneous
starts use Mac observation/receipt order, not unsynchronized device wall clocks.
Phone -> Mac `{type:"playback_started"}` for new local media start.
Mac -> Phone `{type:"claim",owner:"mac|phone",revision:<counter>}`.
Phone -> Mac `{type:"yielded",revision:<same>,success:<bool>,message:<short UI reason>}`
when asked to yield to Mac; pause old phone media without marking it auto-resumable.
Phone -> Mac `{type:"result",revision:<same>,success:<bool>,message:<short UI reason>}`
when its A2DP claim finishes. Reject stale/duplicate revision messages.
Mac -> Phone optional `{type:"status",owner:<owner>,revision:<counter>,success:<bool>,message:<short UI reason>}`.
No raw exception, filesystem path, Bluetooth address, track title or crypto data in UI reasons.

For a phone start, Mac first closes its selected AirPods connection, then sends
claim(phone). For a Mac start, send claim(mac), wait for yielded (bounded), then
request Mac Bluetooth connection and explicitly select that AirPods CoreAudio output.
Success means actual selected audio route/A2DP state, not socket/baseband success.
No automatic play/replay after connection. Playback pauses do not send claims.
If peer transport is unavailable, a local new start may request local audio once;
reconnection itself never replays an old claim. Show disconnected/error state.
Avoid routing feedback with player-specific automatic-resume filtering/baseline refresh and bounded
retry/backoff; never retry indefinitely against a newer claim.

Mac: poll CoreAudio process-output state (macOS 14.2+) with startup baseline;
track newly active output processes, debounce transient changes, exclude system
alert sounds/helpers where identifiable. No audio capture/tap.
Android: public media playback callback, filter USAGE_MEDIA, compare active
AudioPlaybackConfiguration sets and aggregate inactive->active, ignoring app-issued
media keys and route-change callbacks. Anonymous player equality is an AOSP
implementation detail, not a guaranteed public player identity; disclose limitations.
No notification listener is added. Enabling handoff suspends legacy wear/music/call automatic takeover and takeover
replay; disabling it restores original behavior. Explicit manual play/reconnect
request PHONE ownership. Original local ear/conversation resume remains available
only while the service holds the PHONE audio lease; resumed player identities are
excluded from new-start detection so local features do not seize a different owner.
No blanket suppression window hides genuine new-player starts. Observation activates
only after an atomic seed->observe flag, and actual A2DP route completion refreshes
the player baseline. Some rapid or identity-changing player transitions cannot be
perfectly classified through public Android callbacks; disclose this UI limitation. Preserve all original feature permissions.

## Android integration API (owned by handoff implementation)

Package `me.kavishdevar.librepods.data.handoff`:
- enum `HandoffOwner { NONE, MAC, PHONE }`
- singleton `MacHandoffClient`, observable `state: StateFlow<MacHandoffState>`
- volatile `enabled`, `ready`, `owner`, `currentRevision` (read-only externally)
- `initialize(context)`, `attach(context, listener)`, `detach(listener)`
- `setEnabled(context, enabled)`, `scan(context)`, `connect(context, deviceId)`
- `confirmPairing()`, `rejectPairing()`, `forget(context)`
- `notifyPlaybackStarted()` sends authenticated start when ready; if enabled and
  already paired but offline, invokes listener.onClaim(PHONE, unique negative revision) for local-only start.
  Negative revisions decrease per event, remain local and never appear on wire.
- `reportResult(revision: Long, success: Boolean, message: String)` returns yielded
  for current MAC claim, result for PHONE claim, and ignores stale/offline results.
- top-level `HandoffListener` with `fun onClaim(owner: HandoffOwner, revision: Long)` invoked on main thread.
- `MacHandoffCard()` Compose settings entry owns initialization/UI only; starts the
  existing AirPodsService when enabling/scanning, which owns the live listener.
Parent owns service/media hooks, AppSettingsScreen insertion, version and privacy.
The handoff client owns its GATT, scanners, reconnect, crypto and pairing preferences.
Detach shuts down GATT/scanning and listener without forgetting stored pairing.

## Delivery boundaries

Android display version 0.2.0, internal70 (current release 0.1.0/internal69). Keep `my-v70`
release tag and fixed `LibrePods-My.apk` asset for existing updater compatibility.
Mac app first version 0.1.0, ad-hoc local signing; no automatic enable or claims on
first launch. Build with installed Xcode/Swift and system frameworks only.
Build/lint/packaged permissions and signing are inspected; no tests added/run unless
user requests them. Actual bidirectional audio behavior requires the paired physical
phone and AirPods; do not claim this was verified from builds alone.

## Implementation clarifications (before integration)

- First pair: local human approval and peer authenticated ready are both required.
  Pinned reconnect: matching identity auto-counts as local approval; phone sends ready
  after matching hello, Mac sends ready after receiving phone ready. Mac always shows
  the session SAS while waiting/connected, so a missing pin on one side can be safely
  re-confirmed against the already-pinned side. Each private identity is retained by
  Forget. UI recommends Forget on both sides for a full reset. Never replace a
  different pinned public key without the explicit Forget action.
- Pinning is allowed only after local confirmation and valid peer ready. A lost final
  ready can leave a one-sided pin; the same identity path above must recover without
  changing either identity or silently approving an unpinned side.
- The `owner` field means last **requested winner**, not proof of the actual audio
  route. UI labels it '최근 재생 기기'. Revision and requested winner are persisted
  before side effects so a newer start cancels old work even if the new claim fails.
  Connection status/success separately reports observed route. Failure never reports
  successful audio ownership or restores/replays the old owner.
- After EVERY asynchronous wait/callback and before every route side effect/status
  update, verify current session and revision. Stale work may not release/connect
  audio, overwrite status or emit acknowledgement for a newer claim.
- MAC claim while connected to phone: wait for yielded=true (phone media pause and
  release request completed) before attempting Mac audio. ACK failure/3s timeout
  shows a failure, no forced connected-peer claim. Phone may lack permission to
  fully disconnect A2DP; acknowledged pause + bounded release request is not claimed
  as actual disconnection. Mac actual route still determines final success.
- No more than 16 outbound frames/64KiB total and no more than one 8192-byte frame
  plus 2-byte header buffered inbound. Queue overflow, callback/write timeout or
  malformed frames close the logical session. Mac stops notify on backpressure;
  Android serializes writes and closes on failed write-with-response.
- Initial ready(owner,revision) is informational ONLY; it never invokes onClaim,
  sends playback_started, pauses audio or changes audio route. Offline starts are
  not replayed upon reconnection. Last-requested owner can differ from actual route.
- Add volatile `paired: Boolean` to MacHandoffClient API. The parent's legacy guards
  and observer activate only for `enabled && paired`. Until a pairing is confirmed,
  the original phone controls keep working. On mode activation seed playback from
  current configurations before observing new callbacks, without emitting an event.

## Android 17 audio control authorization

Public BluetoothDevice.connect()/disconnect() requires BLUETOOTH_CONNECT plus
either a same-device CompanionDeviceManager association or privileged Bluetooth
permission. The phone settings provide a native OS association request filtered
to the selected bonded AirPods MAC; the user approves it once (case may need to
be open for discovery). No new Android permission is requested. A uses-feature
companion_device_setup required=false allows feature detection. Older/root setups
retain their existing fallbacks without promising non-root profile management.
Reference: https://developer.android.com/reference/android/bluetooth/BluetoothDevice#connect()

The control socket's blocking connect/read is closed on timeout or stale claim via
a service-owned watchdog. All callers, including ACL/startup handlers, capture
current lifecycle and handoff state; stale readers never overwrite newer sockets.
Audio success is reported from A2DP before guarded AACP/ATT control setup proceeds.

## Discovery and reconnect

Mac advertises `LPMy-` + uppercase hex of the first four SHA256 bytes of its SPKI
identity. Android matches this stable public hint or the saved BLE address across
address rotation, then requires the exact pinned key and authenticated handshake.
The name is never proof of identity. Initial retries are finite; enabled paired
services continue bounded low-power discovery rounds roughly one minute apart
after fast attempts, stopping on disable, detach, forget or missing permissions.

Active handoff exclusively controls automatic connection/disconnection policies.
Conflicting wear/idle/call takeover toggles are visibly unavailable while enabled
and paired, with explanation, without changing their stored preferences. Disabling
handoff restores their original behavior. Battery, ANC, local ear pause/resume,
conversation awareness and head gestures remain in place.
