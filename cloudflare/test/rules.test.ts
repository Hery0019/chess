import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Board, Game, lan, perft, RESULTS, BLACK, WHITE } from '../src/rules.ts';

/** Same positions, depths and published node counts as test.PerftTest. */
const PERFT = [
  ['startpos', 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1', 5, 4_865_609],
  ['kiwipete', 'r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1', 4, 4_085_603],
  ['pos3 (ep/pins)', '8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1', 5, 674_624],
  ['pos4 (promos)', 'r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1', 4, 422_333],
  ['pos5', 'rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8', 4, 2_103_487],
  ['pos6', 'r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10', 3, 89_890],
] as const;

for (const [name, fen, depth, expected] of PERFT) {
  test(`perft ${name} d${depth}`, () => {
    const b = Board.fromFen(fen);
    const before = b.key();
    assert.equal(perft(b, depth), expected);
    assert.equal(b.key(), before, 'make/unmake restores the position');
  });
}

/** Plays long-algebraic moves, each of which must be legal. */
function play(game: Game, ...moves: string[]): void {
  for (const s of moves) {
    const m = game.legalMoves().find(legal => lan(legal) === s);
    assert.ok(m, `${s} is legal`);
    game.applyMove(m);
  }
}

test('fool\'s mate is checkmate for Black', () => {
  const g = new Game();
  play(g, 'f2f3', 'e7e5', 'g2g4', 'd8h4');
  assert.equal(g.result, RESULTS.BLACK_WINS_MATE);
  assert.equal(g.result.pgn, '0-1');
});

test('stalemate', () => {
  const g = new Game(Board.fromFen('7k/5Q2/6K1/8/8/8/8/8 b - - 0 1'));
  assert.equal(g.result, RESULTS.DRAW_STALEMATE);
});

test('threefold repetition, counting the initial position', () => {
  const g = new Game();
  play(g, 'g1f3', 'g8f6', 'f3g1', 'f6g8', 'g1f3', 'g8f6', 'f3g1');
  assert.equal(g.isOver, false);
  play(g, 'f6g8');
  assert.equal(g.result, RESULTS.DRAW_REPETITION);
});

test('a double push changes the position (en-passant square), as in the engine key', () => {
  const g = new Game();
  // After 1.e4 the e3 square is set; the same placement later without it is a different position.
  play(g, 'e2e4', 'g8f6', 'g1f3', 'f6g8', 'f3g1', 'g8f6', 'g1f3', 'f6g8', 'f3g1');
  assert.equal(g.isOver, false);
});

test('fifty-move rule at halfmove clock 100', () => {
  const g = new Game(Board.fromFen('4k3/8/8/8/8/8/8/R3K3 w - - 99 80'));
  play(g, 'a1a2');
  assert.equal(g.result, RESULTS.DRAW_FIFTY_MOVES);
});

test('dead positions: K+B v K+B same colour yes, opposite colours and two knights no', () => {
  assert.equal(new Game(Board.fromFen('4k3/8/8/8/8/8/8/4K3 w - - 0 1')).result, RESULTS.DRAW_INSUFFICIENT_MATERIAL);
  assert.equal(new Game(Board.fromFen('4k3/8/8/8/8/8/8/2B1K3 w - - 0 1')).result, RESULTS.DRAW_INSUFFICIENT_MATERIAL);
  assert.equal(new Game(Board.fromFen('2b1k3/8/8/8/8/8/8/3BK3 w - - 0 1')).result, RESULTS.DRAW_INSUFFICIENT_MATERIAL);
  assert.equal(new Game(Board.fromFen('3bk3/8/8/8/8/8/8/3BK3 w - - 0 1')).isOver, false);
  assert.equal(new Game(Board.fromFen('4k3/8/8/8/8/8/8/1NN1K3 w - - 0 1')).isOver, false);
});

test('a capture into a dead position ends the game', () => {
  const g = new Game(Board.fromFen('4k3/8/8/8/8/8/3r4/4K3 w - - 0 1'));
  play(g, 'e1d2');
  assert.equal(g.result, RESULTS.DRAW_INSUFFICIENT_MATERIAL);
});

test('timeout: loss, unless the opponent has a bare king', () => {
  const g = new Game();
  g.timeout(WHITE);
  assert.equal(g.result, RESULTS.BLACK_WINS_TIMEOUT);
  const bare = new Game(Board.fromFen('4k3/8/8/8/8/8/PPPP4/4K3 w - - 0 1'));
  bare.timeout(WHITE);
  assert.equal(bare.result, RESULTS.DRAW_TIMEOUT_VS_BARE_KING);
});

test('resign and agreed draw; the first verdict stands', () => {
  const g = new Game();
  g.resign(BLACK);
  assert.equal(g.result, RESULTS.WHITE_WINS_RESIGNATION);
  g.agreeDraw();
  assert.equal(g.result, RESULTS.WHITE_WINS_RESIGNATION);
});

test('castling, en passant and promotion in long algebraic notation', () => {
  const castle = new Game(Board.fromFen('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1'));
  const moves = castle.legalMoves().map(lan);
  assert.ok(moves.includes('e1g1') && moves.includes('e1c1'));
  const ep = new Game(Board.fromFen('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1'));
  assert.ok(ep.legalMoves().map(lan).includes('e5d6'));
  const promo = new Game(Board.fromFen('4k3/P7/8/8/8/8/8/4K3 w - - 0 1'));
  assert.deepEqual(promo.legalMoves().map(lan).filter(s => s.startsWith('a7')), ['a7a8q', 'a7a8r', 'a7a8b', 'a7a8n']);
});
