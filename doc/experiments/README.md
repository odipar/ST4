# Experiments

Ideas that were measured against the real corpus and the real tools, and
what the measurements said. Most entries are declines: an idea can be good,
measurable and still not worth its complexity - writing the numbers down is
what keeps it from being proposed, measured and declined a second time.
Some entries are diagnoses instead: a bug whose cause hid well enough that
the hunt itself is worth keeping.

An entry records: the idea, the method (what was packed or counted, with
what), the numbers, the verdict, and - when a cheaper variant remains
after the decline - the door left open.

| date | experiment | verdict |
|---|---|---|
| 2026-08-19 | [movep and pre-formatted streams](2026-08-19-movep-and-stream-formats.md) | declined - ties at best, +25% bytes at worst |
| 2026-08-19 | [register clustering](2026-08-19-register-clustering.md) | declined - real but marginal; fixed variant noted |
| 2026-08-19 | [the SID ticking](2026-08-19-sid-ticking.md) | diagnosed and fixed - the burst may not write a register a timer effect owns |
| 2026-08-20 | [SID phase semantics](2026-08-20-sid-phase-semantics.md) | surveyed - no two players implement re-start phase alike; v2 ships ym2149-rs's, -sidresume the alternative |
| 2026-08-20 | [the Synergy Credits hunt](2026-08-20-synergy-credits-sid-phase.md) | diagnosed and fixed - a phase bug every write-level instrument called correct |
| 2026-08-20 | [the drum reopen click](2026-08-20-drum-reopen-click.md) | diagnosed and fixed - one cautionary +1 frame in the packer, a click after every digidrum; differentials must compare event timing, and the capture chain must be validated before the player |
| 2026-08-20 | [the CoS dump audit](2026-08-20-cos-dump-audit.md) | dump vs original replay - melody exact to 0.00 semitones, drum samples replaced by the converter; en route, the drum rescue became a windowed-sinc resample to the ceiling |
| 2026-08-21 | [the timers left running](2026-08-21-the-timers-left-running.md) | diagnosed and fixed - identical chip writes, different machine: the host must stop the timers the player does not claim |
| 2026-08-21 | [the unmasked burst](2026-08-21-the-unmasked-burst.md) | changed - one instruction per register write, so the frame write needs no interrupt mask: 500 cycles of tick latency gone, and the frame got cheaper |
