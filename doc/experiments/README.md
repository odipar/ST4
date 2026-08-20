# Experiments

Ideas that were measured against the real corpus and the real tools, and
what the measurements said. Most entries are declines: an idea can be good,
measurable and still not worth its complexity - writing the numbers down is
what keeps it from being proposed, measured and declined a second time.
Some entries are diagnoses instead: a bug whose cause hid well enough that
the hunt itself is worth keeping.

An entry records: the idea, the method (what was packed or counted, with
what), the numbers, the verdict, and - when a cheaper variant survives the
decline - the door left open.

| date | experiment | verdict |
|---|---|---|
| 2026-08-19 | [movep and pre-formatted streams](2026-08-19-movep-and-stream-formats.md) | declined - ties at best, +25% bytes at worst |
| 2026-08-19 | [register clustering](2026-08-19-register-clustering.md) | declined - real but marginal; fixed variant noted |
| 2026-08-19 | [the SID ticking](2026-08-19-sid-ticking.md) | diagnosed and fixed - the burst may not write a register a timer effect owns |
- [2026-08-20 SID phase semantics](2026-08-20-sid-phase-semantics.md) — no player agrees on re-start phase; v2 keeps v1's deterministic loud-half restart
- [2026-08-20 the Synergy Credits hunt](2026-08-20-synergy-credits-sid-phase.md) — a phase bug every write-level instrument called correct; how it hid and what convicted it
