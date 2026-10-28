/**
 * Chess rules for the online referee: a port of engine.Board,
 * engine.MoveGenerator and the adjudication of game.GameSession — only
 * what a referee needs (legal moves, make/unmake, mate / stalemate /
 * draws), none of the search. Same encoding as the Java engine
 * ({@code piece = type | color << 3}, a1 = 0, h8 = 63) and the same
 * strategy: pseudo-legal generation filtered by make / inCheck / unmake,
 * validated by perft (test/rules.test.ts).
 */

export const EMPTY = 0, PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
export const WHITE = 0, BLACK = 1;

export const make = (type: number, color: number): number => type | (color << 3);
export const typeOf = (piece: number): number => piece & 7;
/** Only valid for non-empty pieces. */
export const colorOf = (piece: number): number => (piece >>> 3) & 1;

export const CASTLE = 1, EN_PASSANT = 2, DOUBLE_PUSH = 4;

export interface Move {
  readonly from: number;
  readonly to: number;
  /** The full piece code that moves. */
  readonly piece: number;
  /** Full piece code captured, or EMPTY; for en passant the enemy pawn. */
  readonly captured: number;
  /** Piece TYPE promoted to, or 0. */
  readonly promotion: number;
  readonly flags: number;
}

/** What {@link Board.makeMove} needs to restore on unmake. */
export interface Undo {
  readonly castlingRights: number;
  readonly epSquare: number;
  readonly halfmoveClock: number;
}

export const squareName = (sq: number): string =>
  String.fromCharCode(97 + (sq & 7)) + String.fromCharCode(49 + (sq >>> 3));

/** Long algebraic notation, e.g. {@code e2e4}, {@code e7e8q}. */
export const lan = (m: Move): string =>
  squareName(m.from) + squareName(m.to) + (m.promotion !== 0 ? '.pnbrqk'[m.promotion] : '');

export const WK_CASTLE = 1, WQ_CASTLE = 2, BK_CASTLE = 4, BQ_CASTLE = 8;

/** rights &= CASTLE_MASK[from] & CASTLE_MASK[to] on every move. */
const CASTLE_MASK = new Array<number>(64).fill(15);
CASTLE_MASK[0] = 15 & ~WQ_CASTLE;                  // a1
CASTLE_MASK[4] = 15 & ~(WK_CASTLE | WQ_CASTLE);    // e1
CASTLE_MASK[7] = 15 & ~WK_CASTLE;                  // h1
CASTLE_MASK[56] = 15 & ~BQ_CASTLE;                 // a8
CASTLE_MASK[60] = 15 & ~(BK_CASTLE | BQ_CASTLE);   // e8
CASTLE_MASK[63] = 15 & ~BK_CASTLE;                 // h8

const KNIGHT_D = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
const KING_D = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
const BISHOP_DIRS = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
const ROOK_DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];

function targets(sq: number, deltas: number[][]): number[] {
  const r = sq >>> 3, f = sq & 7, out: number[] = [];
  for (const [dr, df] of deltas) {
    const nr = r + dr, nf = f + df;
    if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) out.push(nr * 8 + nf);
  }
  return out;
}
const KNIGHT_TARGETS = Array.from({ length: 64 }, (_, sq) => targets(sq, KNIGHT_D));
const KING_TARGETS = Array.from({ length: 64 }, (_, sq) => targets(sq, KING_D));

/** Mutable position, 8x8 mailbox. */
export class Board {
  readonly squares = new Array<number>(64).fill(EMPTY);
  sideToMove = WHITE;
  castlingRights = 0;
  /** Square BEHIND the double-pushed pawn, or -1. */
  epSquare = -1;
  halfmoveClock = 0;
  fullmoveNumber = 1;
  readonly kingSq = [0, 0];

  static startPosition(): Board {
    return Board.fromFen('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1');
  }

