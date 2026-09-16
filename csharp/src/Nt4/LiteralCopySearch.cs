// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Diagnostics;
using System.Globalization;

namespace Nt4;

/// <summary>
/// The optimizer for streams with copies from the literal stream: a search
/// over which units are literal, each step scored by an exact parse for that
/// choice and by what the compressor then writes, for as long as the search runs.
/// The port of the Java <c>St4LiteralCopySearch</c>.
/// </summary>
/// <remarks>
/// A dictionary is a set of forced literals: they stay literal, a copy comes
/// only from them, the parse decides the rest. The opening passes, what
/// <c>nt4 -c</c> alone writes, read the literals of a full-window parse, fill
/// holes of a few units, and shrink the dictionary to what gets copied from.
/// With time, a sweep frees or trims every literal run, keeping what packs
/// smaller; then random moves free, seed, extend or trim runs, accepted when
/// they pack smaller and by annealing when they do not, and the search
/// returns to the best and sweeps again when it stalls. The parse is
/// <see cref="FastOptimizer"/>'s DP with copies added: sources found through
/// two-unit chains over the dictionary, the rep of a copy as a ring rep at
/// the same output distance with literal shadows at the source, the literal
/// channel a queue a gamma class over match ends, chains rebuilt from a node pool,
/// and every parse restarted from a checkpoint before the first changed unit.
/// A copy is costed with the literal count of the dictionary, a lower bound,
/// so every copy is valid; the compressor's bits are the score.
/// </remarks>
public static class LiteralCopySearch
{
    private const int None = int.MinValue;

    /// <summary>Holes of up to this many units between dictionary runs are filled in the opening passes.</summary>
    private const int Hole = 3;

    /// <summary>The opening passes, at most.</summary>
    private const int Passes = 4;

    /// <summary>The annealing temperature, in bits, at the start and at the end.</summary>
    private const double Hot = 10.0;
    private const double Cold = 0.3;

    /// <summary>Steps without a new best before the search returns to the best.</summary>
    private const int Patience = 2000;

