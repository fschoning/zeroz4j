# Agent Prompts: Build a Zeroz4j App

Ready-to-paste prompts for building Zeroz4j example applications with an AI coding agent. They serve three purposes:

1. **For users** — off-the-shelf starting points: paste a prompt into your agent, get a working app, then adapt it.
2. **For the project** — a growing set of showcase examples, buildable by any capable agent.
3. **As a DX test suite** — if a competent agent fails or goes off the rails with one of these prompts, that is a framework or documentation bug. Please open an issue with the transcript.

## How to use

1. Clone the repository and run `mvn install -DskipTests` once from the root, so the agent starts from a green build.
2. Paste the **Common Context** block below into your agent, followed by exactly one **Task** block.
3. When the agent finishes, run the acceptance checks at the end of the task block yourself.

Prompts are ordered roughly by difficulty. Each is independent.

---

## Common Context (paste this first, before any task)

```text
You are implementing a new example application for Zeroz4j, a zero-impedance Java
web framework: one language (Java 21) end-to-end — the client UI compiles to Wasm/JS
via TeaVM, the server runs on Helidon with CDI, and the SAME POJOs travel from
EclipseStore persistence through RMI to the browser. No REST, no JSON, no DTOs,
no JavaScript, no SQL.

Before writing any code, read these files in the repository:
- docs/CONCEPTS.md            (the 10 core concepts)
- docs/SIGNALS.md             (client-side reactive state)
- docs/SERVER_EVENTS.md       (typed server-to-client events)
- the README.md and full source of the REFERENCE EXAMPLE named in the task

Method — follow it exactly:
1. Copy the reference example's directory tree under zeroz4j-examples/ to the new
   example name given in the task (copy the pom.xml files as-is, then rename the
   artifactIds and module names consistently). Do NOT write poms from scratch.
2. Register the new module in zeroz4j-examples/pom.xml.
3. Keep the copied structure: <example>-shared (models + @RmiService interfaces),
   <example>-client (Wasm UI), <example>-server (Helidon server, EclipseStore
   boilerplate under .../server/store/). Keep the org.teavm.flavour.templates
   stub classes in the shared module — the TeaVM build needs them.
4. Replace the domain code with the task's spec. Package conventions:
   com.zeroz4j.example.model / .api / .client / .server
5. Update the <title> in both index.html files (client resources and server webapp).
6. Write a README.md for the example: what it demonstrates, how to run it.

Framework rules (violating these produces silent runtime failures):
- Wire models: annotate @BinaryModel, implement BinaryPackable, provide a public
  no-arg constructor plus getters/setters for every field. Add value-based
  equals/hashCode when the client needs to deduplicate instances.
- Services: @RmiService interface in the shared module; the APT generates a
  <Name>_Stub the client instantiates directly (new MyService_Stub()).
- Signals (client only): NEVER mutate a signal's value in place and set it back —
  ValueSignal.set() deduplicates by equals. Always build a new object/list in
  update(...). Collect every Disposable from Effect.create / ServerEvents.on and
  release them in a dispose() method; call dispose() on Computed instances too.
- Server events: declare EventTopic constants once in the shared module (explicit
  wire names like "job.statusChanged"); inject EventPublisher (never the transport
  engine) on the server; subscribe with ServerEvents.on BEFORE fetching initial
  state over RMI, then merge (events are at-most-once, no replay).
- LiveSync: annotate the state class @LiveSync, mutate it on the server, then call
  syncEngine.notifyChanged(state) — the client's instance updates in place.
- Persistence: the EclipseStore DataRoot pattern from the reference example
  (DataRoot + DefaultDataRootProvider + DefaultTenantResolver in .../server/store/).
  After mutating a collection, call storage.store(<the collection>).
- Blocking RMI calls from the client belong on a background thread
  (new Thread(...).start() — TeaVM green threads), as the reference examples do.

Hard constraints:
- Do NOT modify any framework module (zeroz4j-* directories) — examples only.
- Do NOT add external dependencies.
- Use only UI components that exist in
  zeroz4j-ui-components/src/main/java/com/zeroz4j/ui/component/ — check before use.
- If the spec leaves a choice open, make a sensible one and record it in the README.

Definition of done: `mvn install` from the repository root succeeds (including the
TeaVM compile of your client), and the acceptance criteria in the task are met.
```

---

## Task 1 — `form-signup`: forms with reactive validation

Feature focus: form components, `Computed` validation, two-way `bindValue`. Reference: `todo-signals`.