  /** Minimal FEN parser — trusted input (tests and internal use only). */
  static fromFen(fen: string): Board {
    const b = new Board();
    const parts = fen.trim().split(/\s+/);
    if (parts.length < 4) throw new Error('Bad FEN: ' + fen);
    let sq = 56;   // FEN starts at a8
    for (const c of parts[0]) {
      if (c === '/') { sq -= 16; continue; }
      if (c >= '1' && c <= '8') { sq += Number(c); continue; }
      const type = 'pnbrqk'.indexOf(c.toLowerCase()) + 1;
      if (type === 0) throw new Error('Bad FEN piece: ' + c);
      const color = c === c.toUpperCase() ? WHITE : BLACK;
      b.squares[sq] = make(type, color);
      if (type === KING) b.kingSq[color] = sq;
      sq++;
    }
    b.sideToMove = parts[1] === 'w' ? WHITE : BLACK;
    if (parts[2] !== '-') {
      for (const c of parts[2]) b.castlingRights |= ({ K: WK_CASTLE, Q: WQ_CASTLE, k: BK_CASTLE, q: BQ_CASTLE } as Record<string, number>)[c] ?? 0;
    }
    if (parts[3] !== '-') b.epSquare = (parts[3].charCodeAt(0) - 97) + 8 * (parts[3].charCodeAt(1) - 49);
    b.halfmoveClock = parts.length > 4 ? Number(parts[4]) : 0;
    b.fullmoveNumber = parts.length > 5 ? Number(parts[5]) : 1;
    return b;
  }

  /**
   * Identity of the position for repetition counting: what the Java
   * Zobrist key covers — placement, side to move, castling rights and the
   * en-passant square (set after every double push, as in the engine).
   */
  key(): string {
    let s = '';
    for (const p of this.squares) s += '.PNBRQK..pnbrqk'[p];
    return s + ' ' + this.sideToMove + ' ' + this.castlingRights + ' ' + this.epSquare;
  }

  makeMove(m: Move): Undo {
    const u: Undo = { castlingRights: this.castlingRights, epSquare: this.epSquare, halfmoveClock: this.halfmoveClock };
    const s = this.squares, us = this.sideToMove, type = typeOf(m.piece);
    this.epSquare = -1;
    if (m.flags & EN_PASSANT) s[m.to + (us === WHITE ? -8 : 8)] = EMPTY;
    s[m.from] = EMPTY;
    s[m.to] = m.promotion !== 0 ? make(m.promotion, us) : m.piece;
    if (type === KING) this.kingSq[us] = m.to;
    if (m.flags & CASTLE) {   // rook hop; rook squares are fixed by geometry
      const [rookFrom, rookTo] = m.to > m.from ? [m.from + 3, m.from + 1] : [m.from - 4, m.from - 1];
      s[rookTo] = s[rookFrom];
      s[rookFrom] = EMPTY;
    }
    if (m.flags & DOUBLE_PUSH) this.epSquare = (m.from + m.to) >>> 1;
    this.castlingRights &= CASTLE_MASK[m.from] & CASTLE_MASK[m.to];
    this.halfmoveClock = type === PAWN || m.captured !== EMPTY ? 0 : this.halfmoveClock + 1;
    if (us === BLACK) this.fullmoveNumber++;
    this.sideToMove ^= 1;
    return u;
  }

  unmakeMove(m: Move, u: Undo): void {
    this.sideToMove ^= 1;   // back to the mover
    const s = this.squares, us = this.sideToMove;
    if (us === BLACK) this.fullmoveNumber--;
    s[m.from] = m.piece;    // undoes promotion implicitly
    s[m.to] = EMPTY;
    if (m.flags & EN_PASSANT) s[m.to + (us === WHITE ? -8 : 8)] = m.captured;
    else if (m.captured !== EMPTY) s[m.to] = m.captured;
    if (typeOf(m.piece) === KING) this.kingSq[us] = m.from;
    if (m.flags & CASTLE) {
      const [rookFrom, rookTo] = m.to > m.from ? [m.from + 3, m.from + 1] : [m.from - 4, m.from - 1];
      s[rookFrom] = s[rookTo];
      s[rookTo] = EMPTY;
    }
    this.castlingRights = u.castlingRights;
    this.epSquare = u.epSquare;
    this.halfmoveClock = u.halfmoveClock;
  }

  inCheck(color: number): boolean {
    return this.isSquareAttacked(this.kingSq[color], color ^ 1);
  }

