# The browser asks again calmly

> The user's decision, 2026-10-27 (the switch of Iron Rainbow onto groundwork-3, decision 2: live updates tell by table, the gates decide; the browser asks again calmly: only questions on a page someone can see, a hidden tab asking once when shown, at most once per question per window, a setting, e.g. 2000 ms). Since v0.6.0. Changes in part [The question store v1](2026-10-22-the-question-store-v1.md) and [A change can be told by the browser, and an answer on its way is known](2026-10-23-a-change-can-be-told-and-an-answer-on-its-way-known.md).

**Decision:** telling stays by table, as designed; the store asks again calmly. A change that touches a watched question (heard over sse, told by the browser itself, or every one after a break) asks it again:

- **only on a page someone can see.** The store is handed the page's document, `{:page js/document}`, a required option of `make-question-store`; while its `visibilityState` isn't `"visible"`, a touched question is only marked, and when the page is seen again (`visibilitychange`), each marked one is asked once.
- **at most once in `:ask-again-at-most-every-ms`** since it was last asked, a required setting (2000 for Iron Rainbow; 0, at once): a burst of changes is one ask, at the window's end.
- **once after a running ask**, when a change comes while it is out.
- A question with no answer yet (its first ask is out) counts as reading every table, so a change during its first ask is asked once after it too.

`watch-question!` and `ask-question-again!` ask at once, as before: a screen that opens, a refresh button. `is-question-being-asked?` says true while the question will be asked again: after the ask that is out, at its window's end, when the page is seen again, or by a change still gathered.

```clojure
(live-updates/make-question-store send! {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000 :ask-again-at-most-every-ms 2000}
                                  {:page js/document})
```

**Why:** telling by table rings every screen that read a busy table on every change to it; with many tabs open and orders changing all day, each tab asked again on every change. A tab nobody looks at needs only the last answer, when it is looked at; a screen someone looks at needs no more than one fresh answer a window.

**The price:** a screen shows a change up to a window late, the writer's own write's too when its question was asked within the window. A hidden tab's answers are as old as the moment it was hidden, until it is seen. One more setting, and the store's maker takes the page.

**Instead of:**
- **telling by row or by scope** (the decision's other choice): the gates decide who sees a row; the bell can't, without access in it
- **a hidden tab asking on its own timer**: asks nobody reads
- **the page's visibility read from `js/document` inside the store**: the store couldn't be tested on node, nor run where there is no document; handed in, the tests hand a stand-in
- **the writer's own change asked at once, past the window**: two rules for one ask, and a store that has to tell its own changes from the server's
