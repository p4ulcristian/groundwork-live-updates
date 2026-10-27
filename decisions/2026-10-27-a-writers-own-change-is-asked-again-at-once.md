# A writer's own change is asked again at once

> The user's decision, 2026-10-27 (a change the browser learns from its own write's answer skips the calm window, so the writer sees their change at once; changes told by others keep the window; what TanStack Query does on a mutation's success: invalidate and refetch immediately). Since v0.6.1. Changes in part [The browser asks again calmly](2026-10-27-the-browser-asks-again-calmly.md) (its price, "the writer's own write's too", and its "instead of: the writer's own change asked at once, past the window") and [A change can be told by the browser, and an answer on its way is known](2026-10-23-a-change-can-be-told-and-an-answer-on-its-way-known.md) (`hear-changed-table!` was for the browser's own change).

**Decision:** the browser's store hears a change from one of two tellers, and the function names which:

- **`(hear-changed-table! store "orders")`**, a change told by others, as the sse line's `:groundwork-live-updates/changed` tells it (`make-sse-handlers` calls it): asked again calmly, as v0.6.0 decided.
- **`(hear-own-changed-table! store "orders")`**, new: a table this browser's own write changed, as the write's answer's notes name it. Gathered with the other changes for `:gather-changes-ms` (one write names several tables: one ask); then every watched question that read it is asked again at once, past `:ask-again-at-most-every-ms`:
  - a question waiting for its window is asked now, and its wait is called off (the answer has the others' change too);
  - a question that is out is asked again as soon as it lands (the ask out may have left before the write committed), not at its window's end;
  - on a page nobody can see, it is marked, and asked once when the page is seen again, as any change: the window is skipped, the page's visibility is not.

A gathering that holds both an own change and others' asks at once the questions the own change touched, and calmly the rest.

```clojure
;; the app's write, its answer's notes naming the tables it wrote
(-> (send! {:orders/mark-paid {:order/id id}})
    (.then (fn [answer]
             (doseq [table (get-in answer [:groundwork-steps/notes :orders/mark-paid :tables])]
               (live-updates/hear-own-changed-table! store table)))))
```

**Why:** the writer's screen is the one that must show the write: a window late reads as "my save didn't work". The others' changes are what the window is for, many tabs asking on a busy table; one writer's own write is one ask. TanStack Query's documented pattern is the same: on a mutation's success the app invalidates the queries it touched (`invalidateQueries`), and those being watched are refetched at once.

**The price:** two functions where there was one, told apart by who learned of the change. A writer's own change still waits `:gather-changes-ms`. The others' tabs see the write within their window, as before.

**Instead of:**
- **`hear-changed-table!` itself made the writer's, the sse line's handler calling the store directly:** the same name with another behaviour; an app calling it for its line would skip the window silently
- **an option on `hear-changed-table!`:** a second argument to tell what a function's name can
- **skipping the gathering too:** a write naming two tables would ask a question twice, the second after the first lands