  /** Is {@code sq} attacked by any piece of {@code byColor}? */
  isSquareAttacked(sq: number, byColor: number): boolean {
    const s = this.squares, file = sq & 7;
    // Pawns. A white pawn on p attacks p+7 (file-1) and p+9 (file+1); invert.
    if (byColor === WHITE) {
      if (file > 0 && sq >= 9 && s[sq - 9] === make(PAWN, WHITE)) return true;
      if (file < 7 && sq >= 7 && s[sq - 7] === make(PAWN, WHITE)) return true;
    } else {
      if (file > 0 && sq <= 56 && s[sq + 7] === make(PAWN, BLACK)) return true;
      if (file < 7 && sq <= 54 && s[sq + 9] === make(PAWN, BLACK)) return true;
    }
    const knight = make(KNIGHT, byColor), king = make(KING, byColor);
    for (const t of KNIGHT_TARGETS[sq]) if (s[t] === knight) return true;
    for (const t of KING_TARGETS[sq]) if (s[t] === king) return true;
    return this.rayAttacked(sq, byColor, BISHOP_DIRS, BISHOP) || this.rayAttacked(sq, byColor, ROOK_DIRS, ROOK);
  }

  private rayAttacked(sq: number, byColor: number, dirs: number[][], sliderType: number): boolean {
    const r0 = sq >>> 3, f0 = sq & 7;
    for (const [dr, df] of dirs) {
      for (let r = r0 + dr, f = f0 + df; r >= 0 && r < 8 && f >= 0 && f < 8; r += dr, f += df) {
        const p = this.squares[r * 8 + f];
        if (p !== EMPTY) {
          if (colorOf(p) === byColor && (typeOf(p) === sliderType || typeOf(p) === QUEEN)) return true;
          break;
        }
      }
    }
    return false;
  }
}

// ---- move generation ----

const mv = (from: number, to: number, piece: number, captured: number, promotion: number, flags: number): Move =>
  ({ from, to, piece, captured, promotion, flags });

/** All strictly legal moves for the side to move. */
export function generateLegal(b: Board): Move[] {
  const pseudo: Move[] = [];
  const us = b.sideToMove, them = us ^ 1;
  for (let sq = 0; sq < 64; sq++) {
    const p = b.squares[sq];
    if (p === EMPTY || colorOf(p) !== us) continue;
    switch (typeOf(p)) {
      case PAWN: pawnMoves(b, sq, p, us, them, pseudo); break;
      case KNIGHT: leaperMoves(b, sq, p, us, KNIGHT_TARGETS[sq], pseudo); break;
      case KING: leaperMoves(b, sq, p, us, KING_TARGETS[sq], pseudo); break;
      case BISHOP: sliderMoves(b, sq, p, us, BISHOP_DIRS, pseudo); break;
      case ROOK: sliderMoves(b, sq, p, us, ROOK_DIRS, pseudo); break;
      case QUEEN:
        sliderMoves(b, sq, p, us, BISHOP_DIRS, pseudo);
        sliderMoves(b, sq, p, us, ROOK_DIRS, pseudo);
        break;
      default: throw new Error('corrupt board');
    }
  }
  addCastlingMoves(b, pseudo);
  return pseudo.filter(m => {
    const u = b.makeMove(m);
    const ok = !b.inCheck(us);
    b.unmakeMove(m, u);
    return ok;
  });
}

function pawnMoves(b: Board, sq: number, piece: number, us: number, them: number, out: Move[]): void {
  const dir = us === WHITE ? 8 : -8, startRank = us === WHITE ? 1 : 6, promoRank = us === WHITE ? 7 : 0;
  const rank = sq >>> 3, file = sq & 7, fwd = sq + dir;
  if (b.squares[fwd] === EMPTY) {
    if ((fwd >>> 3) === promoRank) {
      addPromotions(out, sq, fwd, piece, EMPTY);
    } else {
      out.push(mv(sq, fwd, piece, EMPTY, 0, 0));
      if (rank === startRank && b.squares[sq + 2 * dir] === EMPTY) out.push(mv(sq, sq + 2 * dir, piece, EMPTY, 0, DOUBLE_PUSH));
    }
  }
  for (const dc of [-1, 1]) {
    const nf = file + dc;
    if (nf < 0 || nf > 7) continue;
    const t = sq + dir + dc, target = b.squares[t];
    if (target !== EMPTY && colorOf(target) === them) {
      if ((t >>> 3) === promoRank) addPromotions(out, sq, t, piece, target);
      else out.push(mv(sq, t, piece, target, 0, 0));
    } else if (t === b.epSquare) {
      out.push(mv(sq, t, piece, make(PAWN, them), 0, EN_PASSANT));
    }
  }
}

function addPromotions(out: Move[], from: number, to: number, piece: number, captured: number): void {
  for (const type of [QUEEN, ROOK, BISHOP, KNIGHT]) out.push(mv(from, to, piece, captured, type, 0));
}

