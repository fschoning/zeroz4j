# Detailed Protocol Specification

ZeroZ4j relies on a pure binary WebSocket protocol to enable high-performance, bidirectional communication between the WebAssembly client and the Jakarta EE backend. The protocol eliminates JSON overhead entirely, providing dense serialization and native object mapping.

## General Frame Structure

Every WebSocket frame in ZeroZ4j is binary and begins with a 4-byte ID:

```
[ 4 bytes: Correlation ID or Handle ID (Int) ]
[ 1 byte : Frame Type / Opcode (Optional for RMI Requests) ]
[ Variable : Payload ]
```

* **Correlation ID**: Used in Request/Response pairs to match server replies with suspended client coroutines.
* **Handle ID**: Used in LiveSync frames to identify the synced object graph.

## RPC Protocol (RMI)

Remote Method Invocations allow the client to call server-side CDI beans directly.

### RMI Request (Client -> Server)
Interestingly, standard RMI requests do not include a dedicated opcode byte. Instead, they rely on the length prefix of the interface name. Since the interface name string length is less than 16MB, the 5th byte (MSB of the length integer) is always `0x00`. The server interprets `0x00` as an RMI call because it falls outside the `0x10-0x1F` LiveSync opcode range.

**Structure:**
* `[4 bytes]` Message ID
* `[String]` Interface FQCN (Length + UTF-8 bytes)
* `[String]` Method Name
* `[4 bytes]` Argument Count
* `[N Elements]` Arguments (Type Tag + Value)

### Server Responses (Server -> Client)

Server responses include an explicit opcode byte at index 4.

* **0x01 — RMI_RESPONSE (Success)**
  * Payload: `[Type Tag + Value]` (The return value of the method)
* **0x0F — RMI_ERROR (Error)**
  * Payload: `[String]` (Error message)
* **0x02 — PUSH (Server-initiated)**
  * Payload: `[String]` (Topic Name) + `[Type Tag + Value]` (Payload)
* **0x03 — AUTH (Authentication Handshake)**
  * Sent automatically on connection open.
  * Payload: `[String]` (Username) + `[4 bytes]` (Role Count) + `[N Strings]` (Roles)

## LiveSync Protocol (0x10 – 0x1F)

LiveSync handles real-time object graph synchronization and reactive signals.

* **0x10 — SUBSCRIBE** (Client -> Server)
  * Payload: `[String]` Class Name
* **0x11 — SNAPSHOT** (Server -> Client)
  * Payload: `[8 bytes]` Version + `[Type Tag + Value]` Serialized Object
* **0x12 — UNSUBSCRIBE** (Client -> Server)
  * Payload: None (Handle ID is sufficient)
* **0x13 — MUTATE** (Client -> Server)
  * Propose a new state.
  * Payload: `[8 bytes]` Base Version + `[Type Tag + Value]` Serialized Object
* **0x14 — ACK** (Server -> Client)
  * Mutation accepted.
  * Payload: `[8 bytes]` New Version
* **0x15 — REJECT** (Server -> Client)
  * Mutation rejected (e.g., version conflict).
  * Payload: `[8 bytes]` Current Version + `[Type Tag + Value]` Current Object + `[String]` Reason
* **0x16 — SIGNAL_SUB** (Client -> Server)
  * Reserved. The current subscribe mechanism rides an RMI-shaped frame to the internal
    service `zeroz4j.signals` (method `subscribe`, one String argument: the signal name);
    the server intercepts it before service dispatch and answers with the retained value.
* **0x17 — SIGNAL_UPD** (Server -> Client)
  * Shared signal value. Broadcast to all sessions on every server-side change, and sent
    directly to a session in response to a subscribe.
  * Payload: `[String]` Signal Name + `[Type Tag + Value]` Serialized Value

## Binary Type Tags (Serialization)

ZeroZ4j serializes data dynamically using `BinarySerializer`. Each serialized argument or payload value is prefixed with a 1-byte type tag:

| Tag | Type | Encoding |
|-----|------|----------|
| `0x00` | Null | No payload |
| `0x01` | Integer | 4 bytes |
| `0x02` | Long | 8 bytes |
| `0x03` | Double | 8 bytes |
| `0x04` | Float | 4 bytes |
| `0x05` | Boolean | 1 byte (0 or 1) |
| `0x06` | String | 4-byte length + UTF-8 bytes |
| `0x07` | Object | `[String]` ClassName + Custom bytes from `BinaryPackable.writeToBuffer()` |
| `0x08` | Short | 2 bytes |
| `0x09` | Byte | 1 byte |
| `0x0A` | Character | 2 bytes |
| `0x0B` | List | 4-byte Size + `N` Elements (Tag + Value) |
| `0x0C` | Map | 4-byte Size + `N` Key/Value pairs (Tag + Value) |
| `0x0D` | Byte Array | 4-byte Length + `N` bytes |