```text
TASK: Build the example "form-signup" under zeroz4j-examples/form-signup.
Reference example to copy: zeroz4j-examples/todo-signals.

A signup form for a fictional developer conference:
- Fields: full name (TextField), email (TextField), years of Java experience
  (Range or Select), T-shirt size (RadioButtonGroup or Select: S/M/L/XL),
  "subscribe to newsletter" (Toggle or Checkbox), short bio (TextArea, optional).
- Bind every field to a ValueSignal via bindValue(...). Lay the form out with
  FormLayout or VerticalLayout.
- Reactive validation, all client-side via Computed signals:
  * name: non-blank; email: contains "@" and "."; experience: 0–50.
  * a Computed<Boolean> formValid combining the field validations;
  * per-field error hints (a small Div under each field) driven by Effects —
    show the hint only after the field was touched (track touched state in signals);
  * the Submit button is disabled (setEnabled(false)) while formValid is false —
    driven by an Effect, never set manually in handlers.
- Shared model Registration (@BinaryModel) carrying the form data.
- @RmiService RegistrationService { void register(Registration r);
  List<Registration> listRegistrations(); } — the server appends to the
  EclipseStore DataRoot and persists.
- After a successful submit: show a Toast or Alert, clear the form signals, and
  render the (refetched) registration list under the form in a Table.

ACCEPTANCE:
- mvn install passes from the root.
- Typing an invalid email shows its hint and keeps Submit disabled; fixing it
  enables Submit with no explicit "recheck" code path — validation is entirely
  Computed/Effect driven.
- Submitted registrations survive a server restart (EclipseStore).
```

---

## Task 2 — `job-monitor`: a server-fed signal from a long-running job

Feature focus: a backend service publishing progress events from a fake external source; the UI updates automatically through `ServerEvents.latest` (a server-fed signal). Reference: `chat-events` (structure) + `todo-signals` (signals usage).

```text
TASK: Build the example "job-monitor" under zeroz4j-examples/job-monitor.
Reference example to copy: zeroz4j-examples/chat-events.

Simulate monitoring a long-running deployment pipeline:
- Shared model JobStatus (@BinaryModel): jobId (long), phase (String: one of
  "Queued", "Building", "Testing", "Deploying", "Done", "Failed"), percent (int
  0–100), message (String), running (boolean). Value-based equals/hashCode.
- Shared topics class JobEvents: EventTopic<JobStatus> STATUS_CHANGED with wire
  name "job.statusChanged".
- @RmiService JobService { JobStatus getCurrentStatus(); void startJob(); }.
- Server: an @ApplicationScoped JobRunner bean simulating the fake external
  source. startJob() launches a virtual thread (Thread.ofVirtual() or
  Executors.newVirtualThreadPerTaskExecutor()) that advances through the phases
  over ~20 seconds, updating percent in increments (Thread.sleep between ticks;
  small random variation is fine) and calling
  events.publish(JobEvents.STATUS_CHANGED, status) on every tick — inject
  EventPublisher. Roughly 1 run in 4 should end in "Failed" partway through.
  Ignore startJob() while a job is already running. Keep the latest JobStatus in
  the bean so getCurrentStatus() answers late-joining clients.
- Client: ServerEvents.latest(JobEvents.STATUS_CHANGED, <idle status>) gives a
  LatestSignal<JobStatus> — the "server-fed signal". Derive the whole UI from it
  with Effects/Computed, nothing else:
  * a Progress (or RadialProgress) bar bound to percent;
  * a Steps component showing the phases with the current one highlighted;
  * a StatusDot + Badge for the phase, styled differently for Done/Failed;
  * a "Start job" Button disabled (via an Effect) while running is true.
  On startup, call getCurrentStatus() ONCE (background thread) and, if it is
  fresher than the signal's value, feed it into the same state path — a client
  that joins mid-job must show the correct progress immediately.
- Open two browser windows: both must track the same job in real time.
- Provide dispose() releasing the LatestSignal and all Effects.

ACCEPTANCE:
- mvn install passes from the root.
- Clicking "Start job" animates the progress bar to completion (or failure)
  WITHOUT any client-side polling — no timers, no repeated RMI calls; the only
  client->server traffic after start is the initial getCurrentStatus().
- A second browser window opened mid-job shows the current progress within a tick.
```

---

## Task 3 — `inventory-crud`: master-detail persistence

Feature focus: EclipseStore CRUD, one POJO end-to-end, `Table`, master-detail editing. Reference: `todo-signals`.

