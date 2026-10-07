# PairDesk protocol

This document describes the wire protocol so that other clients (the Android
app in `android/`) interoperate with the desktop application. The reference
implementation is the desktop code: `src/main/signaling/`, `src/main/crypto/`,
`src/renderer/common/rtc.js`, `src/renderer/viewer/viewer.js` and
`src/renderer/host/host.js`. When this document and the code disagree, the
code wins; please fix the document.

Conventions: `lv(a, b, …)` is the concatenation, for each argument, of its
length as a 4-byte big-endian integer followed by its bytes (strings are UTF-8).
`b64u` is base64url without padding. All JSON numbers are plain numbers.

## 1. Identities

* **ID**: 9 decimal digits, first digit 1-9 (`/^[1-9]\d{8}$/`), chosen at random
  on first start and kept. Displayed as `123 456 789`; users may type spaces or
  dashes, keep only digits.
* **Temporary password**: 6 characters from `abcdefghjkmnpqrstuvwxyz23456789`,
  regenerated at will. An optional **permanent password** (8+ chars) may exist.
* **PRS** (password-related string), 32 bytes:
  `PBKDF2-HMAC-SHA256(password = NFC(trim(password)), salt = "PairDesk-PRS-v1|" + hostId, iterations = 200000, length = 32)`.
  The host stores only PRS values. The controller derives the PRS from the
  password typed by the user and the host ID.
* **Device key** (server transport only): 32 random bytes, b64u, kept; proves
  ownership of the ID to a private PairDesk server.

`PROTOCOL_VERSION = 1`.

## 2. Transports (signaling)

A transport delivers JSON objects `data` between IDs: `send(to, data)` and
`on message {from, data}`. Messages are at most 64 KiB once serialized.

### 2.1 Public MQTT relays (default)

* Brokers (all used at once, see `pairdesk.config.json`):
  `wss://broker.emqx.io:8084/mqtt`, `wss://broker.hivemq.com:8884/mqtt`,
  `wss://test.mosquitto.org:8081/mqtt`, `wss://mqtt.eclipseprojects.io:443/mqtt`.
  MQTT 3.1.1 over secure WebSocket, clean session, keepalive 30 s, random
  client id `pd_<16 hex>`.
* Each device subscribes (QoS 1) to its own topic
  `"pairdesk/v1/" + hex(SHA-256("pairdesk:" + id))[0:32]`.
* To send, publish (QoS 1) to the recipient's topic on every connected broker
  the UTF-8 JSON envelope `{"mid": <random b64u, 12 bytes>, "from": <my id>, "data": <data>}`.
  Sending succeeds when at least one broker acknowledges.
* On reception, drop envelopes larger than 64 KiB, invalid `from`, or an
  already seen `mid` (the same message arrives once per broker).

### 2.2 Private PairDesk server (optional)

WebSocket to the configured URL (e.g. `wss://pairdesk.example.com/ws`):

* client → server `{"t":"register","id":<id>,"key":<device key>,"v":1}`;
  server → client `{"t":"registered","iceServers":[…]}` or
  `{"t":"error","code":"id-taken"|"bad-register"}`.
* client → server `{"t":"send","to":<id>,"mid":<b64u 9 bytes>,"data":<data>}`;
  server → client `{"t":"ack","mid":…}` or `{"t":"error","mid":…,"code":"offline"|…}`.
* server → client `{"t":"msg","from":<id>,"data":<data>}` and
  `{"t":"ice-servers","iceServers":[…]}` (TURN credentials refresh).
* WebSocket ping every 25 s.

## 3. Session establishment (CPace PAKE)

Notation: *ctrl* is the device that connects (types the ID and password),
*host* is the device being connected to. Every message has `t` and `sid`
(8-64 chars; the controller picks 18 random bytes, b64u).

```
ctrl                                                  host
{t:'probe', sid, v:1} ───────────────────────────────▶
     ◀────────────────────────────── {t:'probe-ack', sid, v:1}
{t:'hello', sid, v:1, ya} ───────────────────────────▶
     ◀──────────────── {t:'challenge', sid, ybs:[…], tags:[…]}   (one per host password, ≤ 4)
{t:'confirm', sid, idx, tag, intro} ─────────────────▶            (intro is sealed, see 3.3)
     ◀────────── {t:'sec', …} = sealed {type:'waiting'} (optional, local user must accept)
     ◀────────── {t:'sec', …} = sealed {type:'accepted', …} | {type:'rejected', reason}
{t:'sec', …} ◀────────────────────────────────────────▶ {t:'sec', …}   (session messages)
```

