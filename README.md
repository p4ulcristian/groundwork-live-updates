# groundwork-live-updates

A change to a table rings every open screen, and the screens that read
that table ask again. When a transaction commits, Postgres tells every
listening server which table changed: only its name, no ids, no values.
Each server tells every open sse line `{:groundwork-live-updates/changed {:table
"orders"}}`. Every answer says the tables it was read from, so the
browser's question store asks again only the watched questions that read
the changed table; the gates (Postgres's row rules) decide what each asker
gets. groundwork-live-updates decides no one's access.

```clojure
io.github.p4ulcristian/groundwork-live-updates {:git/tag "v0.5.14" :git/sha "9c29bbe"}
```

Needs [groundwork-postgres](https://github.com/p4ulcristian/groundwork-postgres) (Postgres: the
trigger's function, listening, the tables a query reads) and
[groundwork-steps](https://github.com/p4ulcristian/groundwork-steps) (a handler's notes and the
message's log in its steps' context, the browser's no-answer refusal),
[groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) (the app's metrics, handed
in), malli (the settings checked) and jsonista (Postgres's change messages
read). One public namespace,
`groundwork-live-updates.api`: a `.clj` for the server, a `.cljs` for the browser.

groundwork-live-updates requires no wire: it opens no line and writes on
none itself. `start-listening!` is handed, in its options, how an open line
is told a message (`:push!`, `(fn [line message])`) and what tells one line
from another (`:find-line-key`, `(fn [line])`), and the browser's store is
handed the app's send; the app plugs them into its wire (groundwork does,
with groundwork-bridge's sse lines and `send-message!`;
[why](decisions/2026-10-24-groundwork-live-updates-requires-no-wire.md)). So it
never makes a [groundwork-shaped-json](https://github.com/p4ulcristian/groundwork-shaped-json)
and takes none. Nor a log: `start-listening!` takes the app's under `:log`,
for groundwork-postgres's listener, and the notes are taken with the
message's own, `:groundwork-steps/log` ([why](decisions/2026-10-22-the-app-passes-its-log.md)).

`start-listening!` takes the app's meter under `:meter`, and the app's
table maps under `:tables`, and declares groundwork-live-updates's metrics
([why](decisions/2026-10-23-changes-and-tells-are-counted-on-the-apps-meter.md)):
`groundwork_live_updates_changes_total{table}`, each change heard, the tables that tell
as the set (another is `"other"`); `groundwork_live_updates_tells_total{message}`, each
`changed` or `missed` message written to a line; `groundwork_live_updates_listening`, 1
while the listener listens. It hands the meter to groundwork-postgres's listener, which
counts a break (`groundwork_postgres_listening_reconnects_total`); the wire counts the
open lines (groundwork-bridge's `groundwork_bridge_open_lines`).

## The api

| side | name | does |
|---|---|---|
| server | `function-files` | the `.sql` file of `groundwork_live_updates.tell()`, which the triggers call, for groundwork-postgres's `:functions` beside the app's own |
| server | `check-live-updates-of-tables` `put-live-updates-on-tables` | every table map says `:live-updates :tell` or `[:silent "why"]`; one that tells gets its triggers (groundwork-postgres's `:on-change`), a silent one `:on-change nil` |
| server | `put-table-notes-into-handlers` | every answer notes the tables its steps read (`:groundwork-steps/notes`) |
| server | `listening-settings-shape` `start-listening!` `stop-listening!` | listens on the channel `groundwork_live_updates`, tells every open line by the app's `:push!`; its batch window, waits and reconnect delay are required settings, the app's log, meter, tables, `:push!` and `:find-line-key` required options |
| server | `add-sse-line!` `remove-sse-line!` `count-open-lines` | for the app's sse open and close hooks; a line kept under its `:find-line-key` |
| both | `store-settings-shape` | the question store's settings, every key required |
| browser | `make-question-store` | answers kept by question, `[name params]`, asked through the app's send (groundwork-bridge's `send-message!` in groundwork) |
| browser | `watch-question!` `ask-question-again!` `find-kept-answer` | a screen watches what it shows; asked now, and again when a table it read changes |
| browser | `is-question-being-asked?` | whether a fresher answer is on its way: out now, or a gathered change will ask it |
| browser | `hear-changed-table!` | a table changed, told by the browser itself (a write's answer): gathered, then what read it is asked again |
| browser | `message-shapes-by-name` `make-sse-handlers` `hear-missed-changes!` | the two messages, their shapes and handlers; after a break, every watched question asked again |

Nothing has a default: every setting is written out; a missing one
refuses, named, with every other problem at once. What stays fixed, and
why: [the decision](decisions/2026-10-22-doorbell-is-its-own-library.md)
and [listening's numbers](decisions/2026-10-22-listening-s-numbers-are-settings.md).

```clojure
(require '[groundwork-live-updates.api :as live-updates]
         '[groundwork-postgres.api :as postgres])

(def orders {:table :orders :columns […] :indexes […] :uniques [] :checks [] :references [] :triggers []
             :gates nil :live-updates :tell})
(def logs   {:table :logs :columns […] :indexes […] :uniques [] :checks [] :references [] :triggers []
             :gates nil :live-updates [:silent "no screen shows logs"]})
;; put-live-updates-on-tables writes :on-change, the triggers or nil

;; the deploy, as the role that owns the tables: groundwork-postgres makes groundwork_live_updates.tell()
;; (function-files) before the triggers that call it
(postgres/migrate! owner-pool {:tables     (live-updates/put-live-updates-on-tables [orders logs])
                             :extensions []
                             :functions  live-updates/function-files   ; with the app's own
                             :views      []}
                 changes migrate-options)

;; the server, as the app's own role (it needs no grant for groundwork-live-updates)
(def bell (live-updates/start-listening! pool {:batch-window-ms        50     ; after the first change, how long more join its batch
                                          :check-stopped-every-ms 500    ; one wait for a change, before looking whether it was stopped
                                          :reconnect-after-ms     1000   ; after a break, the wait before listening again
                                          :stop-wait-ms           2000}  ; how long stop-listening! waits
                                     {:log    log                        ; the app's: a break in listening is written to it
                                      :meter  meter                      ; the app's: changes heard, lines told, listening
                                      :tables [orders logs]                ; the tables that tell are the changes' set
                                      ;; the app's wire (groundwork's: groundwork-bridge's sse lines)
                                      :push!         #(bridge/send-message-on-sse-line! %1 %2 nil)
                                      :find-line-key :groundwork-bridge/channel}))
;; plugged into the app's wire, as groundwork does it with groundwork-bridge
(bridge/make-routes-for-messages
 (live-updates/put-table-notes-into-handlers handlers)   ; each writes out :run-steps-in, nil for none
 settings
 {:shaper         shaper                                  ; the app's; each line carries it
  :log          log                                   ; the app's; each message's steps get one with its request id
  :meter        meter                                 ; the app's; bridge's metrics
  :on-sse-open  #(live-updates/add-sse-line! bell %)
  :on-sse-close #(live-updates/remove-sse-line! bell %)
  …})

;; the browser, plugged into the wire the same way
(def store (live-updates/make-question-store #(bridge/send-message! (:http addresses) shapes-by-name % {:shaper shaper :headers {} :keep-alive? false})
                                         {:gather-changes-ms         50       ; changes heard together, one round of asking
                                          :keep-unwatched-answers-ms 300000})) ; an unwatched answer is kept this long
(bridge/open-sse-line! (:sse addresses) (merge shapes-by-name live-updates/message-shapes-by-name)
                         (live-updates/make-sse-handlers store)
                         {:shaper               shaper
                          :on-open            #(when % (live-updates/hear-missed-changes! store))
                          :on-break           nil                  ; or (fn []), once per break
                          :reconnect-after-ms sse-reconnect-after-ms})
(def stop (live-updates/watch-question! store [:orders/of-shop {:shop/id shop-id}] show-orders!))
```

## Tests

```sh
clojure -X:test                              # the server; the Postgres one is skipped without a database
npm install && npx shadow-cljs compile test  # the browser's store, on node

# with a throwaway Postgres:
initdb -D /tmp/live-updates-pg -A trust -U "$USER"
pg_ctl -D /tmp/live-updates-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/live-updates-pg.log start
LIVE_UPDATES_TEST_PORT=5499 LIVE_UPDATES_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/live-updates-pg stop
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