```text
TASK: Build the example "inventory-crud" under zeroz4j-examples/inventory-crud.
Reference example to copy: zeroz4j-examples/todo-signals.

A small warehouse inventory manager:
- Shared model Product (@BinaryModel): id (long), name, category (String),
  quantity (int), unitPrice (double). Server assigns ids from a counter stored
  in the DataRoot (persist the counter too).
- @RmiService ProductService { List<Product> list(); Product save(Product p);
  void delete(long id); } — save() creates when id == 0, else updates; every
  mutation persists via storage.store(...).
- Client, master-detail layout (HorizontalLayout or SplitPane):
  * master: a Table of products (name, category, quantity, price) plus a
    TextField filter; hold products and the filter in ValueSignals, derive the
    filtered rows and a Stat/KpiTile header (product count, total stock value)
    via Computed, render via Effects;
  * detail: an edit form (TextFields, Select for category from a fixed list)
    that fills when a row is selected; New / Save / Delete buttons calling the
    service on background threads, then refreshing the products signal from the
    server's return value.
- Show an EmptyState component when the (filtered) list is empty.

ACCEPTANCE:
- mvn install passes from the root.
- Create, edit, delete round-trips update the table and the header stats without
  any manual "refresh" beyond re-setting the products signal.
- Data survives a server restart. The same Product class is used in storage, on
  the wire, and in the UI — verify no mapping code exists anywhere.
```

---

## Task 4 — `secure-admin`: authentication and role-based UI

Feature focus: `@Secured`, `@RolesAllowed`, `RmiSecurityContext`, role-dependent rendering. Reference: `chat-events` (its ChatService already uses @Secured/@RolesAllowed — study how).

```text
TASK: Build the example "secure-admin" under zeroz4j-examples/secure-admin.
Reference example to copy: zeroz4j-examples/chat-events.

A support-ticket queue with two roles:
- Shared model Ticket (@BinaryModel): id, title, description, status
  ("Open"/"Closed"), reporter (String).
- @RmiService @Secured TicketService:
  * List<Ticket> myTickets();            // tickets reported by the caller
  * void submit(String title, String description);
  * @RolesAllowed("admin") List<Ticket> allTickets();
  * @RolesAllowed("admin") void close(long id);
  Determine the caller via RmiRequestContext.getPrincipal() on the server —
  never trust a client-supplied username.
- Reuse the authentication mechanism exactly as the reference example's server
  is configured — study how the reference example authenticates and how the
  client learns its identity/roles (RmiSecurityContext.onAuthenticated,
  getUsername, hasAnyRole). Do not build a custom login screen unless the
  existing mechanism requires one.
- Client: after authentication, render:
  * for every user: a submit form and "my tickets" list;
  * ONLY when RmiSecurityContext.hasAnyRole("admin"): an admin panel listing all
    tickets with Close buttons.
  The admin panel must not merely be hidden — verify the server also rejects
  close()/allTickets() for non-admins (client-side hiding is cosmetic; the
  @RolesAllowed annotations are the actual security boundary).
- Handle the rejection gracefully: catching the RMI error and showing an Alert.

ACCEPTANCE:
- mvn install passes from the root.
- A non-admin user sees no admin panel AND gets a server-side error (surfaced as
  an Alert, not a crash) if close() is invoked anyway.
- README documents which users/roles exist and how to log in as each.
```

---

## Task 5 — `metrics-live`: implicit sync with @LiveSync

Feature focus: `@LiveSync` state mirrored automatically — the contrast to explicit events. Reference: `chat-livesync`.

