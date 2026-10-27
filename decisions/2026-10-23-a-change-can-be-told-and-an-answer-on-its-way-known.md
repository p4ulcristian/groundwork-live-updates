# A change can be told by the browser, and an answer on its way is known

> **Changed in part** by [A writer's own change is asked again at once](2026-10-27-a-writers-own-change-is-asked-again-at-once.md) (2026-10-27, v0.6.1): `hear-changed-table!` is a change told by others (the sse line); a change the browser learns from its own write's answer is told by `hear-own-changed-table!`, and asked again at once.

> **Changed in part** by [The browser asks again calmly](2026-10-27-the-browser-asks-again-calmly.md) (2026-10-27, v0.6.0): a change the browser tells is asked again calmly too; `is-question-being-asked?` is also true while a question waits for its window, for the page to be seen, or for the ask that is out.

> Adds to [The question store v1](2026-10-22-the-question-store-v1.md). Since v0.4.0.

**Decision:** the browser's side has two more functions.

- **`(hear-changed-table! store "orders")`** is what the sse line's `:doorbell/changed` does, now public: the change is gathered for `:gather-changes-ms` with the others, then every watched question that read the table is asked again. `make-sse-handlers` uses it. It is for a change the browser learns of itself: a write's answer, whose notes name the tables it wrote (parlor's `:parlor/send`).
- **`(is-question-being-asked? store question)`** says whether an answer newer than the kept one is on its way: the question is out now, or it is watched, read a table, and a change to that table is still being gathered, so the gathering will ask it. It is for something that wants the fresh answer rather than the kept one (parlor's `:parlor/once-answered`, v0.5.0's "waits for the request in flight").

```clojure
(doorbell/hear-changed-table! store "orders")
(doorbell/is-question-being-asked? store [:orders/of-shop {:shop/id shop-id}])   ;=> true while a fresher answer is coming
```

**Why:** parlor asked again through `(:doorbell/changed (doorbell/make-sse-handlers store))`, a handler made for the sse line, to get a function the store already has; and it couldn't tell the kept answer from one about to be replaced. A change gathered counts as being asked: a write's answer starts a gathering, and the question goes out only at its end, so "out" alone would say no in exactly the moment after a write.

**The price:** a question can be "being asked" for up to `:gather-changes-ms` before it is out. It is a question about now: an answer that has landed, but whose watcher hasn't acted on it yet (a re-frame event still queued), reads as not being asked; the caller compares the kept answer with the one it took (`find-kept-answer`).

**Instead of:**
- a callback when a question goes out: another required argument on `watch-question!` or a store setting, for one caller that only needs to ask once
- only "out": wrong for the whole gathering after a write
