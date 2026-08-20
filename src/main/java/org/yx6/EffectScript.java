package org.yx6;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The compiled effect script - format v2's replacement for the player's
 * effect interpreter.
 *
 * <p>The v1 player re-derived, on every frame, decisions that are pure
 * functions of the tune: is this code new or held, does the count need
 * reloading, which slot's timer must stop for whose drum. This class replays
 * exactly that decision logic - the branch structure of {@code yx6_slot} and
 * {@code yx6_pcm_start}, transcribed - over the whole timeline at pack
 * time, and emits five streams of prepared actions the player executes
 * without comparing anything against remembered state.
 *
 * <p>Names here are the YM format's, because the codes being compiled are:
 * a SID voice, a digidrum, a sync-buzzer, two effect slots. In the model
 * {@code doc/terminology.md} describes, all of these are TICK STREAMS -
 * values written to one register between frames, at a rate a timer sets -
 * and what this class decides for each is exactly a stream's lifecycle:
 * start, hold, retune (a new rate, the same place in the cycle), release,
 * resume, and which stream preempts which when two want one register. The
 * five streams it emits are script data, not register streams: their bytes
 * never reach the chip.
 *
 * <h2>The stream ABI (frozen: packer, player and rigs all cite this)</h2>
 *
 * <pre>
 * stream 14  M   master byte. 0 = nothing anywhere this frame.
 *                bit 0 = slot 1 acts (read A1, maybe P1)
 *                bit 1 = slot 2 acts
 *                bit 2 = apply the gate state in bits 5-3
 *                bits 5-3 = burst-gate mask, voices A/B/C, 1 = muted;
 *                           absolute state, idempotent to re-assert
 * stream 15  A1  slot 1 action: verb in bits 7-5, voice in bits 4-3,
 * stream 16  P1  bits 2-0 the prescaler (program verbs) or HELD flags;
 * stream 17  A2  P carries the MFP timer count for any action that
 * stream 18  P2  programs or reloads. Bytes on frames where a stream is
 *                not consumed are unspecified; the encoder repeats the
 *                previous byte, which the event optimizer packs away.
 * </pre>
 *
 * The verbs:
 *
 * <pre>
 * 1 HELD        flags: 1 = reload the count (P), 2 = track SID volume,
 *               4 = track buzzer shape - emitted only on frames where the
 *               value actually changed (v1 repatched unconditionally)
 * 2 STOP        stop this slot's timer (a SID or buzzer level dropped)
 * 3 SID_START   selects, volume, vector := the loud half, full program
 * 4 SID_RETUNE  volume, full stop/count/run - the vector is NOT touched:
 *               the square's phase lives through every retune
 * 5 BUZZ_START  shape, vector := the buzzer tick, full program
 * 6 DRUM        a trigger (fresh or the held-code retrigger): sample
 *               table lookup, select, vector, full program
 * 7 START_PCM_PREEMPT    as DRUM, but first stop the partner slot's timer - the
 *               contract's stop-the-victim-first order, straight-line
 * </pre>
 *
 * <p>A SID that went away and comes back - a released note, a drum that
 * borrowed the voice - always re-enters through SID_START, which restarts
 * the square at phase zero: the player writes the voice silent, and the
 * first tick - one timer period later - plays the loud half. Free-running
 * phase belongs only to a HELD code (and its retunes): the ym2149-rs
 * reference model, deterministic at every gap. SID_RETUNE is ONLY the held
 * prescaler-slide.
 *
 * <p>SID volume, buzzer shape and the drum number are read by the player
 * from the voice's own register ring (v1's mechanism), so they need no
 * streams; the packer merely marks the frames. The drummed voice's ring
 * byte is NOT sanitized: its burst write is gated (it lands on the PSG
 * select register and the next select overrides it), so nothing edits the
 * ring at runtime and v1's whole borrow/patch/restore machinery has no v2
 * counterpart. R7 arrives with the drummed voices' mixer forcing already
 * baked in ({@link Result#r7force}).
 *
 * <h2>Frame alignment</h2>
 *
 * A drum's end is the one genuinely asynchronous event: its sample runs out
 * at timer rate, mid-frame, and only the marker tick knows the instant. The
 * script reopens the voice's gate, releases the mixer and re-starts a
 * suppressed SID at the frame boundary AFTER the computed end - never
 * before, so a burst write can never race a live drum. The computation
 * allows for the arming phase (the trigger runs a bounded slice into its
 * frame) and no more: v1 reopened at the marker tick itself, and a whole
 * frame of grace on top of that parks the voice at the drum's tail volume,
 * tone forced off, 20ms longer than v1 - an audible click after every
 * drum.
 *
 * <h2>The split rotation</h2>
 *
 * v2's loop sections are compiled against one entry state, but the state
 * arriving from the intro and the state arriving from the wrap owe each
 * other nothing. A loop is a cycle, so it is cut where the states agree:
 * the smallest unit-aligned {@code c} with S(L+c) = S(O+c) rotates the
 * split to L+c, the intro absorbs the first {@code c} loop frames, and the
 * loop section is one full cycle taken from the steady window. The played
 * sequence is exactly v1's; the rotation is inaudible. Convergence within
 * one pass follows from determinism; if a tune needs more, the intro
 * absorbs a whole extra pass, and failing even that is a pack error.
 */
public final class EffectScript {

    // The action ABI. Verb 0 is the SID resume - the maxYMiser model: a
    // release only MASKS the timer interrupt (the counter keeps counting,
    // the square's half stays frozen), and coming back is an unmask plus a
    // reload of whatever changed; the phase runs on through the gap.
    public static final int VERB_RESUME = 0;
    public static final int RESUME_RELOAD = 1;
    public static final int RESUME_VOLUME = 2;
    public static final int VERB_HOLD = 1 << 5;
    public static final int VERB_RELEASE = 2 << 5;
    /** STOP flag bit 0: mask instead of stopping - a SID release. A buzzer
     * release hard-stops its timer. */
    public static final int RELEASE_MASK = 1;
    public static final int VERB_START_TOGGLE = 3 << 5;
    public static final int VERB_RETUNE = 4 << 5;
    public static final int VERB_START_RETRIGGER = 5 << 5;
    public static final int VERB_START_PCM = 6 << 5;
    public static final int VERB_START_PCM_PREEMPT = 7 << 5;
    public static final int HOLD_RELOAD = 1;
    public static final int HOLD_VOLUME = 2;
    public static final int HOLD_SHAPE = 4;

    // The master byte.
    public static final int M_SLOT1 = 1;
    public static final int M_SLOT2 = 2;
    public static final int M_GATES = 4;
    public static final int M_GATE_SHIFT = 3;

    public static int action(int verb, int voice, int low) {
        return verb | (voice << 3) | low;
    }

    /**
     * The compiled script: {@code frames} played frames splitting at
     * {@code split}, {@code source[p]} naming the dump frame each played
     * frame shows, the five effect streams, and the mixer bits to OR into
     * R7. {@code reopens} lists {playedFrame, voice} for every drum-end
     * edge - the differential test's skew windows - and {@code notes} what
     * a packer should tell the user.
     */
    public record Result(int frames, int split, int[] source,
                         byte[] m, byte[] a1, byte[] p1, byte[] a2, byte[] p2,
                         byte[] r7force, List<int[]> reopens, List<String> notes) {

        public byte[][] streams() {
            return new byte[][] {m, a1, p1, a2, p2};
        }
    }

    /** A voice's gate never reopens: its drum was cut mid-sample and the
     * marker that would have cleared it will never run (v1's stuck flag,
     * replicated for differential exactness). */
    private static final int STUCK = Integer.MAX_VALUE;

    private static final int KIND_TOGGLE = YmEffects.KIND_TOGGLE;
    private static final int KIND_PCM = YmEffects.KIND_PCM;
    private static final int KIND_RETRIGGER = YmEffects.KIND_RETRIGGER;

    /** One slot's remembered state - the v1 descriptor, field for field,
     * minus the machine addresses. */
    private static final class Slot {
        int elast;                      // SD_ELAST
        int tlast;                      // SD_TLAST
        int vec = -1;                   // what the timer vector holds: -1
        int vecVoice = -1;              // parked, else the type, plus voice
        int sel = -1;                   // the ISR's patched select voice
        int vol = -1;                   // the ISR's patched SID volume
        int shape = -1;                 // the ISR's patched buzzer shape
        boolean masked;                 // a released SID: interrupt masked,
                                        // the timer still counting
        int prescaler = -1;             // what the control register runs at

        int[] snapshot() {
            return new int[] {elast, tlast, vec, vecVoice, sel, vol, shape,
                    masked ? 1 : 0, prescaler};
        }
    }

    private final Ym6Reader.Song song;
    private final YmEffects.Extraction fx;
    private final int loopFrame;
    private final Slot[] slots = {new Slot(), new Slot()};
    private final int[] drumEnd = {-1, -1, -1};   // played frame the voice's
    private final int[] drumOwner = {-1, -1, -1}; // gate reopens; -1 = free
    private int gates;                            // bit v = muted
    private final List<int[]> reopens = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private boolean sidResume;          // the maxYMiser gap model

    // The emission arrays, over the full simulated horizon; cut at the end.
    private final byte[] m;
    private final byte[] a1;
    private final byte[] p1;
    private final byte[] a2;
    private final byte[] p2;
    private final byte[] r7;
    private final int horizon;
    private boolean stuckNoted;

    private EffectScript(Ym6Reader.Song song, YmEffects.Extraction fx, int loopFrame) {
        this.song = song;
        this.fx = fx;
        this.loopFrame = loopFrame;
        int total = song.frames();
        boolean loops = loopFrame >= 0 && loopFrame < total;
        // Simulate far enough to compare three loop passes.
        horizon = loops ? total + 3 * (total - loopFrame) : total;
        m = new byte[horizon];
        a1 = new byte[horizon];
        p1 = new byte[horizon];
        a2 = new byte[horizon];
        p2 = new byte[horizon];
        r7 = new byte[horizon];
    }

    /**
     * Compiles the script. {@code loopFrame} is the effective loop start
     * (after any CLI override), or negative for a play-once tune;
     * {@code unit} aligns the rotated split the way the encoder needs.
     */
    public static Result compile(Ym6Reader.Song song, YmEffects.Extraction fx,
                                 int loopFrame, int unit) {
        return compile(song, fx, loopFrame, unit, false);
    }

    /**
     * As above, choosing the SID gap model: {@code false} (the default) is
     * the ym2149-rs model - a release stops the timer and every re-arrival
     * restarts the square at phase zero; {@code true} is the maxYMiser
     * model - a release only masks the interrupt and a re-arrival resumes
     * the free-running phase. Both are verbs the player always carries;
     * the model is purely which bytes this simulator emits, so it could
     * even change mid-song if anything ever knew where to switch.
     */
    public static Result compile(Ym6Reader.Song song, YmEffects.Extraction fx,
                                 int loopFrame, int unit, boolean sidResume) {
        EffectScript script = new EffectScript(song, fx, loopFrame);
        script.sidResume = sidResume;
        return script.run(unit);
    }

    private Result run(int unit) {
        int total = song.frames();
        boolean loops = loopFrame >= 0 && loopFrame < total;
        int cycle = loops ? total - loopFrame : 0;

        int[][] snaps = new int[horizon + 1][];
        for (int p = 0; p < horizon; p++) {
            snaps[p] = snapshot(p);
            frame(p, source(p, total));
        }
        snaps[horizon] = snapshot(horizon);

        int split;
        int frames;
        if (!loops) {
            split = total;              // "looping at the end": one rule
            frames = total;
        } else {
            int cut = findCut(snaps, total, cycle, unit);
            split = cut;
            frames = cut + cycle;
            if (cut != loopFrame) {
                notes.add("loop split rotated by " + (cut - loopFrame)
                        + " frames so the wrap state matches");
            }
            // Belt and braces: the loop's first frame re-asserts the gate
            // state both arrivals agree on, unless it sets gates itself.
            if ((m[split] & M_GATES) == 0) {
                int mask = snaps[split][snaps[split].length - 1];
                if (mask != 0 || m[split] != 0) {
                    m[split] |= M_GATES | (mask << M_GATE_SHIFT);
                }
            }
        }

        int[] source = new int[frames];
        for (int p = 0; p < frames; p++) {
            source[p] = source(p, total);
        }
        reopens.removeIf(r -> r[0] >= frames);
        return new Result(frames, split, source,
                Arrays.copyOf(m, frames), Arrays.copyOf(a1, frames),
                Arrays.copyOf(p1, frames), Arrays.copyOf(a2, frames),
                Arrays.copyOf(p2, frames), Arrays.copyOf(r7, frames),
                List.copyOf(reopens), List.copyOf(notes));
    }

    /** The dump frame played frame {@code p} shows. */
    private int source(int p, int total) {
        if (p < total) {
            return p;
        }
        return loopFrame + (p - total) % (total - loopFrame);
    }

    /**
     * The smallest unit-aligned cut with S(L+c) = S(O+c); if one pass is
     * not enough for convergence, the intro absorbs a whole pass and the
     * search repeats one cycle later.
     */
    private int findCut(int[][] snaps, int total, int cycle, int unit) {
        for (int base = loopFrame; base <= total; base += cycle) {
            for (int c = 0; c < cycle; c += unit) {
                if (Arrays.equals(snaps[base + c], snaps[base + c + cycle])) {
                    return base + c;
                }
            }
            if (base + 2 * cycle + cycle > snaps.length - 1) {
                break;
            }
        }
        throw new IllegalArgumentException("the effect state never repeats "
                + "across the loop - this tune cannot be split (loop frame "
                + loopFrame + " of " + total + ")");
    }

    /** Everything two arrivals must agree on before sharing loop sections.
     * Drum ends compare as frames-remaining; the SID square's half is
     * deliberately absent - phase free-runs in v1 too. */
    private int[] snapshot(int frame) {
        int[] s1 = slots[0].snapshot();
        int[] s2 = slots[1].snapshot();
        int[] out = new int[s1.length + s2.length + 7];
        System.arraycopy(s1, 0, out, 0, s1.length);
        System.arraycopy(s2, 0, out, s1.length, s2.length);
        int at = s1.length + s2.length;
        for (int v = 0; v < 3; v++) {
            out[at++] = drumOwner[v];
            out[at++] = drumEnd[v] < 0 ? -1
                    : drumEnd[v] == STUCK ? STUCK : drumEnd[v] - frame;
        }
        out[at] = gates;                // last: run() reads the mask here
        return out;
    }

    // -------------------------------------------------------------------------
    // One played frame: expire drum windows, then slot 1, then slot 2 -
    // exactly the order the v1 player discovers the same events in.
    // -------------------------------------------------------------------------

    private int gatesBefore;

    private void frame(int p, int f) {
        gatesBefore = gates;

        for (int v = 0; v < 3; v++) {
            if (drumOwner[v] >= 0 && drumEnd[v] == p) {
                drumOwner[v] = -1;      // the marker has run by now: the
                drumEnd[v] = -1;        // gate reopens, the mixer frees
                gates &= ~(1 << v);
                reopens.add(new int[] {p, v});
            }
        }

        slot(p, f, 0);
        slot(p, f, 1);

        for (int v = 0; v < 3; v++) {
            if (drumOwner[v] >= 0) {    // the forced mixer, baked into R7
                r7[p] |= (byte) (0x09 << v);
            }
        }
        if (gates != gatesBefore) {
            m[p] |= M_GATES | (gates << M_GATE_SHIFT);
        }
    }

    /** yx6_slot, transcribed: the labels in the comments are v1's. */
    private void slot(int p, int f, int index) {
        Slot slot = slots[index];
        int code = (index == 0 ? fx.e1() : fx.e2())[f] & 0xFF;
        int count = (index == 0 ? fx.t1() : fx.t2())[f] & 0xFF;

        if (code == slot.elast) {
            if (code == 0) {
                return;                 // the idle frame
            }
            held(p, f, index, slot, code, count);
            return;
        }
        int old = slot.elast;           // move.b (a5),d4
        slot.elast = code;              // move.b d0,(a5)
        if (code == 0) {                // .released
            released(p, index, slot, old);
            return;
        }
        int voice = ((code >> 4) & 3) - 1;
        int type = code & 0xC0;
        if (type == KIND_TOGGLE) {         // .sid
            sid(p, f, index, slot, code, count, voice, old);
        } else if (type == KIND_PCM) { // .drum
            drum(p, f, index, slot, code, count, voice, old);
        } else {                        // the buzzer arm
            buzz(p, f, index, slot, code, count, voice);
        }
    }

    /** .held: a running effect's count reload and parameter tracking -
     * emitted only on frames where a value actually changed. */
    private void held(int p, int f, int index, Slot slot, int code, int count) {
        int type = code & 0xC0;
        int voice = ((code >> 4) & 3) - 1;
        if (type == KIND_PCM) {        // a held drum code retriggers every
            drum(p, f, index, slot, code, count, voice, code);
            return;                     // frame, with THAT frame's number
        }
        int flags = 0;
        if (count != slot.tlast) {      // cmp.b SD_TLAST(a5),d1
            slot.tlast = count;
            flags |= HOLD_RELOAD;
        }
        int value = parameter(f, voice);
        if (type == KIND_TOGGLE) {         // .track: v1 repatched blindly
            if (value != slot.vol) {
                slot.vol = value;
                flags |= HOLD_VOLUME;
            }
        } else if (value != slot.shape) {
            slot.shape = value;
            flags |= HOLD_SHAPE;
        }
        if (flags != 0) {
            emit(p, index, action(VERB_HOLD, voice, flags), count);
        }
    }

    /** .released: a buzzer level dropping stops its timer. A SID release
     * is where the two gap models fork: the default (ym2149-rs) stops the
     * timer too, so the next arrival restarts at phase zero; the resume
     * model (maxYMiser) only masks the interrupt - the counter keeps
     * counting, the square's half stays frozen, and {@code masked} routes
     * the next arrival through RESUME. A drum finishes itself. */
    private void released(int p, int index, Slot slot, int old) {
        int type = old & 0xC0;
        if (type == KIND_PCM) {
            return;                     // timer left running: the marker ends it
        }
        cut(p, index, -1);
        if (type == KIND_TOGGLE) {
            openOld(old);               // bsr yx6_burst_open_old
            if (sidResume) {
                slot.masked = true;
                emit(p, index, action(VERB_RELEASE, 0, RELEASE_MASK), 0);
                return;
            }
        }
        emit(p, index, action(VERB_RELEASE, 0, 0), 0);
    }

    private void sid(int p, int f, int index, Slot slot, int code, int count,
                     int voice, int old) {
        if (drumOwner[voice] >= 0) {    // the drum owns the volume register:
            slot.elast = 0;             // clr.b (a5) - retry next frame
            openOld(old);
            return;                     // nothing armed, nothing emitted
        }
        int value = parameter(f, voice);
        // The gap models fork on {@code masked}, which only a resume-mode
        // release sets (doc/experiments/2026-08-20-sid-phase-semantics.md):
        // a re-arrival on a slot whose masked timer still runs this SID's
        // square at the same prescaler RESUMES - unmask, reload only what
        // changed, the phase ran on through the gap. A prescaler change
        // across a masked gap needs the hardware's stop/load/start
        // (RETUNE, half kept). Everything else - and everything in the
        // default model - is a full START: phase zero, one silent timer
        // period, then the loud half. The gate bit is set on every path:
        // M carries the gates.
        boolean sameSid = slot.vec == KIND_TOGGLE && slot.vecVoice == voice
                && slot.sel == voice;
        boolean resume = slot.masked && sameSid && slot.prescaler == (code & 7);
        boolean retune = old != 0 && ((code ^ old) & 0xF0) == 0
                || slot.masked && sameSid && slot.prescaler != (code & 7);
        cut(p, index, -1);
        openOld(old);
        gates |= 1 << voice;
        slot.masked = false;
        if (resume) {
            int low = 0;
            if (count != slot.tlast) {
                slot.tlast = count;
                low |= RESUME_RELOAD;
            }
            if (value != slot.vol) {
                slot.vol = value;
                low |= RESUME_VOLUME;
            }
            emit(p, index, action(VERB_RESUME, voice, low), count);
            return;
        }
        slot.tlast = count;
        slot.vol = value;
        slot.prescaler = code & 7;
        if (retune) {
            emit(p, index, action(VERB_RETUNE, voice, code & 7), count);
            return;
        }
        slot.sel = voice;
        slot.vec = KIND_TOGGLE;
        slot.vecVoice = voice;
        emit(p, index, action(VERB_START_TOGGLE, voice, code & 7), count);
    }

    private void drum(int p, int f, int index, Slot slot, int code, int count,
                      int voice, int old) {
        if (old != code) {              // the old-effect cleanup; a
            if ((old & 0xC0) == KIND_TOGGLE && old != 0) {
                openOld(old);           // retrigger short-circuits it all
            } else if ((old & 0xC0) == KIND_PCM && old != 0
                    && ((old ^ code) & 0x30) != 0) {
                int orphan = ((old >> 4) & 3) - 1;
                if (drumOwner[orphan] == index) {
                    drumOwner[orphan] = -1;   // cut mid-sample: its marker
                    drumEnd[orphan] = -1;     // never runs, so the start
                    gates &= ~(1 << orphan);  // cleans up for it
                }
            }
        }
        // Arbitration: the partner holds a SID on this voice - its timer
        // stops FIRST (inside the START_PCM_PREEMPT handler), and it retries.
        Slot other = slots[1 - index];
        int verb = VERB_START_PCM;
        if ((other.elast & 0xC0) == KIND_TOGGLE && other.elast != 0
                && ((other.elast >> 4) & 3) - 1 == voice) {
            other.elast = 0;
            verb = VERB_START_PCM_PREEMPT;
        }
        cut(p, index, voice);           // the retrigger's own voice gets a
        slot.tlast = count;             // fresh window, not a stuck flag
        slot.masked = false;
        slot.prescaler = code & 7;
        slot.vec = KIND_PCM;
        slot.vecVoice = voice;
        gates |= 1 << voice;
        drumOwner[voice] = index;
        drumEnd[voice] = p + duration(f, code, count, voice);
        emit(p, index, action(verb, voice, code & 7), count);
    }

    private void buzz(int p, int f, int index, Slot slot, int code, int count,
                      int voice) {
        cut(p, index, -1);
        slot.tlast = count;
        slot.masked = false;
        slot.prescaler = code & 7;
        slot.shape = parameter(f, voice);
        slot.vec = KIND_RETRIGGER;
        slot.vecVoice = voice;
        emit(p, index, action(VERB_START_RETRIGGER, voice, code & 7), count);
    }

    /** yx6_burst_open_old: only an old SID's voice gate reopens. */
    private void openOld(int old) {
        if (old != 0 && (old & 0xC0) == KIND_TOGGLE) {
            gates &= ~(1 << (((old >> 4) & 3) - 1));
        }
    }

    /**
     * Any action that programs or stops this slot's timer cuts a drum the
     * slot still owes ticks to: its marker will never run, so its voice
     * stays muted and forced - v1's stuck flag, replicated and logged.
     */
    private void cut(int p, int index, int skip) {
        for (int v = 0; v < 3; v++) {
            if (v == skip) {
                continue;
            }
            if (drumOwner[v] == index && drumEnd[v] > p && drumEnd[v] != STUCK) {
                drumEnd[v] = STUCK;
                if (!stuckNoted) {
                    stuckNoted = true;
                    notes.add("an effect armed over its own slot's running "
                            + "drum: voice " + (char) ('A' + v)
                            + " stays muted (v1 semantics)");
                }
            }
        }
    }

    /** The voice's parameter register byte, as the player reads it. */
    private int parameter(int f, int voice) {
        return song.registers()[8 + voice][f] & 15;
    }

    /**
     * A drum's length in frames, rounded so the reopen is never early: the
     * sample plus its marker tick at the (already downsample-scaled) timer
     * rate, plus a sixteenth of a frame for the arming phase - the trigger
     * action runs a bounded slice into its VBL, so the last tick lands that
     * much later than the tick count alone says. A whole frame here instead
     * held every voice muted 20ms past its drum: the click v1 never had.
     */
    private int duration(int f, int code, int count, int voice) {
        int number = song.registers()[8 + voice][f] & 31;
        long samples = fx.drums()[number].length + 1L;
        long divisor = (long) YmEffects.PREDIV[code & 7] * count;
        long scaled = samples * divisor * song.playerHz()
                + YmEffects.MFP_CLOCK / 16;
        return (int) ((scaled + YmEffects.MFP_CLOCK - 1) / YmEffects.MFP_CLOCK);
    }

    private void emit(int p, int index, int action, int count) {
        m[p] |= (byte) (index == 0 ? M_SLOT1 : M_SLOT2);
        if (index == 0) {
            a1[p] = (byte) action;
            p1[p] = (byte) count;
        } else {
            a2[p] = (byte) action;
            p2[p] = (byte) count;
        }
    }
}
