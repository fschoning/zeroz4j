# chat-signals

A real-time chat demonstrating **typed server events feeding client-side Signals** — the explicit, reactive counterpart to the `chat-livesync` example.

## What it demonstrates

* **`EventTopic` / `EventPublisher` / `ServerEvents`** — server-to-client events declared once, typed end-to-end (`ChatEvents` in the shared module). No topic strings duplicated between server and client; a payload type mismatch is a compile error.
* **Events → reducers → signals** — pushes fold into a `ValueSignal<List<ChatMessage>>` via immutable updates; `Effect` re-renders the view and a `Computed` derives the message count.
* **Snapshot-race handling** — the view subscribes *before* fetching history, then merges the snapshot with any events that arrived in flight (deduplicated via `ChatMessage` value equality).
* **Lifecycle hygiene** — all subscriptions and effects are collected and released in `ChatView.dispose()`.
* **One POJO end-to-end** — the same `ChatMessage` class is persisted by EclipseStore, returned over RMI, broadcast as an event payload, and rendered by the Wasm UI. No DTOs, no mapping.

## How it differs from chat-livesync

| | chat-livesync | chat-signals |
|---|---|---|
| Sync style | Implicit: `@LiveSync` state object updated in place | Explicit: typed events + client-side reactive state |
| Client code | Receives mutated state transparently | Owns its state signals; reduces events into them |
| Best for | "Just mirror this server object" | Derived state, per-client views, explicit control |

## Running

Build the reactor once from the repository root, then start the server:

```bash
mvn install -DskipTests
cd zeroz4j-examples/chat-signals/chat-signals-server
mvn exec:java -Dexec.mainClass="com.zeroz4j.example.server.ExampleServer"
```

Open `http://localhost:8080` in two browser windows and chat between them.