Errors from the host: `{t:'denied', sid, reason, v:1, retryIn?}` with reason
`version | locked | busy | disabled | protocol | auth`. A controller whose
password does not match any `tags[i]` waits 2.5 s (a forged answer may come
from a public relay) then sends `{t:'abort', sid}` and reports `auth`.
Timeouts: hello → challenge 20 s, confirm → accepted 120 s (user approval).

### 3.1 CPace

* `CI = lv("ctrl", ctrlId, "host", hostId)`.
* Generator `G = hash_to_ristretto255(lv(PRS, CI, sid))` as in RFC 9380
  (`expand_message_xmd` with SHA-512, 64 uniform bytes, ristretto255 one-way
  map of RFC 9496) with `DST = "PairDesk-v1-CPace-ristretto255"`.
* Each side draws a scalar `y` = 64 random bytes read as a **little-endian**
  integer, reduced modulo the group order L (retry if 0); share `Y = y·G`,
  32-byte ristretto255 encoding, sent as b64u (`ya` from ctrl, `yb` from host).
* `K = y·Y_peer`; reject non-canonical encodings, the identity share and an
  identity result.
* `ISK = SHA-512(lv("PairDesk-v1-CPace-ISK", sid, K, ya, yb))`.
* `OKM = HKDF-SHA256(ikm = ISK, salt = UTF-8(sid), info = "PairDesk v1 key schedule", length = 128)`:
  `tagKeyHost = OKM[0:32]`, `tagKeyCtrl = OKM[32:64]`,
  `encHostToCtrl = OKM[64:96]`, `encCtrlToHost = OKM[96:128]`.
* `transcript = lv("PairDesk-v1-transcript", sid, ctrlId, hostId, ya, yb)`.
* Host tag (in `challenge.tags`): `HMAC-SHA256(tagKeyHost, lv("host-confirm", transcript, ""))`.
* Controller tag (in `confirm.tag`): `HMAC-SHA256(tagKeyCtrl, lv("ctrl-confirm", transcript, ""))`.
  `confirm.idx` is the index of the matching host share.

The host computes one candidate per stored PRS (temporary and permanent) and
counts every hello as a failed attempt until a valid confirm arrives
(5 failures in 30 min lock it for 30 s, doubling up to 30 min).

### 3.2 Sealed messages

AES-256-GCM, key `encCtrlToHost` (direction 2) or `encHostToCtrl` (direction 1),
nonce = `uint32_be(direction) ‖ 0x0000 ‖ uint48_be(counter)`, counter starts at 1
and increases per message, AAD = UTF-8 `"PairDesk-v1|" + sid`, plaintext = UTF-8
JSON. Wire form: `{t:'sec', sid, n: counter, ct: b64u(ciphertext ‖ 16-byte tag)}`.
Receivers reject a counter already seen. `confirm.intro` is the same
`{n, ct}` object without `t`/`sid`.

### 3.3 Session messages (sealed)

* `intro` (in confirm, ctrl → host):
  `{type:'intro', name, platform, appVersion, kind?}`; `platform` is
  `win32 | linux | darwin | android`; `kind` is `'control'` (default) or
  `'camera'` (1.2+, see 6).
* `{type:'waiting'}`, `{type:'rejected', reason}`.
* `{type:'accepted', name, caps, platform, appVersion, kind?}` (host → ctrl);
  `caps = {control, files, clipboard, audio}` (booleans), 1.2+ hosts echo
  `kind`.
* `{type:'signal', data}` where `data` is `{description: RTCSessionDescriptionInit}`
  or `{candidate: RTCIceCandidateInit}` (WebRTC, both ways).
* `{type:'bye', reason}` ends the session.

## 4. WebRTC session

ICE servers: STUN `stun:stun.l.google.com:19302`, `stun:stun1.l.google.com:19302`,
`stun:stun.cloudflare.com:3478`, plus TURN servers handed out by a private
server. `bundlePolicy: max-bundle`. Trickle ICE through `signal` messages.

The **host is always the offerer** (also when renegotiating). It creates three
data channels before the first offer:

| label     | options                                   | content |
|-----------|-------------------------------------------|---------|
| `control` | ordered, reliable                         | JSON control messages (section 5) |
| `input`   | ordered, reliable                         | JSON arrays of input events (clicks, keys, wheel, resting position) |
| `pointer` | `ordered:false, maxRetransmits:0` (1.1+)  | JSON arrays of mouse moves (latest wins) |
| `file:<fid>` | ordered, reliable, created by the sender | raw file chunks (16 KiB) |

Media (control sessions): the host sends its screen as a video track (VP9
preferred for screen content, VP8 fallback); audio, when shared, goes in its
own MediaStream for 1.1+ viewers. The controller only receives.

### 4.1 Input events (controller → host)

Each message is a JSON array of events; coordinates are normalized to the
current display, `0 ≤ x, y ≤ 1` (rounded to 4 decimals).