```text
TASK: Build the example "metrics-live" under zeroz4j-examples/metrics-live.
Reference example to copy: zeroz4j-examples/chat-livesync.

A live server-metrics dashboard, fed by a fake metrics source:
- Shared model LiveMetrics (@LiveSync @BinaryModel): cpuPercent (int),
  requestsPerSecond (int), activeUsers (int), lastUpdated (long), plus a
  List<Integer> of the last 30 cpu samples for a sparkline.
- @RmiService MetricsService { LiveMetrics getMetrics(); }.
- Server: an @ApplicationScoped MetricsTicker that starts a virtual thread on
  first use (or eagerly) and every 2 seconds writes plausible drifting fake
  values into the LiveMetrics instance held in the DataRoot, appends the cpu
  sample (cap the list at 30), then calls syncEngine.notifyChanged(metrics) —
  exactly the pattern chat-livesync's ChatServiceImpl uses after mutations.
- Client: fetch LiveMetrics once via RMI, keep the reference, and render it into
  KpiTile/Stat components, a RadialProgress for cpu, and a Sparkline for the
  history. The framework updates the OBJECT'S FIELDS IN PLACE when the server
  notifies — there is no callback hook in this example, so re-render on a simple
  client-side interval (a background thread loop with Thread.sleep(1000) that
  re-reads the fields and updates the components). Note this honestly in the
  README: LiveSync moves the DATA automatically; re-rendering is the app's job.
- Two browser windows must show the same numbers changing in step.

ACCEPTANCE:
- mvn install passes from the root.
- Numbers and sparkline update continuously in the browser with NO RMI calls
  after the initial getMetrics() (verify: network tab shows only WebSocket
  frames).
- README contrasts this style with job-monitor's explicit events in 2–3
  sentences.
```

---

## Task 6 — Capstone: `zeroboard`, a real-time team kanban

Feature focus: everything composed — persistence, RMI, typed events, signals, security. This is the hardest task; attempt it only with the other examples green.

```text
TASK: Build the capstone example "zeroboard" under zeroz4j-examples/zeroboard.
Reference examples: copy zeroz4j-examples/chat-events for structure; study
todo-signals for the client state model before writing the UI.

A multi-user kanban board ("zeroboard") with three fixed columns: Backlog,
In Progress, Done.

Shared module:
- CardItem (@BinaryModel): id, title, column (String), createdBy, createdAt.
  Value-based equals/hashCode.
- BoardEvents: EventTopic<CardItem> CARD_UPSERTED ("board.cardUpserted"),
  EventTopic<Long> CARD_DELETED ("board.cardDeleted") — deleting broadcasts the id.
- @RmiService @Secured BoardService:
  * List<CardItem> getBoard();
  * CardItem addCard(String title);                  // into Backlog, server ids
  * CardItem moveCard(long id, String targetColumn); // validate target column
  * @RolesAllowed("admin") void deleteCard(long id);

Server (single BoardServiceImpl):
- EclipseStore persistence via the DataRoot pattern; server-assigned ids from a
  persisted counter; createdBy from RmiRequestContext.getPrincipal().
- After every successful mutation, persist, then publish the matching event via
  the injected EventPublisher. Reject moves to unknown columns.

Client (this is where the composition shows — follow it exactly):
- State: ONE ValueSignal<List<CardItem>> cards is the single source of truth,
  plus ValueSignal<String> searchText.
- Events feed the state: subscribe to CARD_UPSERTED (reduce: replace-by-id or
  append, immutably) and CARD_DELETED (reduce: remove-by-id) BEFORE calling
  getBoard(), then merge the snapshot (dedupe by id, keep the newer of
  duplicates). Own mutations do NOT update state directly — the echoed event is
  the single update path, so every window (including your own) renders identically.
- Derived state: Computed per column (filtered by column + searchText match) and
  a Computed count per column shown as a Badge in the column header.
- Rendering: three columns (GridLayout or HorizontalLayout of Cards); each card
  shows title + createdBy and Buttons to move left/right (no drag & drop); an
  add-card TextField+Button; a search TextField bound to searchText; admins
  additionally see a delete Button per card (RmiSecurityContext.hasAnyRole).
- All rendering via Effects; full dispose() for subscriptions, effects, computeds.

ACCEPTANCE:
- mvn install passes from the root.
- Two browser windows: adding/moving a card in one appears in the other (and in
  the acting window itself) via the event echo — there is no direct local-state
  write in any button handler.
- Search filters all three columns live; column counts always match visible cards.
- Non-admins see no delete buttons, and a forced deleteCard() is rejected
  server-side.
- README explains the event-echo pattern and why it guarantees convergence.
```

---

## Evaluating agent runs (for framework contributors)

When using these prompts to test how well agents handle Zeroz4j, record per run:

| Check | Pass/Fail |
|---|---|
| `mvn install` green on first agent-declared completion | |
| Acceptance criteria met without human fixes | |
| Framework rules honored (immutable signal updates, dispose, subscribe-before-fetch, no DTOs) | |
| Number of human interventions needed, and what unblocked each | |

Treat every failure as a signal: if the agent needed information that exists only in framework source code (not in docs/), that is a documentation gap; if it needed information that exists nowhere, that is an API usability gap. File issues accordingly — these prompts double as the framework's developer-experience regression suite.