    /// <summary>
    /// The pool a collection allows against what it kept, the smallest pool
    /// it collects at, and what stands in the forwarding table for a node it
    /// keeps and has not moved yet.
    /// </summary>
    private const int PoolGrowth = 2;
    private const int PoolFloor = 1 << 20;
    private const int PoolMarked = -2;

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    /// <summary>
    /// Searches for <paramref name="seconds"/>, zero for the opening passes
    /// alone, and returns the best parse found: copies from the literal
    /// stream as negative offsets, matches within the window as positive ones.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit.</param>
    /// <param name="window">The furthest a match may reach back, in units.</param>
    /// <param name="maxOpLength">The compressor's operation limit, which the score counts.</param>
    /// <param name="seconds">How long to search.</param>
    /// <param name="progress">
    /// Whether to report on stdout: the opening passes as <see cref="ProgressMeter"/>
    /// does, then each improvement.
    /// </param>
    /// <returns>The final block of the best parse.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static Block Optimize(int[] units, int unit, int window, int maxOpLength,
                                 double seconds, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        long deadline = Stopwatch.GetTimestamp() + (long)(seconds * Stopwatch.Frequency);
        return new Search(units, unit, window, maxOpLength, 1, progress)
            .Run(deadline, long.MaxValue);
    }

    /// <summary>Searches for <paramref name="steps"/> steps from <paramref name="seed"/>, reproducibly; for the tests.</summary>
    internal static Block Optimize(int[] units, int unit, int window, int maxOpLength,
                                   long steps, long seed) =>
        new Search(units, unit, window, maxOpLength, seed, false).Run(long.MaxValue, steps);

    // ---------------------------------------------------------------- search

    private sealed class Search
    {
        private readonly int[] units;
        private readonly int unit;
        private readonly int window;
        private readonly int maxOpLength;
        private readonly int count;
        private readonly JavaRandom random;
        private readonly Parser parser;

        // The incumbent: its dictionary is exactly its literals.
        private bool[] forced;
        private Block chain;
        private int bits;
        private List<int[]> runs = new();         // literal runs {start, end, referenced}
        private List<int[]> copies = new();       // {start, end, isCopy, distance}

        private Block best;
        private int bestBits;

        // The budget: a deadline, a step count, and the steps taken.
        private long deadline;
        private long stepsAllowed;
        private long started;
        private long step;
        private long accepted;
        private long lastBest;
        private readonly bool progress;

        internal Search(int[] units, int unit, int window, int maxOpLength, long seed,
                        bool progress)
        {
            this.units = units;
            this.unit = unit;
            this.window = window;
            this.maxOpLength = maxOpLength;
            count = units.Length;
            random = new JavaRandom(seed);
            this.progress = progress;
            parser = new Parser(units, unit, window);
            started = Stopwatch.GetTimestamp();
            // The opening passes: the full-window parse's literals, holes
            // filled, shrunk to what gets copied from.
            int reach = Format.MaxOffsetUnits(unit);
            bool[] dictionary = Filled(LiteralCopySearch.LiteralMask(
                EventOptimizer.Optimize(units, unit, reach, progress), count));
            Block first = parser.Parse(dictionary, progress);
            chain = first;
            forced = LiteralCopySearch.LiteralMask(first, count);
            Adopt(first);
            best = chain;
            bestBits = bits;
            ReportPass(1);
            for (int pass = 1; pass < Passes; pass++)
            {
                bool[] next = Filled(Referenced());
                if (next.AsSpan().SequenceEqual(dictionary))
                {
                    break;
                }
                dictionary = next;
                Adopt(parser.Parse(dictionary, progress));
                if (bits < bestBits)
                {
                    best = chain;
                    bestBits = bits;
                }
                ReportPass(pass + 1);
            }
            ReturnToBest();
        }

        private void ReportPass(int pass)
        {
            if (progress)
            {
                Console.Error.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "{0,7:F1}s pass {1}: {2} bits, {3} bytes",
                    (Stopwatch.GetTimestamp() - started) / (double)Stopwatch.Frequency, pass,
                    bits, (bits + 7) / 8));
            }
        }

        /// <summary>Parses the best dictionary again, so the parser's base is the best.</summary>
        private void ReturnToBest() => Adopt(parser.Parse(LiteralMask(best)));

        /// <summary>
        /// Makes <paramref name="parsed"/>, the parse just made, the incumbent,
        /// its literals the dictionary from here on.
        /// </summary>
        private void Adopt(Block parsed)
        {
            parser.Accept();
            chain = parsed;
            bits = Compressor.Compress(parsed, units, unit, maxOpLength, -1, window).Bits;
            forced = LiteralMask(parsed);
            runs = new List<int[]>();
            copies = new List<int[]>();
            bool[] referenced = Referenced();
            int previous = -1;
            foreach (Block block in Blocks(parsed))
            {
                int start = previous + 1;
                if (block.Offset == 0)
                {
                    int used = 0;
                    for (int p = start; p <= block.Index; p++)
                    {
                        used |= referenced[p] ? 1 : 0;
                    }
                    runs.Add(new[] { start, block.Index, used });
                }
                else
                {
                    copies.Add(new[] { start, block.Index, block.Offset < 0 ? 1 : 0,
                        Math.Abs(block.Offset) });
                }
                previous = block.Index;
            }
        }

        /// <summary>The positions the incumbent's copies read from.</summary>
        private bool[] Referenced()
        {
            bool[] referenced = new bool[count];
            int previous = -1;
            foreach (Block block in Blocks(chain))
            {
                if (block.Offset < 0)
                {
                    int distance = -block.Offset;
                    for (int p = previous + 1; p <= block.Index; p++)
                    {
                        referenced[p - distance] = true;
                    }
                }
                previous = block.Index;
            }
            return referenced;
        }

        internal Block Run(long deadline, long steps)
        {
            this.deadline = deadline;
            stepsAllowed = steps;
            // Descend first: most of what the opening passes force packs
            // smaller free, and a sweep finds that run by run.
            Sweep();
            while (!Exhausted())
            {
                double fraction = steps == long.MaxValue
                    ? (double)(Stopwatch.GetTimestamp() - started) / Math.Max(1, deadline - started)
                    : (double)step / steps;
                double temperature = Hot * Math.Pow(Cold / Hot, Math.Min(1.0, fraction));
                bool[] proposal = (bool[])forced.Clone();
                string move = Propose(proposal);
                Block parsed = parser.Parse(proposal);
                int score = Evaluate(parsed);
                int delta = score - bits;
                if (delta <= 0 || random.NextDouble() < Math.Exp(-delta / temperature))
                {
                    Adopt(parsed);
                    accepted++;
                    NoteBest(move);
                }
                if (step - lastBest > Patience)
                {
                    // Stuck: back to the best, and descend from there again.
                    ReturnToBest();
                    Sweep();
                    lastBest = step;
                }
            }
            if (progress && step > 0)
            {
                Console.Error.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "{0} steps, {1} accepted: {2} bits, {3} bytes", step, accepted, bestBits,
                    (bestBits + 7) / 8));
            }
            return best;
        }

        private bool Exhausted() =>
            step >= stepsAllowed || (step % 8 == 0 && Stopwatch.GetTimestamp() >= deadline);

        /// <summary>Scores a parse: the compressor's bits. A step of the budget.</summary>
        private int Evaluate(Block parsed)
        {
            step++;
            return Compressor.Compress(parsed, units, unit, maxOpLength, -1, window).Bits;
        }

        private void NoteBest(string move)
        {
            if (bits < bestBits)
            {
                best = chain;
                bestBits = bits;
                lastBest = step;
                if (progress)
                {
                    Report(move);
                }
            }
        }

        /// <summary>
        /// Greedy descent: every literal run of the incumbent, in a random
        /// order, freed whole and trimmed at either end, keeping each change
        /// that packs smaller.
        /// </summary>
        private void Sweep()
        {
            var order = new List<int[]>(runs);
            for (int i = order.Count - 1; i > 0; i--)
            {
                int j = random.NextInt(i + 1);
                (order[i], order[j]) = (order[j], order[i]);
            }
            foreach (int[] run in order)
            {
                if (Exhausted())
                {
                    return;
                }
                int start = run[0];
                int end = run[1];
                if (!forced[start] && !forced[end])
                {
                    continue;                           // gone already
                }
                if (Improve(start, end + 1, "sweep free"))
                {
                    continue;
                }
                if (end > start && !Improve(start, start + 1, "sweep trim"))
                {
                    Improve(end, end + 1, "sweep trim");
                }
            }
        }

        /// <summary>Frees [from, to) when that packs smaller.</summary>
        private bool Improve(int from, int to, string move)
        {
            bool[] proposal = (bool[])forced.Clone();
            Array.Fill(proposal, false, from, to - from);
            Block parsed = parser.Parse(proposal);
            int score = Evaluate(parsed);
            if (score < bits)
            {
                Adopt(parsed);
                accepted++;
                NoteBest(move);
                return true;
            }
            return false;
        }

        private void Report(string move) =>
            Console.Error.WriteLine(string.Format(CultureInfo.InvariantCulture,
                "{0,7:F1}s {1,8} steps: {2} bits, {3} bytes  ({4})",
                (Stopwatch.GetTimestamp() - started) / (double)Stopwatch.Frequency, step,
                bestBits, (bestBits + 7) / 8, move));

        /// <summary>
        /// Changes the dictionary in place, and says how. The odds follow
        /// what each move saved when it was accepted: a move that changes
        /// one run at random is the walk the annealing makes, and the moves
        /// the parse aims - a run grown where a copy reads from, a gap
        /// between two runs filled - are what lands the bits. Weighted this
        /// way the search writes 1.08 per cent fewer bytes at the same
        /// steps over the corpus, on every seed tried (doc/research.md).
        /// </summary>
        private string Propose(bool[] dictionary)
        {
            int kind = random.NextInt(20);
            if (kind < 2)
            {
                Free(dictionary);
                return "free";
            }
            if (kind < 6)
            {
                Seed(dictionary);
                return "seed";
            }
            if (kind < 10)
            {
                Extend(dictionary);
                return "extend";
            }
            if (kind < 11)
            {
                Trim(dictionary);
                return "trim";
            }
            if (kind < 15)
            {
                Merge(dictionary);
                return "merge";
            }
            if (kind < 19)
            {
                Source(dictionary);
                return "source";
            }
            Free(dictionary);
            Seed(dictionary);
            return "free+seed";
        }

        /// <summary>
        /// Fills the gap between a literal run and the one after it. A move
        /// that grows one run reaches a gap of twenty units only by drawing
        /// its whole length at once; this one closes what stands between
        /// two runs.
        /// </summary>
        private void Merge(bool[] dictionary)
        {
            if (runs.Count < 2)
            {
                return;
            }
            int at = random.NextInt(runs.Count - 1);
            int gap = runs[at + 1][0] - runs[at][1] - 1;
            if (gap <= 0 || gap > 24)
            {
                return;
            }
            Array.Fill(dictionary, true, runs[at][1] + 1, runs[at + 1][0] - runs[at][1] - 1);
        }

        /// <summary>
        /// Grows the dictionary where a copy reads from, so that the copy
        /// may read further: the literals a copy needs stand at its source,
        /// and the parse names where that is.
        /// </summary>
        private void Source(bool[] dictionary)
        {
            if (copies.Count == 0)
            {
                return;
            }
            int[] op = copies[random.NextInt(copies.Count)];
            for (int attempt = 0; attempt < 4 && op[2] == 0; attempt++)
            {
                op = copies[random.NextInt(copies.Count)];
            }
            if (op[2] == 0)
            {
                return;
            }
            int size = 1 + random.NextInt(20);
            int from = random.NextBoolean() ? op[1] - op[3] + 1 : op[0] - op[3] - size;
            int lo = Math.Clamp(from, 0, count);
            int hi = Math.Clamp(from + size, 0, count);
            if (lo < hi)
            {
                Array.Fill(dictionary, true, lo, hi - lo);
            }
        }

        /// <summary>A literal run, unreferenced ones four times as likely, or none.</summary>
        private int[]? PickRun()
        {
            if (runs.Count == 0)
            {
                return null;
            }
            for (int attempt = 0; attempt < 4; attempt++)
            {
                int[] run = runs[random.NextInt(runs.Count)];
                if (run[2] == 0 || random.NextInt(4) == 0)
                {
                    return run;
                }
            }
            return runs[random.NextInt(runs.Count)];
        }

        /// <summary>Frees a literal run, or part of one, for the parse to match.</summary>
        private void Free(bool[] dictionary)
        {
            int[]? run = PickRun();
            if (run == null)
            {
                return;
            }
            int length = run[1] - run[0] + 1;
            if (random.NextBoolean())
            {
                Array.Fill(dictionary, false, run[0], length);
            }
            else
            {
                int size = 1 + random.NextInt(Math.Min(length, 8));
                int start = run[0] + random.NextInt(length - size + 1);
                Array.Fill(dictionary, false, start, size);
            }
        }

        /// <summary>Forces literals where a copy or match sits, so later copies can come from there.</summary>
        private void Seed(bool[] dictionary)
        {
            if (copies.Count == 0)
            {
                return;
            }
            int[] op = copies[random.NextInt(copies.Count)];
            for (int attempt = 0; attempt < 3 && op[2] == 0 && random.NextInt(4) != 0; attempt++)
            {
                op = copies[random.NextInt(copies.Count)];       // prefer copies
            }
            int length = op[1] - op[0] + 1;
            int size = random.NextBoolean() ? Math.Min(length, 32) : 1 + random.NextInt(Math.Min(length, 12));
            int start = op[0] + (random.NextBoolean() ? 0 : random.NextInt(length - size + 1));
            Array.Fill(dictionary, true, start, size);
        }

        /// <summary>
        /// Grows a literal run past its end, by one to twenty units. Twenty
        /// because a copy reads a source as long as itself: at eight the
        /// search reaches the same parse and is slower over it
        /// (doc/research.md).
        /// </summary>
        private void Extend(bool[] dictionary)
        {
            int[]? run = PickRun();
            if (run == null)
            {
                return;
            }
            int size = 1 + random.NextInt(20);
            if (random.NextBoolean())
            {
                int to = Math.Min(count, run[1] + 1 + size);
                Array.Fill(dictionary, true, run[1] + 1, to - (run[1] + 1));
            }
            else
            {
                int from = Math.Max(0, run[0] - size);
                Array.Fill(dictionary, true, from, run[0] - from);
            }
        }

        /// <summary>Shortens a literal run at either end by a unit or a few.</summary>
        private void Trim(bool[] dictionary)
        {
            int[]? run = PickRun();
            if (run == null)
            {
                return;
            }
            int length = run[1] - run[0] + 1;
            int size = 1 + random.NextInt(Math.Min(length, 3));
            if (random.NextBoolean())
            {
                Array.Fill(dictionary, false, run[1] + 1 - size, size);
            }
            else
            {
                Array.Fill(dictionary, false, run[0], size);
            }
        }

        private bool[] LiteralMask(Block parsed) => LiteralCopySearch.LiteralMask(parsed, count);
    }

    internal static List<Block> Blocks(Block chain)
    {
        var list = new List<Block>();
        for (Block? block = chain; block != null && block.Index >= 0; block = block.Chain)
        {
            list.Add(block);
        }
        list.Reverse();
        return list;
    }

    internal static bool[] LiteralMask(Block chain, int count)
    {
        bool[] literal = new bool[count];
        int previous = -1;
        foreach (Block block in Blocks(chain))
        {
            if (block.Offset == 0)
            {
                for (int p = previous + 1; p <= block.Index; p++)
                {
                    literal[p] = true;
                }
            }
            previous = block.Index;
        }
        return literal;
    }

    private static bool[] Filled(bool[] dictionary)
    {
        bool[] result = (bool[])dictionary.Clone();
        int run = 0;
        for (int p = 0; p < result.Length; p++)
        {
            if (result[p])
            {
                if (run > 0 && run <= Hole)
                {
                    Array.Fill(result, true, p - run, run);
                }
                run = 0;
            }
            else
            {
                run++;
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- parser

    /// <summary>
    /// The exact parse for one dictionary, on arrays reused across calls. Ring
    /// offsets 1..window and copy distances window+1..count-1 share one state
    /// index space and never meet.
    /// </summary>
    internal sealed class Parser
    {
        private readonly int[] units;
        private readonly int count;
        private readonly int literalBits;
        private readonly int window;
        private readonly int reach;

        // Per state index: the best chain ending in a match or copy there,
        // its cost, end and how to rebuild it, and its literal extension. A
        // state is a ring match at the last offset or at a new one, a copy
        // from the literal stream, or a rep of the last copy after literals;
        // what it is stands in the chain rather than in the state, since the
        // parse weighs the four by their bits alone.
        private readonly int[] stateBits;
        private readonly int[] stateEnd;
        private readonly int[] statePred;
        private readonly int[] stateNode;
        private readonly int[] litBits;
        private readonly int[] litEnd;
        private readonly int[] litNode;
        private readonly int[] matchLength;
        private readonly int[] stamp;

        // Per position: the winner, and the best match or copy ending there.
        private readonly int[] optimalBits;
        private readonly int[] winNode;
        private readonly int[] matchNodeSlot;         // by end + 1, so -1 has a slot
        private readonly int[] bestLength;

        // The dictionary as prefix counts, and the input's two-unit chains:
        // the previous position with the same two units, the same for every
        // dictionary.
        private readonly int[] forcedBefore;
        private readonly int[] prevSame2;
        private bool[] forced = Array.Empty<bool>();

        // Distances visited at the previous position, whose runs may end at
        // this one, and distances whose last copy could still be repped.
        private int[] activePrev;
        private int[] activeCur;
        private int activePrevCount;
        private int activeCurCount;
        private readonly int[] repable;
        private readonly bool[] inRepable;
        private int repableCount;

        // The position being parsed, and its best match or copy so far.
        private int bestMatch;
        private int bestMatchIdx;
        private int bestLengthSize;

        // The node pool, which keeps what the parse can still reach. A
        // parse of 48,063 units at a window of 32,512 makes 108 million
        // nodes and ends with 1.26 million of them reachable, and the
        // opening passes reach 330 million against 6.4 million: the rest is
        // a node each for a state the parse reached and left. A collection
        // marks from the arrays that name a node, renumbers what it keeps
        // into the front of the pool, and bounds the next collection at
        // PoolGrowth times that. Ids move, and a parse decides on bits
        // alone, so the bytes out are what they were.
        private int[] nodeEnd = new int[1024];
        private int[] nodeOffset = new int[1024];
        private int[] nodePred = new int[1024];
        private int[] nodeBits = new int[1024];
        private int nodes;
        private int limit = PoolFloor;             // the pool size a collection runs at
        private int[] forward = Array.Empty<int>();  // in a collection: where a node moved

        // The literal channel: the best match or copy end, by the gamma class
        // of the run length that reaches a position from it. A class is a
        // window in slot space that slides one slot a position, so a queue
        // kept least first reads its least in one step where a min-tree read
        // it in a logarithm. The values stand beside the queues, since a
        // parse that restarts at a checkpoint fills the queues from them.
        private readonly long[] leaf;              // by match end + 1: bits - end*literalBits
        private readonly int[][] dqAt;             // by class: the slots of its queue, least first
        private readonly int[] dqLo;               // by class: where its queue begins
        private readonly int[] dqEnd;              // by class: one past where its queue ends
        private readonly int classes;

        // Checkpoints: the state before position k*checkpoint, for the base
        // dictionary, the last parse accepted, and for the parse under way.
        // A parse restarts from the last checkpoint before its dictionary
        // first differs from the base's, since what stands before is independent of
        // what comes after. Nodes are appended past the base's, so a
        // rejected parse leaves the base's intact.
        private readonly int checkpoint;
        private readonly Snapshot[] baseline;
        private readonly Snapshot[] proposal;
        private bool[] baseForced = Array.Empty<bool>();
        private bool hasBase;
        private int poolTop;
        private int sharedUpTo;                    // checkpoints the parse under way shares

        internal Parser(int[] units, int unit, int window)
        {
            this.units = units;
            count = units.Length;
            literalBits = 8 * unit;
            this.window = window;
            reach = Format.MaxOffsetUnits(unit);
            int size = Math.Max(count, window) + 1;
            stateBits = new int[size];
            stateEnd = new int[size];
            statePred = new int[size];
            stateNode = new int[size];
            litBits = new int[size];
            litEnd = new int[size];
            litNode = new int[size];
            matchLength = new int[size];
            stamp = new int[size];
            optimalBits = new int[count];
            winNode = new int[count];
            matchNodeSlot = new int[count + 1];
            bestLength = new int[Math.Max(count, 3)];
            forcedBefore = new int[count + 1];
            prevSame2 = new int[count];
            activePrev = new int[size];
            activeCur = new int[size];
            repable = new int[size];
            inRepable = new bool[size];
            var last = new Dictionary<long, int>();
            for (int p = 0; p + 1 < count; p++)
            {
                long key = ((long)units[p] << 32) | (units[p + 1] & 0xFFFFFFFFL);
                prevSame2[p] = last.TryGetValue(key, out int previous) ? previous : -1;
                last[key] = p;
            }
            if (count > 0)
            {
                prevSame2[count - 1] = -1;
            }
            leaf = new long[count + 2];
            int kinds = 0;
            while ((1 << kinds) <= count + 1)
            {
                kinds++;
            }
            classes = kinds + 1;
            dqAt = new int[classes][];
            for (int k = 0; k < classes; k++)
            {
                dqAt[k] = new int[count + 2];
            }
            dqLo = new int[classes];
            dqEnd = new int[classes];
            checkpoint = Math.Max(1024, (count + 7) / 8);
            int slots = (count + checkpoint - 1) / checkpoint;
            baseline = new Snapshot[slots];
            proposal = new Snapshot[slots];
            for (int k = 0; k < slots; k++)
            {
                baseline[k] = new Snapshot(size, count);
                proposal[k] = new Snapshot(size, count);
            }
        }

        /// <summary>A parse's whole state before a checkpoint position.</summary>
        private sealed class Snapshot
        {
            internal readonly int[] StateBits;
            internal readonly int[] StateEnd;
            internal readonly int[] StatePred;
            internal readonly int[] StateNode;
            internal readonly int[] LitBits;
            internal readonly int[] LitEnd;
            internal readonly int[] LitNode;
            internal readonly int[] MatchLength;
            internal readonly int[] Stamp;
            internal readonly int[] OptimalBits;
            internal readonly int[] WinNode;
            internal readonly int[] MatchNodeSlot;
            internal readonly long[] Leaves;
            internal readonly int[] ActivePrev;
            internal readonly int[] ActiveCur;
            internal readonly int[] Repable;
            internal int ActivePrevCount;
            internal int ActiveCurCount;
            internal int RepableCount;
            internal int Position;
            internal bool Valid;

            internal Snapshot(int size, int count)
            {
                StateBits = new int[size];
                StateEnd = new int[size];
                StatePred = new int[size];
                StateNode = new int[size];
                LitBits = new int[size];
                LitEnd = new int[size];
                LitNode = new int[size];
                MatchLength = new int[size];
                Stamp = new int[size];
                OptimalBits = new int[count];
                WinNode = new int[count];
                MatchNodeSlot = new int[count + 1];
                Leaves = new long[count + 1];
                ActivePrev = new int[size];
                ActiveCur = new int[size];
                Repable = new int[size];
            }
        }

        internal Block Parse(bool[] dictionary) => Parse(dictionary, false);

        /// <summary>As above, reporting on stdout as <see cref="ProgressMeter"/> does.</summary>
        internal Block Parse(bool[] dictionary, bool progress)
        {
            int from = 0;
            if (hasBase)
            {
                from = count - 1;
                for (int p = 0; p < count; p++)
                {
                    if (dictionary[p] != baseForced[p])
                    {
                        from = p;
                        break;
                    }
                }
            }
            int slot = from / checkpoint;
            while (slot > 0 && !baseline[slot].Valid)
            {
                slot--;
            }
            sharedUpTo = slot;
            forced = dictionary;
            int start = slot * checkpoint;
            if (slot == 0)
            {
                Prepare();
            }
            else
            {
                Restore(baseline[slot]);
            }
            for (int p = start; p < count; p++)
            {
                forcedBefore[p + 1] = forcedBefore[p] + (forced[p] ? 1 : 0);
            }
            Queues(start);
            var meter = new ProgressMeter(ProgressMeter.TotalSteps(count, start, window), progress);
            for (int index = start; index < count; index++)
            {
                if (nodes > limit)
                {
                    Collect(index);
                }
                if (index > 0 && index % checkpoint == 0)
                {
                    TakeSnapshot(proposal[index / checkpoint], index);
                }
                bool literalOnly = forced[index];
                int value = units[index];
                bestLengthSize = 2;

                // The literal channel: the best match or copy end, per gamma
                // class of the run length that reaches here from it.
                int litCand = int.MaxValue;
                int litE = 0;
                for (int k = 0; ; k++)
                {
                    int slotHi = index - (1 << k) + 1;
                    if (slotHi < 0)
                    {
                        break;
                    }
                    int slotLo = Math.Max(0, index - (2 << k) + 2);
                    long found = Least(k, slotLo, slotHi);
                    if (found != long.MaxValue)
                    {
                        int candidate = (int)(found >> 32) + index * literalBits + 2 + 2 * k;
                        if (candidate < litCand)
                        {
                            litCand = candidate;
                            litE = (int)found - 1;
                        }
                    }
                }

                bestMatch = int.MaxValue;
                bestMatchIdx = -1;

                // Ring offsets: the reference DP.
                int maxOffset = (int)Math.Clamp((long)index, Optimizer.InitialOffset, window);
                for (int offset = 1; offset <= maxOffset; offset++)
                {
                    if (!literalOnly && index != 0 && value == units[index - offset])
                    {
                        if (litEnd[offset] != None)
                        {
                            if (matchLength[offset] == 0)
                            {
                                litNode[offset] = NewNode(litEnd[offset], 0,
                                    Node(offset), litBits[offset]);
                            }
                            int bits = litBits[offset] + 1
                                + EliasGammaBits(index - litEnd[offset]);
                            SetState(offset, bits, index, litNode[offset]); // a ring rep
                            if (bits < bestMatch)
                            {
                                bestMatch = bits;
                                bestMatchIdx = offset;
                            }
                        }
                        if (++matchLength[offset] > 1)
                        {
                            bestLengthSize = ExtendBestLength(bestLengthSize, matchLength[offset],
                                index);
                            int length = bestLength[matchLength[offset]];
                            int bits = optimalBits[index - length] + 3
                                + (offset > Format.ByteOffsetLimit ? 16 : 8)
                                + EliasGammaBits(length - 1);
                            if (stateEnd[offset] != index || stateBits[offset] > bits)
                            {
                                // A ring match at a new offset.
                                SetState(offset, bits, index, winNode[index - length]);
                                if (bits < bestMatch)
                                {
                                    bestMatch = bits;
                                    bestMatchIdx = offset;
                                }
                            }
                        }
                    }
                    else
                    {
                        matchLength[offset] = 0;
                        if (stateEnd[offset] != None)
                        {
                            int length = index - stateEnd[offset];
                            litBits[offset] = stateBits[offset] + 1 + EliasGammaBits(length)
                                + length * literalBits;
                            litEnd[offset] = index;
                        }
                    }
                }

                // Copies. A copy needs two units, so the two-unit chain finds
                // the runs, restricted to dictionary pairs beyond the window;
                // a run in progress the chain no longer lists ends here and is
                // visited for its last unit; a distance whose last copy could
                // still be repped is visited wherever its unit matches, since
                // a rep may be one unit.
                (activePrev, activeCur) = (activeCur, activePrev);
                activePrevCount = activeCurCount;
                activeCurCount = 0;
                if (!literalOnly)
                {
                    if (index + 1 < count)
                    {
                        for (int p = prevSame2[index]; p >= 0; p = prevSame2[p])
                        {
                            if (forced[p] && forced[p + 1] && index - p > window)
                            {
                                Visit(index, index - p);
                            }
                        }
                    }
                    for (int a = 0; a < activePrevCount; a++)
                    {
                        int distance = activePrev[a];
                        if (stamp[distance] == index - 1 && value == units[index - distance]
                            && forced[index - distance])
                        {
                            Visit(index, distance);
                        }
                    }
                    for (int r = 0; r < repableCount; r++)
                    {
                        int distance = repable[r];
                        if (stamp[distance] != index && value == units[index - distance]
                            && forced[index - distance])
                        {
                            Visit(index, distance);
                        }
                    }
                }
                // A distance stays reppable while the literals since its copy
                // have literal shadows at the source.
                for (int r = repableCount - 1; r >= 0; r--)
                {
                    int distance = repable[r];
                    if (stateEnd[distance] < index && stamp[distance] != index
                        && !forced[index - distance])
                    {
                        inRepable[distance] = false;
                        repable[r] = repable[--repableCount];
                    }
                }

                // The winner, and the literal channel's next entry.
                if (bestMatch < litCand)
                {
                    optimalBits[index] = bestMatch;
                    winNode[index] = Node(bestMatchIdx);
                }
                else
                {
                    Debug.Assert(litCand != int.MaxValue, "a literal run always reaches");
                    optimalBits[index] = litCand;
                    winNode[index] = NewNode(index, 0, matchNodeSlot[litE + 1], litCand);
                }
                if (bestMatch != int.MaxValue)
                {
                    matchNodeSlot[index + 1] = Node(bestMatchIdx);
                    Update(index + 1, ((long)(bestMatch - index * literalBits) << 32)
                        | (long)(index + 1));
                }
                meter.Advance(Math.Clamp(index, Optimizer.InitialOffset, window));
            }
            meter.Finish();
            return Rebuild(winNode[count - 1]);
        }

        /// <summary>
        /// A copy distance whose unit matches at <paramref name="index"/> with
        /// the source in the dictionary: continues or starts its run, and
        /// enters the rep of the last copy at that distance and the copy
        /// ending here.
        /// </summary>
        private void Visit(int index, int distance)
        {
            int p = index - distance;
            if (stamp[distance] != index - 1)
            {
                // A run starts. Its rep continues the last copy at this
                // distance when the literals since have literal shadows at
                // the source.
                matchLength[distance] = 1;
                litNode[distance] = -1;
                if (stateEnd[distance] != None)
                {
                    int end = stateEnd[distance];
                    int between = index - 1 - end;
                    if (forcedBefore[p] - forcedBefore[end - distance + 1] == between)
                    {
                        int bits = stateBits[distance] + 1 + EliasGammaBits(between)
                            + between * literalBits;
                        litBits[distance] = bits;
                        litEnd[distance] = index - 1;
                        litNode[distance] = NewNode(index - 1, 0, Node(distance), bits);
                    }
                }
            }
            else
            {
                matchLength[distance]++;
            }
            stamp[distance] = index;
            activeCur[activeCurCount++] = distance;
            int run = matchLength[distance];
            if (litNode[distance] >= 0)
            {
                int bits = litBits[distance] + 1 + EliasGammaBits(run);
                SetState(distance, bits, index, litNode[distance]); // a rep of a copy
                if (bits < bestMatch)
                {
                    bestMatch = bits;
                    bestMatchIdx = distance;
                }
            }
            if (run > 1)
            {
                // Literals from the source's last unit to here: a copy of n
                // units reads back n - 1 more and leaves at least one literal
                // between.
                int between = forcedBefore[index] - forcedBefore[p];
                if (between < 2)
                {
                    return;
                }
                int longest = Math.Min(run, reach - window - between + 1);
                if (longest < 2)
                {
                    return;
                }
                bestLengthSize = ExtendBestLength(bestLengthSize, longest, index);
                int length = bestLength[longest];
                int bits = optimalBits[index - length] + 3
                    + (window + between + length - 1 > Format.ByteOffsetLimit ? 16 : 8)
                    + EliasGammaBits(length - 1);
                int byteLongest = Math.Min(longest, Format.ByteOffsetLimit + 1 - window - between);
                if (byteLongest >= 2 && byteLongest < longest)
                {
                    int shorter = bestLength[byteLongest];
                    int shorterBits = optimalBits[index - shorter] + 3 + 8
                        + EliasGammaBits(shorter - 1);
                    if (shorterBits < bits)
                    {
                        bits = shorterBits;
                        length = shorter;
                    }
                }
                if (stateEnd[distance] != index || stateBits[distance] > bits)
                {
                    // A copy from the literal stream.
                    SetState(distance, bits, index, winNode[index - length]);
                    if (bits < bestMatch)
                    {
                        bestMatch = bits;
                        bestMatchIdx = distance;
                    }
                }
            }
        }

        /// <summary>
        /// Makes the parse just made the base for the ones to come: its
        /// checkpoints stand, its nodes are kept, and the next parse is
        /// compared against its dictionary. The tails every accepted parse
        /// leaves in the pool are collected with the rest, in place of the
        /// full re-parse that compacted them.
        /// </summary>
        internal void Accept()
        {
            Settle();
        }

        /// <summary>The parse just made becomes the base.</summary>
        private void Settle()
        {
            for (int m = sharedUpTo + 1; m < baseline.Length; m++)
            {
                (baseline[m], proposal[m]) = (proposal[m], baseline[m]);
                baseline[m].Valid = true;
            }
            baseForced = (bool[])forced.Clone();
            hasBase = true;
            poolTop = nodes;
        }

        private void TakeSnapshot(Snapshot into, int position)
        {
            Array.Copy(stateBits, into.StateBits, stateBits.Length);
            Array.Copy(stateEnd, into.StateEnd, stateEnd.Length);
            Array.Copy(statePred, into.StatePred, statePred.Length);
            Array.Copy(stateNode, into.StateNode, stateNode.Length);
            Array.Copy(litBits, into.LitBits, litBits.Length);
            Array.Copy(litEnd, into.LitEnd, litEnd.Length);
            Array.Copy(litNode, into.LitNode, litNode.Length);
            Array.Copy(matchLength, into.MatchLength, matchLength.Length);
            Array.Copy(stamp, into.Stamp, stamp.Length);
            Array.Copy(optimalBits, into.OptimalBits, position);
            Array.Copy(winNode, into.WinNode, position);
            Array.Copy(matchNodeSlot, into.MatchNodeSlot, position + 1);
            Array.Copy(leaf, 0, into.Leaves, 0, position + 1);
            Array.Copy(activePrev, into.ActivePrev, activePrevCount);
            Array.Copy(activeCur, into.ActiveCur, activeCurCount);
            Array.Copy(repable, into.Repable, repableCount);
            into.ActivePrevCount = activePrevCount;
            into.ActiveCurCount = activeCurCount;
            into.RepableCount = repableCount;
            into.Position = position;
            into.Valid = true;
        }

        private void Restore(Snapshot from)
        {
            nodes = poolTop;
            Array.Copy(from.StateBits, stateBits, stateBits.Length);
            Array.Copy(from.StateEnd, stateEnd, stateEnd.Length);
            Array.Copy(from.StatePred, statePred, statePred.Length);
            Array.Copy(from.StateNode, stateNode, stateNode.Length);
            Array.Copy(from.LitBits, litBits, litBits.Length);
            Array.Copy(from.LitEnd, litEnd, litEnd.Length);
            Array.Copy(from.LitNode, litNode, litNode.Length);
            Array.Copy(from.MatchLength, matchLength, matchLength.Length);
            Array.Copy(from.Stamp, stamp, stamp.Length);
            int position = sharedUpTo * checkpoint;
            Array.Copy(from.OptimalBits, optimalBits, position);
            Array.Copy(from.WinNode, winNode, position);
            Array.Copy(from.MatchNodeSlot, matchNodeSlot, position + 1);
            Array.Fill(leaf, long.MaxValue);
            Array.Copy(from.Leaves, 0, leaf, 0, position + 1);
            Array.Copy(from.ActivePrev, activePrev, from.ActivePrevCount);
            Array.Copy(from.ActiveCur, activeCur, from.ActiveCurCount);
            activePrevCount = from.ActivePrevCount;
            activeCurCount = from.ActiveCurCount;
            for (int r = 0; r < repableCount; r++)
            {
                inRepable[repable[r]] = false;
            }
            Array.Copy(from.Repable, repable, from.RepableCount);
            repableCount = from.RepableCount;
            for (int r = 0; r < repableCount; r++)
            {
                inRepable[repable[r]] = true;
            }
            bestLength[2] = 2;
        }

        private void Prepare()
        {
            Array.Fill(stateEnd, None);
            Array.Fill(litEnd, None);
            Array.Fill(litNode, -1);
            Array.Fill(stateNode, -1);
            Array.Fill(matchLength, 0);
            Array.Fill(stamp, -2);
            Array.Fill(leaf, long.MaxValue);
            bestLength[2] = 2;
            nodes = poolTop;
            // The fake block every chain hangs from: one unit back, ending
            // before the stream, costing -1 so the first flag is free.
            int root = NewNode(-1, Optimizer.InitialOffset, -1, -1);
            stateBits[Optimizer.InitialOffset] = -1;
            stateEnd[Optimizer.InitialOffset] = -1;
            stateNode[Optimizer.InitialOffset] = root;
            matchNodeSlot[0] = root;
            Update(0, (long)(literalBits - 1) << 32);
            activePrevCount = 0;
            activeCurCount = 0;
            for (int r = 0; r < repableCount; r++)
            {
                inRepable[repable[r]] = false;
            }
            repableCount = 0;
        }

        private int ExtendBestLength(int size, int target, int index)
        {
            if (size < target)
            {
                int bits = optimalBits[index - bestLength[size]] + EliasGammaBits(bestLength[size] - 1);
                do
                {
                    size++;
                    int shorterBits = optimalBits[index - size] + EliasGammaBits(size - 1);
                    if (shorterBits <= bits)
                    {
                        bestLength[size] = size;
                        bits = shorterBits;
                    }
                    else
                    {
                        bestLength[size] = bestLength[size - 1];
                    }
                }
                while (size < target);
            }
            return size;
        }

        private void SetState(int idx, int bits, int end, int pred)
        {
            stateBits[idx] = bits;
            stateEnd[idx] = end;
            statePred[idx] = pred;
            stateNode[idx] = -1;
            if (idx > window && !inRepable[idx])
            {
                inRepable[idx] = true;
                repable[repableCount++] = idx;
            }
        }

        /// <summary>The state's node, made when first needed.</summary>
        private int Node(int idx)
        {
            if (stateNode[idx] < 0)
            {
                stateNode[idx] = NewNode(stateEnd[idx], idx <= window ? idx : -idx,
                    statePred[idx], stateBits[idx]);
            }
            return stateNode[idx];
        }

        /// <summary>
        /// Writes one block of a chain. A literal run stands at an offset of
        /// zero and every match and copy at one that is not, so the rebuild
        /// reads the kind off the offset and the pool does not keep it.
        /// </summary>
        private int NewNode(int end, int offset, int pred, int bits)
        {
            if (nodes == nodeEnd.Length)
            {
                int grown = nodes * 2;
                Array.Resize(ref nodeEnd, grown);
                Array.Resize(ref nodeOffset, grown);
                Array.Resize(ref nodePred, grown);
                Array.Resize(ref nodeBits, grown);
            }
            nodeEnd[nodes] = end;
            nodeOffset[nodes] = offset;
            nodePred[nodes] = pred;
            nodeBits[nodes] = bits;
            return nodes++;
        }

        /// <summary>
        /// Keeps the nodes the parse can still reach, at index <paramref name="at"/>,
        /// and moves them to the front of the pool. The roots are the arrays
        /// that name a node: each state, its literal run and its
        /// predecessor, the winner and the best match at every position the
        /// parse has written, and the checkpoints of the base and of the
        /// parse under way. A node's predecessor is a node made before it
        /// and so stands below it, so one pass over the pool in order
        /// renumbers a node after the predecessor it names.
        /// </summary>
        /// <remarks>
        /// The base's nodes stand below <c>poolTop</c> and a restore drops
        /// what is above, so the pass counts what it keeps from below it and
        /// <c>poolTop</c> follows.
        /// </remarks>
        private void Collect(int at)
        {
            if (forward.Length < nodes)
            {
                forward = new int[nodes];
            }
            Array.Fill(forward, -1, 0, nodes);
            MarkNodes(stateNode, stateNode.Length);
            MarkRuns(matchLength, litNode);
            MarkPreds(stateEnd, statePred);
            MarkNodes(winNode, at);
            MarkNodes(matchNodeSlot, at + 1);
            foreach (Snapshot snapshot in baseline)
            {
                if (snapshot.Valid)
                {
                    MarkSnapshot(snapshot);
                }
            }
            for (int k = sharedUpTo + 1; k <= at / checkpoint && k < proposal.Length; k++)
            {
                MarkSnapshot(proposal[k]);
            }

            int kept = 0;
            int top = 0;
            for (int n = 0; n < nodes; n++)
            {
                if (forward[n] != PoolMarked)
                {
                    forward[n] = -1;
                    continue;
                }
                forward[n] = kept;
                int pred = nodePred[n];
                if (pred >= 0)
                {
                    pred = forward[pred];
                }
                nodeEnd[kept] = nodeEnd[n];
                nodeOffset[kept] = nodeOffset[n];
                nodePred[kept] = pred;
                nodeBits[kept] = nodeBits[n];
                kept++;
                if (n < poolTop)
                {
                    top = kept;
                }
            }

            MoveNodes(stateNode, stateNode.Length);
            MoveNodes(litNode, litNode.Length);
            MoveNodes(statePred, statePred.Length);
            MoveNodes(winNode, winNode.Length);
            MoveNodes(matchNodeSlot, matchNodeSlot.Length);
            foreach (Snapshot snapshot in baseline)
            {
                if (snapshot.Valid)
                {
                    MoveSnapshot(snapshot);
                }
            }
            for (int k = sharedUpTo + 1; k <= at / checkpoint && k < proposal.Length; k++)
            {
                MoveSnapshot(proposal[k]);
            }
            nodes = kept;
            poolTop = top;
            limit = Math.Max(PoolFloor, PoolGrowth * kept);
            if (nodeEnd.Length > 2 * limit)
            {
                // The pool grew for a parse that kept far more than this
                // one. The arrays come back to the bound, since what a run
                // needs at its widest is what it asks the machine for.
                Array.Resize(ref nodeEnd, limit);
                Array.Resize(ref nodeOffset, limit);
                Array.Resize(ref nodePred, limit);
                Array.Resize(ref nodeBits, limit);
                forward = Array.Empty<int>();
            }
        }

        /// <summary>Keeps a node and every node below it in its chain.</summary>
        private void Mark(int id)
        {
            for (int n = id; n >= 0 && n < nodes && forward[n] != PoolMarked; n = nodePred[n])
            {
                forward[n] = PoolMarked;
            }
        }

        /// <summary>Keeps every chain the first <paramref name="upTo"/> slots of an array name.</summary>
        private void MarkNodes(int[] ids, int upTo)
        {
            for (int i = 0; i < upTo; i++)
            {
                Mark(ids[i]);
            }
        }

        /// <summary>
        /// Keeps the literal run of every distance with a match run in
        /// progress. A distance between runs names the run of an older one,
        /// which the run it starts next replaces before anything reads it.
        /// </summary>
        private void MarkRuns(int[] lengths, int[] ids)
        {
            for (int idx = 0; idx < lengths.Length; idx++)
            {
                if (lengths[idx] > 0)
                {
                    Mark(ids[idx]);
                }
            }
        }

        /// <summary>
        /// Keeps the predecessor of every state that stands, which is a node
        /// where the state itself has none yet.
        /// </summary>
        private void MarkPreds(int[] ends, int[] preds)
        {
            for (int idx = 0; idx < ends.Length; idx++)
            {
                if (ends[idx] != None)
                {
                    Mark(preds[idx]);
                }
            }
        }

        private void MarkSnapshot(Snapshot s)
        {
            MarkNodes(s.StateNode, s.StateNode.Length);
            MarkRuns(s.MatchLength, s.LitNode);
            MarkPreds(s.StateEnd, s.StatePred);
            MarkNodes(s.WinNode, s.Position);
            MarkNodes(s.MatchNodeSlot, s.Position + 1);
        }

        /// <summary>
        /// Renumbers what a collection kept, and blanks what it dropped: a
        /// slot past what a parse has written names a node from an older
        /// parse, which is written again before it is read.
        /// </summary>
        private void MoveNodes(int[] ids, int upTo)
        {
            for (int i = 0; i < upTo; i++)
            {
                int id = ids[i];
                if (id >= 0)
                {
                    ids[i] = id < forward.Length ? forward[id] : -1;
                }
            }
        }

        private void MoveSnapshot(Snapshot s)
        {
            MoveNodes(s.StateNode, s.StateNode.Length);
            MoveNodes(s.LitNode, s.LitNode.Length);
            MoveNodes(s.StatePred, s.StatePred.Length);
            MoveNodes(s.WinNode, s.WinNode.Length);
            MoveNodes(s.MatchNodeSlot, s.MatchNodeSlot.Length);
        }

        private Block Rebuild(int last)
        {
            var order = new List<int>();
            for (int node = last; node >= 0; node = nodePred[node])
            {
                order.Add(node);
            }
            Block chain = new Block(-1, -1, Optimizer.InitialOffset, null);
            for (int i = order.Count - 2; i >= 0; i--)
            {
                int node = order[i];
                chain = new Block(nodeBits[node], nodeEnd[node], nodeOffset[node], chain);
            }
            return chain;
        }

        private void Update(int slot, long value)
        {
            leaf[slot] = value;
        }

        /// <summary>
        /// The least value of class <paramref name="k"/> over the slots
        /// <paramref name="lo"/> to <paramref name="hi"/>, the slot
        /// <paramref name="hi"/> entering the class as its window slides one
        /// on. A slot is written before any class reaches it, so a queue
        /// reads what a min-tree read.
        /// </summary>
        private long Least(int k, int lo, int hi)
        {
            if (leaf[hi] != long.MaxValue)
            {
                long value = leaf[hi];
                while (dqEnd[k] > dqLo[k] && leaf[dqAt[k][dqEnd[k] - 1]] >= value)
                {
                    dqEnd[k]--;
                }
                dqAt[k][dqEnd[k]++] = hi;
            }
            while (dqEnd[k] > dqLo[k] && dqAt[k][dqLo[k]] < lo)
            {
                dqLo[k]++;
            }
            if (dqEnd[k] == dqLo[k])
            {
                return long.MaxValue;
            }
            return leaf[dqAt[k][dqLo[k]]];
        }

        /// <summary>
        /// Fills every class from the values a parse begins with, which is
        /// what a checkpoint restored, for a parse that begins at
        /// <paramref name="start"/>.
        /// </summary>
        private void Queues(int start)
        {
            for (int k = 0; k < classes; k++)
            {
                dqLo[k] = 0;
                dqEnd[k] = 0;
                int hi = start - (1 << k);
                if (hi < 0)
                {
                    continue;
                }
                int lo = Math.Max(0, start - (2 << k) + 2);
                for (int slot = lo; slot <= hi; slot++)
                {
                    long value = leaf[slot];
                    if (value == long.MaxValue)
                    {
                        continue;
                    }
                    while (dqEnd[k] > dqLo[k] && leaf[dqAt[k][dqEnd[k] - 1]] >= value)
                    {
                        dqEnd[k]--;
                    }
                    dqAt[k][dqEnd[k]++] = slot;
                }
            }
        }
    }
}
