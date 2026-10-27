(ns groundwork-live-updates.api
  "A change to a table rings every open screen, and the screens that read
   that table ask again: the browser's side. The server's side is this
   namespace's .clj half.

   A store keeps answers by question, [name params]. A screen watches the
   questions it shows; a change the server tells of asks again the watched
   questions whose answers were read from the changed table:

     ;; the app plugs the store into its wire (groundwork does, with groundwork-bridge):
     ;; the send and the sse line are the app's, and so is the shaper they take
     (def store (live-updates/make-question-store #(bridge/send-message! (:http addresses) shapes-by-name % {:shaper groundwork-shaped-json :headers {} :keep-alive? false})
                                              {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000 :ask-again-at-most-every-ms 2000}
                                              {:page js/document}))
     (bridge/open-sse-line! (:sse addresses) (merge shapes-by-name live-updates/message-shapes-by-name)
                              (live-updates/make-sse-handlers store)
                              {:shaper               groundwork-shaped-json
                               :on-open            #(when % (live-updates/hear-missed-changes! store))
                               :on-break           nil
                               :reconnect-after-ms sse-reconnect-after-ms})
     (def stop (live-updates/watch-question! store [:orders/of-shop {:shop/id shop-id}] show-orders!))
     (live-updates/is-question-being-asked? store [:orders/of-shop {:shop/id shop-id}])  ; a fresher answer coming?
     (live-updates/hear-own-changed-table! store \"orders\")     ; this browser's write's answer noted it: asked at once
     (stop)                                                 ; when the screen goes"
  (:require
   [groundwork-live-updates.settings :as settings]
   [groundwork-live-updates.store :as store]))

(def message-shapes-by-name
  "The shapes of the server's two messages, for the wire's shapes-by-name
   (groundwork-bridge's):
   {:groundwork-live-updates/changed {:table \"orders\"}} and {:groundwork-live-updates/missed {}};
   neither is ever refused, so their :refusals are {}."
  {:groundwork-live-updates/changed {:params-shape nil :answer-shape [:map {:closed true} [:table :string]] :refusals {}}
   :groundwork-live-updates/missed  {:params-shape nil :answer-shape [:map {:closed true}] :refusals {}}})

(defn make-question-store
  "A store that asks through send-message!: (fn [message]) -> a promise of
   the answer keyed by name, with groundwork-steps's :groundwork-steps/notes
   beside it, as groundwork-bridge's send-message! gives it. Settings,
   every key required, checked first (:groundwork-live-updates/bad-settings):
   :gather-changes-ms, how long changes are gathered before the questions
   they touch are asked again; :keep-unwatched-answers-ms, how long an
   answer no screen watches is kept; :ask-again-at-most-every-ms, the
   window each question is asked again at most once in (2000). Options,
   every key required, checked first (:groundwork-live-updates/bad-options): :page,
   the page's document, js/document, whose visibilityState says whether
   someone sees it: a page nobody sees asks nothing again, and asks each
   question a change touched meanwhile once, when it is seen again."
  [send-message! settings options]
  (store/make-store send-message! (settings/check-store-settings settings) (settings/check-store-options options)))

(defn watch-question!
  "(on-answer answer) with the kept answer now, if any, and with each new
   one: asked now, and again when a table it was read from changes (calmly:
   on a page someone sees, at most once in :ask-again-at-most-every-ms,
   once after an ask that is out). Returns
   a function that stops watching; the answer is kept
   :keep-unwatched-answers-ms after the last watcher stops."
  [store question on-answer]
  (store/watch! store question on-answer))

(defn ask-question-again!
  "Asks a question again now (a refresh button, a try-again, a timer)."
  [store question]
  (store/ask! store question))

(defn find-kept-answer
  "The answer kept for a question, or nil."
  [store question]
  (store/find-kept-answer store question))

(defn is-question-being-asked?
  "Whether an answer newer than the kept one is on its way: the question
   is out now, or will be asked again: after the ask that is out, at the
   end of its window (:ask-again-at-most-every-ms), when the page is seen
   again, or by a change heard and still gathered (:gather-changes-ms). For waiting on the fresh answer rather than taking
   the kept one."
  [store question]
  (store/is-being-asked? store question))

(defn hear-changed-table!
  "A table changed (its name, \"orders\"), told by others, as the sse
   line's :groundwork-live-updates/changed tells it: gathered with the
   other changes for :gather-changes-ms, then every watched question that
   read it is asked again, calmly (at most once in
   :ask-again-at-most-every-ms)."
  [store table]
  (store/hear-change! store table :by-others))

(defn hear-own-changed-table!
  "A table this browser's own write changed (its name, \"orders\"), as
   the write's answer's notes name the tables it wrote: gathered with the
   other changes for :gather-changes-ms, then every watched question that
   read it is asked again at once, past :ask-again-at-most-every-ms (a
   window's wait for it called off; while it is out, once at once after
   it lands), so the writer sees their change. On a page nobody can see,
   once when it is seen again, as any change."
  [store table]
  (store/hear-change! store table :by-own-write))

(defn hear-missed-changes!
  "Asks again every watched question, calmly: for when the sse line opens
   again after a break, and changes may have gone unheard."
  [store]
  (store/hear-missed! store))

(defn make-sse-handlers
  "The handlers for the server's two messages, to open the sse line with:
   :groundwork-live-updates/changed asks again what a changed table touches
   (hear-changed-table!), :groundwork-live-updates/missed asks again everything watched
   (hear-missed-changes!)."
  [store]
  {:groundwork-live-updates/changed #(hear-changed-table! store (:table %))
   :groundwork-live-updates/missed  (fn [_] (hear-missed-changes! store))})
