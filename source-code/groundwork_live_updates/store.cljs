(ns groundwork-live-updates.store
  "The browser keeps every answer under its question, [name params]:

     [:orders/of-shop {:shop/id #uuid \"…\"}]

   - A screen watches a question while it shows it; watching asks it, and
     shows the last answer at once while the fresh one comes.
   - Two asks of the same question while one is out make one request; the
     second is asked again when the first lands.
   - When asks overlap, only the newest answer counts.
   - Each answer comes with the tables it was read from (the server's notes);
     a change to one of them, heard over sse, asks again every watched
     question that read it, gathered for :gather-changes-ms. \"any\" means
     every change; so does an answer without notes (a refusal made
     before the handler's steps ran).
   - Asked again calmly: only on a page someone can see (a hidden tab asks
     once, when it is seen again), each question at most once in
     :ask-again-at-most-every-ms since it was last asked (a burst of
     changes is one ask, at the window's end), and a change while it is
     out asks it once after it lands. Watching and ask-question-again!
     ask at once.
   - A writer's own change, which the browser learns from its own write's
     answer (hear-own-change!), skips the window: what it touches is asked
     at once when the gathering ends (or once the ask that is out lands),
     and a window's wait for it is called off. Changes told by others keep
     the window.
   - An answer no screen watches is kept :keep-unwatched-answers-ms, then
     dropped.
   - An ask whose send-message! fails is answered groundwork-steps's refusal
     for no answer, {:groundwork-steps/refused :groundwork-steps/no-answer
     :groundwork-steps/data {:http-status nil}}, as groundwork-bridge's
     send-message! answers a request that got no response; that one never
     fails, so this comes only when the app's own send function does."
  (:require
   [groundwork-steps.api :as steps]))

(def ^:private no-answer
  "groundwork-steps's :groundwork-steps/no-answer, no response at all: no
   request id, since no server sent it."
  (steps/make-refusal :groundwork-steps/no-answer {:http-status nil}))

(defn- is-page-seen?
  "Whether someone can see the page: its document's visibilityState is
   \"visible\" (a tab in front, a window not minimised)."
  [{:keys [page]}]
  (= "visible" (.-visibilityState page)))

(declare ask-missed!)

(defn make-store
  "A store asking through send-message!, (fn [message]) -> a promise of the
   answer, keyed by name, with :groundwork-steps/notes beside it; settings
   and options checked already. It listens to the page's visibilitychange,
   to ask what changed while nobody could see it."
  [send-message! {:keys [gather-changes-ms keep-unwatched-answers-ms ask-again-at-most-every-ms]} {:keys [page]}]
  (let [store {:send-message!              send-message!
               :gather-changes-ms          gather-changes-ms
               :keep-unwatched-answers-ms  keep-unwatched-answers-ms
               :ask-again-at-most-every-ms ask-again-at-most-every-ms
               :page                       page
               :questions                  (atom {})
               :next-number                (atom 0)
               :changed                    (atom #{})
               :own-changed                (atom #{})
               :gathering?                 (atom false)}]
    (.addEventListener page "visibilitychange" #(when (is-page-seen? store) (ask-missed! store)))
    store))

(defn- tell-watchers! [store question]
  (let [{:keys [answer watchers]} (get @(:questions store) question)]
    (doseq [[_ on-answer] watchers]
      (on-answer answer))))

(defn- take-answer!
  "Keeps an answer and tells the watchers, if it is the newest ask's."
  [store question number answer tables]
  (when (= number (get-in @(:questions store) [question :newest-number]))
    (swap! (:questions store) update question assoc :answer answer :tables tables :answered? true)
    (tell-watchers! store question)))

(declare ask! ask-calmly! ask-at-once!)

(defn- land!
  "The ask is back: a question to ask again after it is asked, at once for
   a writer's own change, else calmly."
  [store question]
  (let [again (get-in @(:questions store) [question :ask-again?])]
    (swap! (:questions store) update question assoc :out? false :ask-again? false)
    (case again
      :at-once (ask-at-once! store question)
      false    nil
      nil      nil
      (ask-calmly! store question))))

(defn- find-tables-of-answer
  "The tables the server's notes say the answer was read from; \"any\"
   when it sent none."
  [answer message-name]
  (let [tables (get-in answer [:groundwork-steps/notes message-name :tables])]
    (if (some? tables) tables "any")))

(defn ask!
  "Asks a question now; while it is out, asks it once more after it lands
   (calmly: ask-calmly!)."
  [{:keys [send-message! next-number questions] :as store} [message-name params :as question]]
  (if (get-in @questions [question :out?])
    (swap! questions update question update :ask-again? #(if (= :at-once %) :at-once true))
    (let [number (swap! next-number inc)]
      (swap! questions update question assoc :out? true :newest-number number :asked-at (js/Date.now) :missed? false)
      (-> (send-message! {message-name params})
          (.then (fn [answer]
                   (take-answer! store question number (get answer message-name)
                                 (find-tables-of-answer answer message-name))))
          (.catch (fn [_]
                    (take-answer! store question number no-answer "any")))
          (.finally #(land! store question))))))

(defn- ask-calmly!
  "Asks a watched question again, calmly: on a page nobody can see, not
   now but once when it is seen again; while it is out, once after it
   lands; else at most once in :ask-again-at-most-every-ms since it was
   last asked, so a burst of changes is one ask, at the window's end. An
   unwatched question isn't asked."
  [{:keys [questions ask-again-at-most-every-ms] :as store} question]
  (let [{:keys [watchers out? waiting? asked-at]} (get @questions question)]
    (cond
      (empty? watchers)           nil
      (not (is-page-seen? store)) (swap! questions update question assoc :missed? true)
      out?                        (swap! questions update question update :ask-again? #(if (= :at-once %) :at-once true))
      waiting?                    nil
      :else
      (let [wait (if asked-at (- (+ asked-at ask-again-at-most-every-ms) (js/Date.now)) 0)]
        (if (pos? wait)
          (swap! questions update question assoc :waiting? true
                 :wait-timer (js/setTimeout (fn []
                                              (swap! questions update question assoc :waiting? false :wait-timer nil)
                                              (ask-calmly! store question))
                                            wait))
          (ask! store question))))))

(defn- ask-at-once!
  "Asks a watched question again for the writer's own change, past the
   window: a window's wait for it is called off and it is asked now; while
   it is out, once at once after it lands (the ask out may have left
   before the write). On a page nobody can see, once when it is seen
   again, as any change. An unwatched question isn't asked."
  [{:keys [questions] :as store} question]
  (let [{:keys [watchers out? wait-timer]} (get @questions question)]
    (cond
      (empty? watchers)           nil
      (not (is-page-seen? store)) (swap! questions update question assoc :missed? true)
      out?                        (swap! questions update question assoc :ask-again? :at-once)
      :else
      (do (when wait-timer (js/clearTimeout wait-timer))
          (swap! questions update question assoc :waiting? false :wait-timer nil)
          (ask! store question)))))

(defn- ask-missed!
  "The page is seen again: every watched question a change touched while
   it wasn't is asked, once each, calmly."
  [{:keys [questions] :as store}]
  (doseq [[question kept] @questions
          :when (and (:missed? kept) (seq (:watchers kept)))]
    (swap! questions update question assoc :missed? false)
    (ask-calmly! store question)))

(defn- drop-later! [{:keys [questions keep-unwatched-answers-ms]} question]
  (js/setTimeout #(swap! questions (fn [kept]
                                     (if (empty? (get-in kept [question :watchers]))
                                       (dissoc kept question)
                                       kept)))
                 keep-unwatched-answers-ms))

(defn watch!
  "(on-answer answer) with the kept answer now, if any, and with every new
   one; asks the question. Returns a function that stops watching."
  [{:keys [questions] :as store} question on-answer]
  (let [watcher-id (random-uuid)]
    (swap! questions update-in [question :watchers] assoc watcher-id on-answer)
    (when (get-in @questions [question :answered?])
      (on-answer (get-in @questions [question :answer])))
    (ask! store question)
    (fn []
      (swap! questions update-in [question :watchers] dissoc watcher-id)
      (when (empty? (get-in @questions [question :watchers]))
        (drop-later! store question)))))

(defn find-kept-answer
  "The answer kept for a question, or nil."
  [{:keys [questions]} question]
  (get-in @questions [question :answer]))

(defn- does-question-read?
  "Whether a change to these tables touches the question: it read one of
   them, or \"any\", or it has no answer yet (its first ask is out, and
   might read any)."
  [{:keys [tables]} changed]
  (or (nil? tables) (= "any" tables) (= :all changed) (some (set changed) tables)))

(defn- ask-watched-again!
  "Every watched question a change touched: at once when one of the
   writer's own changed tables touched it, else calmly."
  [{:keys [questions] :as store} changed own-changed]
  (doseq [[question kept] @questions
          :when (and (seq (:watchers kept)) (does-question-read? kept changed))]
    (if (and (seq own-changed) (does-question-read? kept own-changed))
      (ask-at-once! store question)
      (ask-calmly! store question))))

(defn hear-change!
  "A change to a table: told :by-others (the server's sse line) or
   :by-own-write (the browser's own write's answer). Gathered for
   :gather-changes-ms, then every watched question that read one of the
   changed tables is asked again: at once for the writer's own change
   (ask-at-once!), calmly for the others' (ask-calmly!)."
  [{:keys [changed own-changed gathering? gather-changes-ms] :as store} table told]
  (swap! changed conj table)
  (when (= :by-own-write told)
    (swap! own-changed conj table))
  (when-not @gathering?
    (reset! gathering? true)
    (js/setTimeout (fn []
                     (let [tables @changed
                           own    @own-changed]
                       (reset! changed #{})
                       (reset! own-changed #{})
                       (reset! gathering? false)
                       (ask-watched-again! store tables own)))
                   gather-changes-ms)))

(defn is-being-asked?
  "Whether an answer newer than the kept one is on its way: the question
   is out, or will be asked again: after the ask that is out, at the end
   of its window, when the page is seen again, or by a change heard and
   still gathered (it is watched and read a changed table)."
  [{:keys [questions changed]} question]
  (let [kept (get @questions question)]
    (boolean (or (:out? kept)
                 (and (seq (:watchers kept))
                      (or (:ask-again? kept) (:waiting? kept) (:missed? kept)
                          (and (seq @changed) (does-question-read? kept @changed))))))))

(defn hear-missed!
  "Changes may have been missed (the line broke): every watched question
   is asked again, calmly (ask-calmly!)."
  [store]
  (ask-watched-again! store :all #{}))