function leaperMoves(b: Board, sq: number, piece: number, us: number, targets: number[], out: Move[]): void {
  for (const t of targets) {
    const target = b.squares[t];
    if (target === EMPTY || colorOf(target) !== us) out.push(mv(sq, t, piece, target, 0, 0));
  }
}

function sliderMoves(b: Board, sq: number, piece: number, us: number, dirs: number[][], out: Move[]): void {
  const r0 = sq >>> 3, f0 = sq & 7;
  for (const [dr, df] of dirs) {
    for (let r = r0 + dr, f = f0 + df; r >= 0 && r < 8 && f >= 0 && f < 8; r += dr, f += df) {
      const t = r * 8 + f, target = b.squares[t];
      if (target === EMPTY) {
        out.push(mv(sq, t, piece, EMPTY, 0, 0));
      } else {
        if (colorOf(target) !== us) out.push(mv(sq, t, piece, target, 0, 0));
        break;
      }
    }
  }
}

/**
 * Castling: rights, empty squares between king and rook, king not in
 * check, pass-through squares not attacked. "King ends up in check" is
 * left to the generic legality filter; b1/b8 being attacked does not
 * forbid queenside castling.
 */
function addCastlingMoves(b: Board, out: Move[]): void {
  const us = b.sideToMove, them = us ^ 1, kingFrom = us === WHITE ? 4 : 60, king = make(KING, us), s = b.squares;
  if (b.kingSq[us] !== kingFrom) return;
  const canK = (b.castlingRights & (us === WHITE ? WK_CASTLE : BK_CASTLE)) !== 0;
  const canQ = (b.castlingRights & (us === WHITE ? WQ_CASTLE : BQ_CASTLE)) !== 0;
  if ((!canK && !canQ) || b.inCheck(us)) return;
  if (canK && s[kingFrom + 1] === EMPTY && s[kingFrom + 2] === EMPTY
      && !b.isSquareAttacked(kingFrom + 1, them) && !b.isSquareAttacked(kingFrom + 2, them)) {
    out.push(mv(kingFrom, kingFrom + 2, king, EMPTY, 0, CASTLE));
  }
  if (canQ && s[kingFrom - 1] === EMPTY && s[kingFrom - 2] === EMPTY && s[kingFrom - 3] === EMPTY
      && !b.isSquareAttacked(kingFrom - 1, them) && !b.isSquareAttacked(kingFrom - 2, them)) {
    out.push(mv(kingFrom, kingFrom - 2, king, EMPTY, 0, CASTLE));
  }
}

/** Leaf count of the legal move tree — the acceptance test of the whole pipeline. */
export function perft(b: Board, depth: number): number {
  if (depth === 0) return 1;
  const moves = generateLegal(b);
  if (depth === 1) return moves.length;
  let nodes = 0;
  for (const m of moves) {
    const u = b.makeMove(m);
    nodes += perft(b, depth - 1);
    b.unmakeMove(m, u);
  }
  return nodes;
}

// ---- game state and adjudication (game.GameSession / game.GameResult) ----

export interface Result {
  /** PGN token: 1-0, 0-1, 1/2-1/2, or * while ongoing. */
  readonly pgn: string;
  readonly message: string;
}

export const RESULTS = {
  ONGOING: { pgn: '*', message: '' },
  WHITE_WINS_MATE: { pgn: '1-0', message: 'Checkmate — White wins' },
  BLACK_WINS_MATE: { pgn: '0-1', message: 'Checkmate — Black wins' },
  WHITE_WINS_TIMEOUT: { pgn: '1-0', message: 'Black lost on time — White wins' },
  BLACK_WINS_TIMEOUT: { pgn: '0-1', message: 'White lost on time — Black wins' },
  WHITE_WINS_RESIGNATION: { pgn: '1-0', message: 'Black resigned — White wins' },
  BLACK_WINS_RESIGNATION: { pgn: '0-1', message: 'White resigned — Black wins' },
  DRAW_AGREED: { pgn: '1/2-1/2', message: 'Draw — by agreement' },
  DRAW_STALEMATE: { pgn: '1/2-1/2', message: 'Draw — stalemate' },
  DRAW_REPETITION: { pgn: '1/2-1/2', message: 'Draw — threefold repetition' },
  DRAW_FIFTY_MOVES: { pgn: '1/2-1/2', message: 'Draw — fifty-move rule' },
  DRAW_INSUFFICIENT_MATERIAL: { pgn: '1/2-1/2', message: 'Draw — insufficient material' },
  DRAW_TIMEOUT_VS_BARE_KING: { pgn: '1/2-1/2', message: 'Draw — flag fell, but opponent cannot mate' },
} as const satisfies Record<string, Result>;

