# A change heard during a question's first ask is weighed when its answer lands

> The user's decision, 2026-11-20. Changes in part [The browser asks again calmly](2026-10-27-the-browser-asks-again-calmly.md) and [A writer's own change is asked again at once](2026-10-27-a-writers-own-change-is-asked-again-at-once.md): what a change does to a question whose first ask is out.

**What was wrong:** a question learns which tables it reads from its first answer (the server's notes). While that first ask was out, the store counted the question as reading every table, so any change heard then, to any table, asked it once more when it landed. A page that writes a small record of its own at the start (a measurement of the device, of the page's speed) asked every question it had out a second time: on a real app's catalog page, every catalog question twice, the biggest a 6 MB answer that took 2–3 s, all for a change to a table none of them read.

**Decision:** a change heard while a question's first ask is out is kept on the question, the tables and whether they were the writer's own. When the answer lands it names the tables it was read from, and the kept changes are weighed against them: a writer's own change to one of them asks it again at once, another's asks it again calmly, and a change to none of them asks nothing. An answer that names no tables (`"any"`) or no answer at all counts as reading every table, as before; so does a missed-changes ask (`hear-missed-changes!`). A question already answered once still knows its tables and is weighed at once, as before.

**Why:** the answer that is on its way says exactly what the change has to be weighed against; waiting for it costs nothing, since the question could not be asked again before it landed anyway. Counting "not known yet" as "everything" turned every early write into a second ask of everything on the page.

**Instead of:**
- **the page's own records saying they changed no table:** wrong for the store to rely on, and any other early write (a pick, a cart add) would still ask everything out a second time
- **each question declaring its tables before it is asked:** every handler would say by hand what its query reads, and drift from it; the answer already says it
