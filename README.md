# Chess — Java Swing, Minimax/Alpha-Beta AI

A complete chess game: Human vs AI and AI vs AI, full FIDE move rules, optional
chess clocks (or an untimed game), and a fixed-depth negamax (minimax + alpha-beta) engine with
quiescence search. Java 21, zero external dependencies.

## Playing

- **Move by click-click or drag & drop.** Click a piece (legal destinations
  are marked) then click a destination, or press on a piece and drag it onto
  its destination. Dropping a piece back on its own square keeps it selected;
  dropping it anywhere illegal snaps it back.
- **Premoves (chess.com style).** While the AI is thinking you can already
  enter your reply: pick one of your pieces and choose any square it could
  reach on an empty board (blockers are ignored — the position will change).
  The premove is highlighted in red with the piece ghosted on its destination
  and echoed in the status line. As soon as it is your turn the premove is
  played instantly if it is legal in the new position (promotions default to
  a queen), otherwise it is silently dropped. Any left-click on the board
  cancels a pending premove (and may start a new one); right-click cancels
  without selecting. One premove is held at a time.
- **Flip Board** rotates the view; **New Game** returns to the start screen.

## Build & run

```bash
./build.sh          # Linux / macOS / Git Bash   (Windows PowerShell: .\build.ps1)
java -jar chess.jar
```

The scripts compile into `out/` with `-Xlint:all,-serial` and package an
executable `chess.jar` (tests excluded). Without the scripts:

```bash
javac --release 21 -d out $(find src -name "*.java")
java -cp out app.Main
```

## Tests (plain main-class runners — no JUnit, so a bare JDK builds everything)

```bash
./test.sh           # or .\test.ps1 — runs the three runners below, headless
java -cp out test.PerftTest      # engine acceptance gate: 11 standard perft positions
java -cp out test.EngineTests    # 35 targeted rule / draw / search / zobrist tests
java -cp out test.UiTests        # Swing views driven by synthetic mouse events
```

`UiTests` needs no display: it dispatches `MouseEvent`s straight into
`BoardPanel` (click-click, drag & drop, premoves, flipped geometry, painting)
and clicks through `StartScreen` to check the emitted `GameConfig`.
CI (`.github/workflows/ci.yml`) runs build + all runners on every push.

`PerftTest` exercises the full generation + make/unmake pipeline against
published node counts (12.5M nodes, exact match required). `EngineTests`
covers each castling refusal condition individually, the en-passant
horizontal-pin trap, incremental Zobrist correctness over a random playout,
threefold repetition, the fifty-move rule, every insufficient-material case,
ply-adjusted mate scoring, and timeout adjudication (including the
flag-fall-vs-bare-king draw per FIDE 6.9). Both exit non-zero on failure.

## Architecture

```
engine/   Board, Move, MoveGenerator, Zobrist, Evaluator, Search, Perft
          Pure rules + search. Zero dependencies on game/ or ui/. No Swing.
game/     GameSession, ChessClock, GameConfig, GameResult
          Everything above single-position level: history, repetition table,
          draw adjudication, clocks, timeout verdicts. No Swing widgets.
ui/       MainFrame, StartScreen, GamePanel, BoardPanel, PieceRenderer
          Swing only. Never mutates engine state directly — all moves flow
          through GameSession.
app/      Main (EDT bootstrap)
test/     PerftTest, EngineTests, UiTests
```

### Threading model

The EDT owns all game state (`GameSession`, `ChessClock`). The AI runs in a
`SwingWorker` on a **private `Board.copy()`** — it never touches shared state.
Cancellation is cooperative (an `AtomicBoolean` polled every 2048 nodes);
timeouts and New Game set it and discard the result. A stale-worker guard in
`done()` plus the session's own move-legality validation make a late-finishing
worker harmless. The 100 ms UI timer is a *sampler* only: the clock is
`nanoTime`-anchored, so timer drift cannot corrupt timekeeping.

## Recorded design trade-offs

| Decision | Rationale | Cost accepted |
|---|---|---|
| Int piece encoding, not enums | Hot-loop indexing, packing into undo records | `colorOf(EMPTY)` is meaningless; callers must check emptiness |
| `Move` as a record, not packed int | Debuggability, free value equality (UI + tests rely on it) | Allocation per generated move — tolerable at this node budget |
| Pseudo-legal gen + make/unmake filter | Provably correct via perft; simplest scheme to verify | ~2x slower than a legal generator with pin detection |
| Negamax formulation | One code path, half the sign-bug surface; algebraically identical to min/max | Requires symmetric (side-relative) evaluation |
| Quiescence with in-check evasions | Kills the horizon effect; never stands pat while mated | Extra nodes at the horizon |
| MVV-LVA + promotions ordering only | Checks-first ordering costs more than it prunes at depth ≤ 5 | Slightly worse cutoff rate |
| Tapered king MG/EG tables | A hard phase switch causes eval discontinuities and move flip-flop | A few extra multiplies per eval |
| No mobility term | Requires movegen inside the hottest function; poor value at this depth | Weaker positional play |
| All draws auto-declared | No claim UI; unambiguous verdicts | Diverges from OTB claim rules (accepted spec) |
| Unicode glyph rendering behind `PieceRenderer` | No binary assets in a source deliverable; font scan + letter fallback | Glyph aesthetics vary by platform; image renderer is a drop-in later |
| Premove = one (from, to) pair, resolved against the legal list when the turn arrives | Never submits an illegal move; no engine changes; a stale premove simply evaporates | Single premove only (no queue); auto-queen on promotion; played synchronously in the AI's `done()`, so the AI's move and the premove appear in one frame |
| Drag state lives in `BoardPanel` alongside click selection | One `(from, targets)` model serves click-click, drag and premove; a drop is just a click on the target | Board is repainted on every drag event (fine at 8x8 with a single glyph) |
| Full prior Zobrist hash stored in `Undo` | Unconditional unmake correctness for 8 bytes/ply | None meaningful |

## Known limitations (deliberate v1 scope, in priority order for v2)

1. **No transposition table.** The Zobrist infrastructure already exists;
   a TT is the single biggest playing-strength upgrade available.
2. **No repetition detection inside the search tree.** The engine may shuffle
   into a threefold draw in a winning position (the session correctly declares
   it). Fix alongside the TT via a search-path key stack.
3. **No iterative deepening / time management.** Depth is fixed; the AI can
   lose on time at depth 5 in long games with short clocks.
4. **En-passant file is hashed whenever set**, even if no ep capture is
   actually legal — repetition detection is conservative (may detect one
   occurrence late; never declares falsely).
5. **Search is single-threaded** by design (correctness first).