| event | meaning |
|-------|---------|
| `['m', x, y, seq?, btnSeq?]` | move (pointer channel; reliable copy after 120 ms of rest) |
| `['d', button, x, y, seq?]` / `['u', button, x?, y?, seq?]` | button down / up (0 left, 1 middle, 2 right, 3 back, 4 forward) |
| `['w', dx, dy, seq?]` | wheel, pixels (positive dy = down), preceded by `['m', x, y, seq]` |
| `['k', code, down]` | key, `code` = `KeyboardEvent.code` (`KeyA`, `Enter`, `ShiftLeft`, `MetaLeft`…), `down` 1/0 |
| `['r']` | release every pressed key and button |
| `['t', text]` (1.2+) | type Unicode text (≤ 256 chars), for mobile keyboards |
| `['a', action]` (1.2+, Android hosts) | `back`, `home`, `recents`, `notifications` |

Sequence numbers (1.1+): `seq` increases for every position-bearing event of a
viewer window; `btnSeq` is the `seq` of the last `d`/`u`/`w` sent before the
move. The host drops a move whose `seq` is not above the last applied one, or
whose `btnSeq` is above the last applied button/wheel event (it overtook a
click still being retransmitted). Events without numbers are applied as they
come.

### 4.2 Control messages

Host → controller:

* `{type:'info', displays:[{id, width, height, primary}], current, perms}` on
  channel open and when something changes (`width`/`height` in physical pixels).
* `{type:'display-changed', current}`.
* `{type:'cursor', shape}` (1.1+): CSS cursor keyword (`default`, `text`,
  `pointer`, `none`…) or `{shape:'custom', id, hot:[x,y], scale, png?}`.
* `{type:'host-stats', encodeMs, pacerMs, fps, codec, encoder, limitation, bandwidth, scale}` (1.1+, every second).
* `{type:'input-blocked', reason}` (1.2+): `reason` is `elevated` (an
  administrator window is in the foreground: Windows drops injected input),
  `secure-desktop` (UAC prompt, lock screen) or `null` when input works again.

Controller → host:

* `{type:'hello', quality}`, `{type:'quality', mode}` with mode
  `speed | balanced | quality`.
* `{type:'select-display', displayId}`.
* `{type:'wake'}` (1.2+): the picture stopped changing; the host turns its
  screen back on (display power saving).

Both ways:

* `{type:'chat', text}` (≤ 2000 chars), `{type:'clipboard', text}`.
* `{type:'ping', t}` → `{type:'pong', t}` (round trip for file pacing, 1.1+).
* `{type:'bye'}`.
* Files: `{type:'file-offer', fid, name, size}` → `{type:'file-accept'|'file-reject', fid}`;
  the sender then opens data channel `file:<fid>` and sends the content in
  chunks (≤ 16 KiB), the receiver answers `{type:'file-done', fid, ok}` once
  `size` bytes arrived; either side may send `{type:'file-cancel', fid}`.

Unknown messages and fields must be ignored.

## 5. Android peers (1.2+)

### 5.1 Phone controlling a computer

The phone is the controller of a regular control session
(`intro.platform = 'android'`). It renders the video, sends moves on the
`pointer` channel and everything else on `input`, and uses `['t', text]` for
text typed with the soft keyboard (`['k', …]` for Enter, Backspace, arrows,
Escape, Tab and shortcuts).

### 5.2 Computer mirroring a phone

The phone is the host (`accepted.platform = 'android'`): it offers its screen
(MediaProjection), creates the usual data channels, sends
`{type:'info', displays:[{id:'android', width, height, primary:true}], current:'android', perms}`
and applies input with an accessibility service when `perms.control` is true
(taps and drags from `d`/`m`/`u`, scrolling from `w`, text from `t`, Enter /
Backspace from `k`, navigation from `a`). Desktop viewers send printable
characters as `['t', char]` and offer Back / Home / Recent apps buttons when the
host platform is `android`.

## 6. Camera sessions (1.2+)

A phone streams its camera to a computer, which exposes it as a webcam.

* The phone connects like a controller with `intro.kind = 'camera'`.
* A 1.2+ host answers `accepted` with `kind:'camera'`; anything else (older
  host) must be treated as "camera not supported, update PairDesk".
* The host (computer) is still the offerer: its offer contains a `recvonly`
  video m-line (no screen capture) and the `control` channel. The phone answers
  with its camera track (`sendonly`), 1280×720 at 30 fps when possible.
* Control messages: phone → computer `{type:'camera-info', width, height, facing}`
  (`facing` = `front | back`); computer → phone `{type:'camera-switch'}` asks
  for the other camera. Either side ends with `bye`.
