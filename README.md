# Chess — Java Swing, Minimax/Alpha-Beta AI

A complete chess game: Human vs AI, AI vs AI and online 1 v 1 over Java
sockets, full FIDE move rules, optional
chess clocks (or an untimed game), and a negamax (minimax + alpha-beta) engine
with iterative deepening, transposition table, quiescence search and a small
opening book. Java 21, zero external dependencies.

## Screenshots

<p align="center">
  <img src="docs/start.png" width="440" alt="Start screen: mode, side, time control and AI strength as segmented controls">
  <img src="docs/game.png" width="440" alt="A game against the AI: board, clocks, move list in SAN and the controls">
</p>
<p align="center">
  <img src="docs/start-online.png" width="440" alt="Start screen in Online 1 v 1 mode with name, server address and Host / Join buttons">
  <img src="docs/board-annotations.png" width="216" alt="Right-click marks and arrows on the board">
  <img src="docs/promotion.png" width="216" alt="The on-board promotion strip">
</p>

## Playing

- **Move by click-click or drag & drop.** Click a piece (legal destinations
  are marked) then click a destination, or press on a piece and drag it onto
  its destination. Dropping a piece back on its own square keeps it selected;
  dropping it anywhere illegal snaps it back.
- **Premoves (chess.com style).** While the AI is thinking you can already
  enter your reply: pick one of your pieces and choose any square it could
  reach on an empty board (blockers are ignored — the position will change).
  Premoves are highlighted in red, the pieces are painted where they will
  stand, and the queue is echoed in the status line. Several can be queued:
  each later one is entered on the board as it will look after the earlier
  ones. As soon as it is your turn the first premove is played if it is
  legal in the new position (promotions default to a queen); if it is not,
  the whole queue is dropped. While waiting, clicking anything that is not
  an own piece or a destination clears the queue; right-click clears it too.
- **Right-click** highlights a square, **right-drag** draws an arrow (again
  to remove); any left-click or move clears them.
- **Promotion** opens a strip of four pieces on the board starting at the
  promotion square; click one to promote, click anywhere else to cancel.
- **Undo** (Ctrl+Z, Human vs AI): takes back your last move and the AI's
  reply — also while the AI is thinking, or after the game has ended.
- **Move list** in standard algebraic notation beside the board, and
  **Export PGN…** to save the game (seven-tag roster + move text).
- **Sounds** for move, capture, check and game end — synthesised at
  start-up (no audio files), switchable with the Sound checkbox, silent
  when no audio device exists.
- **Save… / Resume a saved game…** write and reload a small text file
  (`.chess`: settings, clocks, moves in long algebraic notation, replayed
  and validated on load).
- **Resign** and **Offer draw** (Human vs AI): the AI accepts a draw when
  its last search rated its own position no better than about +0.30 and
  the game has left the opening; otherwise the offer is declined.
- **Game over** dialog offers a rematch (colours swapped), a new game, or
  staying on the final position to review it.
- Window placement, the sound switch and the last start-screen settings
  are remembered between runs (`java.util.prefs`).
- **Flip Board** rotates the view; **New Game** returns to the start screen.

## Online 1 v 1 (two PCs, Java sockets)

Pick **Online 1 v 1** on the start screen, type a name and a `host:port`.

- **Host game** opens that port on your PC (an embedded server), shows the
  addresses to share, and waits. The other player picks **Join game** with
  your `ip:port`. Only the port matters for the host; the time control is
  the host's (first arrival's).
- **Standalone server** — both players join the same machine, which can be
  a third one: `java -cp chess.jar app.ServerMain 5000`. It pairs clients
  two by two in arrival order, so several games can run at once.
- The server is the referee: it keeps its own game per room and only relays
  a move that is legal and from the side to move. Both clients still
  adjudicate mate / stalemate / draws locally from the identical move list.
- Each side runs its own clock; a player reports their **own** flag fall.
  Resign, draw offers (the opponent decides), premoves and rematch (colours
  swapped, both must accept) work online. A disconnect forfeits the game
  for the player who dropped; losing your own connection aborts it.
