# Signals: Client-Side Reactive State

Zeroz4j's Signals are a client-side reactive state system for Wasm UIs: state lives in **signals**, derived values are **computed**, and rendering happens in **effects** that re-run automatically when anything they read changes. No manual listener wiring, no "remember to update the label too" bugs.

Signals are a standalone feature — they know nothing about the network. Server events can *feed* signals (see [SERVER_EVENTS.md](SERVER_EVENTS.md)), but neither feature requires the other.

## The primitives

| Type | Role |
|---|---|
| `ValueSignal<T>` | Mutable source state: `get()`, `set(value)`, `update(fn)` |
| `Computed<T>` | Lazily evaluated derived state; recomputes when dependencies change |
| `Effect` | Side-effect runner (usually rendering); re-runs when dependencies change |
| `ObservableSignal<T>` | The interface custom signal implementations must implement to participate in tracking |
| `Disposable` | Returned by `Effect.create`; releases the subscription |

## Automatic dependency tracking

Reading a signal with `.get()` inside a `Computed` or `Effect` registers it as a dependency — there is no subscribe call:

```java
ValueSignal<List<Task>> tasks = new ValueSignal<>(new ArrayList<>());
ValueSignal<String> filter = new ValueSignal<>("all");

Computed<List<Task>> visible = new Computed<>(() ->
        tasks.get().stream().filter(t -> matches(t, filter.get())).toList());

Computed<Integer> remaining = new Computed<>(() ->
        (int) tasks.get().stream().filter(t -> !t.isDone()).count());

Disposable render = Effect.create(() -> renderList(visible.get()));
Disposable badge  = Effect.create(() -> label.setText(remaining.get() + " open"));
```

Changing `tasks` or `filter` now updates exactly the parts of the UI that depend on them.

## The immutability contract

`ValueSignal.set()` skips notification when the new value `equals` the old one. **Never mutate a value in place and set it back** — the signal cannot see the change:

```java
// WRONG — same list reference, equality check swallows it, nothing re-renders:
tasks.get().add(task);
tasks.set(tasks.get());

// RIGHT — immutable update produces a new list:
tasks.update(current -> {
    List<Task> next = new ArrayList<>(current);
    next.add(task);
    return next;
});
```

## Component binding

Fields support two-way binding to a signal:

```java
ValueSignal<Boolean> darkTheme = new ValueSignal<>(true);
themeToggle.bindValue(darkTheme);   // toggle ⇄ signal stay in sync both ways
```

## Lifecycle

`Effect.create` returns a `Disposable`; `Computed` has a `dispose()` method. A view that creates effects or computeds must release them when it is permanently removed, or its upstream signals keep it alive:

```java
private final List<Disposable> disposables = new ArrayList<>();
...
disposables.add(Effect.create(() -> ...));
...
public void dispose() {
    disposables.forEach(Disposable::dispose);
    disposables.clear();
    myComputed.dispose();
}
```

## Threading

Signals are not synchronized — treat them as **UI-thread-only**. Server push handlers are dispatched onto the platform UI scheduler, so reducing events into signals from a `ServerEvents.on` handler is safe.

## Custom signals

Anything implementing `ObservableSignal<T>` (get + add/removeListener) participates in `Effect`/`Computed` tracking. The framework's own `ServerEvents.LatestSignal` — a signal holding the most recent payload of an event topic — is built exactly this way.

## When to use signals

* **Use them** when state is rendered in more than one place, or when values derive from other values (counts, filters, validation) — the cases where manual `render()` bookkeeping drifts.
* **Skip them** when a view has a single render path and no derived state — a plain field plus a render method is simpler (see the `chat-events` example for that style).

The `todo-signals` example (`zeroz4j-examples/todo-signals`) demonstrates the full model in isolation.
