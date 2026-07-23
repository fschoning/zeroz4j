# Server Events: Typed Push Topics

Zeroz4j lets the server broadcast **typed events** to connected Wasm clients over the existing binary WebSocket — no REST callbacks, no JSON, no hand-maintained topic strings on either side.

## Terminology

Zeroz4j uses four terms with distinct meanings — keeping them apart keeps the mental model clean:

| Term | Meaning |
|---|---|
| **Signal** | Client-side reactive *state*: `ValueSignal`, `Computed`, `Effect`, `ObservableSignal`. Always has a current value; changes are detected by equality. |
| **Event** | A discrete, fire-and-forget *occurrence* broadcast from server to client: `EventTopic`, `EventPublisher`, `ServerEvents`. There is no "current value" and no replay. |
| **Push** | The transport direction: the 0x02 PUSH frame that carries events over the WebSocket. |
| **Message** | Reserved for application domains (e.g. a `ChatMessage` in a chat app). Never a framework concept — Zeroz4j is not a message broker. |

The sentence that ties them together: **server events feed client signals through reducers.**

## Declaring topics

Declare each topic **once**, in your shared API module. The declaration binds the wire name to the payload type; server and client both compile against it, so a payload mismatch is a compile error:

```java
public final class ChatEvents {
    public static final EventTopic<ChatMessage> MESSAGE_POSTED =
            EventTopic.of(ChatMessage.class, "chat.messagePosted");

    public static final EventTopic<Void> HISTORY_CLEARED =
            EventTopic.of(Void.class, "chat.historyCleared");
}
```

Topic names are explicit strings, deliberately *not* derived from class names: renaming or moving a payload class never silently changes the wire protocol, and the names survive Wasm class-name minification.

## Publishing (server)

Inject `EventPublisher` into your `@RmiService` implementation — not the transport engine — and publish:

```java
@ApplicationScoped
public class ChatServiceImpl implements ChatService {
    @Inject private EventPublisher events;

    @Override
    public void sendMessage(String text) {
        ChatMessage msg = ...;
        // persist ...
        events.publish(ChatEvents.MESSAGE_POSTED, msg);
    }

    @Override
    public void clearHistory() {
        // persist ...
        events.publish(ChatEvents.HISTORY_CLEARED);
    }
}
```

## Subscribing (client)

Events are occurrences; your UI state lives in signals. The recommended pattern is a **reducer**: fold each event into a state signal with an immutable update (so `ValueSignal`'s equality-based change detection sees every change):

```java
ValueSignal<List<ChatMessage>> messages = new ValueSignal<>(new ArrayList<>());

Effect.Disposable sub = ServerEvents.on(ChatEvents.MESSAGE_POSTED, msg ->
        messages.update(list -> {
            List<ChatMessage> next = new ArrayList<>(list);
            next.add(msg);
            return next;
        }));
```

Every subscription returns a `Disposable` — dispose it when the owning view is permanently removed.

For topics whose payload genuinely *is* state (a status broadcast, a live counter), bridge events into the Signals world with `latest`:

```java
ServerEvents.LatestSignal<ServerStatus> status =
        ServerEvents.latest(StatusEvents.CHANGED, ServerStatus.UNKNOWN);

Effect.create(() -> statusLabel.setText(status.get().toString()));
```

## Avoiding the snapshot race

If a view loads initial state via RMI *and* listens for events, subscribe **before** fetching, then merge the snapshot with anything that arrived while the fetch was in flight (deduplicate via value equality on the payload):

```java
ServerEvents.on(ChatEvents.MESSAGE_POSTED, msg -> ...);   // 1. subscribe first
List<ChatMessage> history = chatService.getHistory();     // 2. then fetch
// 3. merge history with already-received events
```

The `chat-signals` example (`zeroz4j-examples/chat-signals`) demonstrates the full pattern end-to-end.

## Delivery semantics

Stated plainly so there are no surprises:

* **Broadcast** to all currently connected sessions (no per-topic subscription filtering yet).
* **At most once** — a disconnected client misses events; there is no queueing, acknowledgement, or redelivery.
* **No replay** — late subscribers do not receive past events.
* Payloads must be wire-serializable: `@BinaryModel` classes or types supported by `BinarySerializer`.

If you need durable delivery or replay, model it in your application (as the snapshot-then-merge pattern above does) — Zeroz4j deliberately does not include broker semantics.