- Protocol: one text line per message (`HELLO`, `START`, `MOVE e2e4`,
  `RESIGN`, `DRAW_OFFER/ACCEPT/DECLINE`, `TIMEOUT`, `REMATCH`,
  `OPPONENT_LEFT`, `ERROR`, `PING/PONG`) — see `net/Protocol.java`.
  Player names are reduced to single safe tokens.
- Firewalls: the host must allow inbound TCP on the chosen port; across the
  Internet the host's router needs a port forward. Names travel in clear —
  there is no encryption or authentication (LAN / friends use).

## Build & run

```bash
./build.sh          # Linux / macOS / Git Bash   (Windows PowerShell: .\build.ps1)
java -jar chess.jar                          # the game
java -cp chess.jar app.ServerMain 5000       # optional: standalone online server
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
java -cp out test.EngineTests    # 77 targeted rule / draw / search / notation / session tests
java -cp out test.UiTests        # 53 checks: Swing views driven by synthetic mouse events
java -cp out test.NetTests       # server + two clients over loopback: pairing, relay, rematch, disconnects
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
engine/   Board, Move, MoveGenerator, Zobrist, Evaluator, Search, TranspositionTable,
          OpeningBook, Perft
          Pure rules + search. Zero dependencies on game/ or ui/. No Swing.
game/     GameSession, ChessClock, GameConfig, GameResult, Notation (SAN / PGN), SavedGame
          Everything above single-position level: history, repetition table,
          draw adjudication, clocks, timeout verdicts. No Swing widgets.
net/      Protocol, ChessServer, ChessClient
          Line-based socket protocol; the server referees with its own
          GameSession per room, the client delivers messages on the EDT.
ui/       MainFrame, StartScreen, OnlineLobbyPanel, GamePanel, BoardPanel,
          PieceRenderer, Sounds, Prefs
          Swing only. Never mutates engine state directly — all moves flow
          through GameSession. MainFrame owns the online connection.
app/      Main (EDT bootstrap), ServerMain (standalone relay server)
test/     PerftTest, EngineTests, UiTests, NetTests
```

### Threading model

The EDT owns all game state (`GameSession`, `ChessClock`). The AI runs in a
`SwingWorker` on a **private `Board.copy()`** — it never touches shared state.
Cancellation is cooperative (an `AtomicBoolean` polled every 2048 nodes);
timeouts and New Game set it and discard the result. A stale-worker guard in
`done()` plus the session's own move-legality validation make a late-finishing
worker harmless. The 100 ms UI timer is a *sampler* only: the clock is
`nanoTime`-anchored, so timer drift cannot corrupt timekeeping.

Online games keep that model intact: `ChessClient` reads the socket on its
own thread but hands every message to the EDT (`invokeLater`, queued until a
listener is installed), so `GamePanel` mutates the session from the EDT
exactly as for a clicked move. `ChessServer` runs one accept thread and one
reader thread per client; a room's state is guarded by the room's monitor and
its referee `GameSession` is created without EDT confinement. When the server
is embedded in the GUI ("Host game") all its threads are daemons, so closing
the window ends it; `ServerMain` uses non-daemon threads and runs until
killed.

## Recorded design trade-offs