/**
 * One game: live board, repetition table and terminal result. Draws are
 * auto-declared the moment their condition holds, as in the Java app:
 * threefold repetition, halfmove clock 100, dead positions.
 */
export class Game {
  readonly board: Board;
  result: Result = RESULTS.ONGOING;
  private readonly repetitions = new Map<string, number>();
  private legal: Move[] | null = null;

  constructor(initial: Board = Board.startPosition()) {
    this.board = initial;
    this.repetitions.set(initial.key(), 1);
    this.adjudicate();   // the initial position may already be terminal
  }

  get sideToMove(): number { return this.board.sideToMove; }
  get isOver(): boolean { return this.result !== RESULTS.ONGOING; }

  /** Legal moves in the current position (cached until the next move). */
  legalMoves(): Move[] {
    return this.legal ??= generateLegal(this.board);
  }

  /** Plays {@code m}, which must come from {@link legalMoves}, and re-adjudicates. */
  applyMove(m: Move): void {
    if (this.isOver) throw new Error('game is over');
    if (!this.legalMoves().includes(m)) throw new Error('illegal move: ' + lan(m));
    this.board.makeMove(m);
    this.legal = null;
    const key = this.board.key();
    this.repetitions.set(key, (this.repetitions.get(key) ?? 0) + 1);
    this.adjudicate();
  }

  /** The flag-faller loses UNLESS the opponent cannot possibly mate (a lone king). */
  timeout(flagFaller: number): void {
    if (this.isOver) return;
    const winner = flagFaller ^ 1;
    if (this.hasBareKing(winner)) this.result = RESULTS.DRAW_TIMEOUT_VS_BARE_KING;
    else this.result = winner === WHITE ? RESULTS.WHITE_WINS_TIMEOUT : RESULTS.BLACK_WINS_TIMEOUT;
  }

  resign(color: number): void {
    if (this.isOver) return;
    this.result = color === WHITE ? RESULTS.BLACK_WINS_RESIGNATION : RESULTS.WHITE_WINS_RESIGNATION;
  }

  agreeDraw(): void {
    if (!this.isOver) this.result = RESULTS.DRAW_AGREED;
  }

  private adjudicate(): void {
    const b = this.board;
    if (this.legalMoves().length === 0) {
      if (b.inCheck(b.sideToMove)) this.result = b.sideToMove === WHITE ? RESULTS.BLACK_WINS_MATE : RESULTS.WHITE_WINS_MATE;
      else this.result = RESULTS.DRAW_STALEMATE;
      return;
    }
    if (this.isInsufficientMaterial()) { this.result = RESULTS.DRAW_INSUFFICIENT_MATERIAL; return; }
    if (b.halfmoveClock >= 100) { this.result = RESULTS.DRAW_FIFTY_MOVES; return; }
    if ((this.repetitions.get(b.key()) ?? 0) >= 3) this.result = RESULTS.DRAW_REPETITION;
  }

  /**
   * FIDE dead positions: K vs K, K+B vs K, K+N vs K, and K+B vs K+B with
   * both bishops on the same square colour. Two knights are not declared.
   */
  private isInsufficientMaterial(): boolean {
    let minors = 0;
    const bishopSquareColor = [-1, -1];   // per side; -1 = no bishop seen, -2 = has a knight
    for (let sq = 0; sq < 64; sq++) {
      const p = this.board.squares[sq];
      if (p === EMPTY) continue;
      switch (typeOf(p)) {
        case PAWN: case ROOK: case QUEEN: return false;
        case KNIGHT:
          minors++;
          bishopSquareColor[colorOf(p)] = -2;
          break;
        case BISHOP: {
          minors++;
          const side = colorOf(p);
          if (bishopSquareColor[side] !== -1) return false;   // second bishop, or bishop + knight
          bishopSquareColor[side] = ((sq >>> 3) + (sq & 7)) & 1;
          break;
        }
      }
      if (minors > 2) return false;
    }
    if (minors <= 1) return true;
    return bishopSquareColor[0] >= 0 && bishopSquareColor[0] === bishopSquareColor[1];
  }

  private hasBareKing(color: number): boolean {
    return this.board.squares.every(p => p === EMPTY || colorOf(p) !== color || typeOf(p) === KING);
  }
}