| Decision | Rationale | Cost accepted |
|---|---|---|
| Int piece encoding, not enums | Hot-loop indexing, packing into undo records | `colorOf(EMPTY)` is meaningless; callers must check emptiness |
| `Move` as a record, not packed int | Debuggability, free value equality (UI + tests rely on it) | Allocation per generated move — tolerable at this node budget |
| Pseudo-legal gen + make/unmake filter | Provably correct via perft; simplest scheme to verify | ~2x slower than a legal generator with pin detection |
| Negamax formulation | One code path, half the sign-bug surface; algebraically identical to min/max | Requires symmetric (side-relative) evaluation |
| Quiescence with in-check evasions | Kills the horizon effect; never stands pat while mated | Extra nodes at the horizon |
| Ordering: TT move, promotions, MVV-LVA captures, killers, history — no checks-first | Checks-first ordering costs more than it prunes at these depths; the check extension covers the tactical need | Slightly worse cutoff rate in check-heavy positions |
| Tapered king MG/EG tables | A hard phase switch causes eval discontinuities and move flip-flop | A few extra multiplies per eval |
| No mobility term | Requires movegen inside the hottest function; poor value at this depth | Weaker positional play |
| All draws auto-declared | No claim UI; unambiguous verdicts | Diverges from OTB claim rules (accepted spec) |
| Unicode glyph rendering behind `PieceRenderer` | No binary assets in a source deliverable; font scan + letter fallback | Glyph aesthetics vary by platform; image renderer is a drop-in later |
| Premoves = queue of (from, to) pairs, each resolved against the legal list when its turn arrives | Never submits an illegal move; no engine changes; a stale premove drops the queue | Later premoves are entered on a naively projected board (no legality); auto-queen on promotion; played by a one-shot timer after the AI move's slide |
| Move animation paints the post-move board with the moving piece interpolated | No intermediate game state, nothing to roll back; a takeback mid-slide just draws the restored board | Captured piece vanishes at the start of the slide rather than on arrival |
| Online: the server referees with its own `GameSession` and relays only legal, in-turn moves | Two honest clients can never diverge; a tampered client cannot cheat with nonsense; the engine's rules are reused unchanged | One extra move generation per move on the server; a desync (should never happen) aborts the game rather than guessing |
| Online: each side runs its own clock and reports its *own* flag fall | No clock authority or latency protocol needed; an honest client cannot gain by lying about itself | Network latency is not compensated; a silent opponent is only dropped by the 30 s idle timeout |
| Online: one plain-text line per message, names reduced to safe tokens | Debuggable with `telnet`/`nc`, trivial to parse, no framing bugs | No encryption or authentication — LAN / friends use only |
| Online: colours by order of HELLO processing, time control from the first arrival | No negotiation round-trip; both clients simply obey `START` | The host cannot choose a colour; a joiner's time setting is ignored |
| Drag state lives in `BoardPanel` alongside click selection | One `(from, targets)` model serves click-click, drag and premove; a drop is just a click on the target | Board is repainted on every drag event (fine at 8x8 with a single glyph) |
| Full prior Zobrist hash stored in `Undo` | Unconditional unmake correctness for 8 bytes/ply | None meaningful |

### Search (v2)

- **Iterative deepening with a time budget.** The "AI strength" setting is
  the maximum depth; with a clock the engine spends about 1/30 of its
  remaining time per move (100 ms – 8 s) and returns the last completed
  iteration when the budget runs out, so it no longer loses on time.
  Untimed games search to the fixed depth with a 15 s safety cap.
- **Transposition table** (1M entries, Zobrist-keyed, age-aware
  replacement) — exact/bound cutoffs plus the previous iteration's best move
  first. One `Search` (and table) lives for the whole game; `findBest` is
  synchronized and works on a private board copy.
- **Repetition detection in the tree.** The game's position keys plus the
  search path form one key stack; a position seen before scores as a draw.
  A winning engine no longer drifts into threefold, a lost one seeks the
  perpetual (both covered by tests).
- Check extension, killer moves, history heuristic, principal variation
  reported to the UI (eval, depth, nodes, time in the status line).
- **Opening book** (`OpeningBook`): ~30 mainstream lines expanded into a
  Zobrist-keyed map at class load (every entry verified legal), probed
  before searching; a random book move gives opening variety at zero cost.

## Known limitations

1. **En-passant file is hashed whenever set**, even if no ep capture is
   actually legal — repetition detection is conservative (may detect one
   occurrence late; never declares falsely).
2. **Search is single-threaded** by design (correctness first).
3. **No null-move pruning / aspiration windows** — deliberately left out
   until an engine-vs-engine harness exists to measure them.
4. **Online play is unencrypted and unauthenticated**; clocks are not
   latency-compensated; there is no takeback, chat or reconnection to a
   game in progress (a dropped connection forfeits or aborts the game).
