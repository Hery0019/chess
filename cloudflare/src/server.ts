import { BLACK, Game, lan, WHITE } from './rules.ts';

/**
 * Relay + referee for online games: a port of net.ChessServer with the
 * transport left out (the Durable Object in worker.ts wires it to
 * WebSockets; the tests to plain arrays). Clients say HELLO and are paired
 * in order of arrival into a Room; each room keeps its own Game and relays
 * a move only if it is legal and from the side to move. Same protocol
 * lines as net.Protocol — see that class for the full table.
 *
 * Single-threaded: a Durable Object delivers one event at a time, so there
 * is no locking here.
 */

export const VERSION = 1;
export const MAX_NAME_LENGTH = 16;
/** A silent client is dropped after this long (clients ping every few seconds). */
export const READ_TIMEOUT_MS = 30_000;

/** One protocol line out, and a way to hang up. */
export interface Connection {
  send(line: string): void;
  close(): void;
}

/** Letters, digits, '_' and '-', at most {@link MAX_NAME_LENGTH} chars, never empty. */
export function sanitizeName(raw: string | undefined): string {
  const s = (raw ?? '').trim().replace(/[^A-Za-z0-9_-]/g, '_').slice(0, MAX_NAME_LENGTH);
  return s === '' ? 'Player' : s;
}

/** Integer.parseInt: an optional sign and digits only, else null. */
function parseIntStrict(s: string | undefined): number | null {
  return s !== undefined && /^[+-]?\d{1,9}$/.test(s) ? Number(s) : null;
}

export class Client {
  name = '?';
  minutes = 0;
  greeted = false;
  room: Room | null = null;
  color = WHITE;
  closed = false;
  lastSeen: number;
  readonly connection: Connection;
  private readonly server: ChessServer;

  constructor(server: ChessServer, connection: Connection, now: number) {
    this.server = server;
    this.connection = connection;
    this.lastSeen = now;
  }

  send(type: string, ...args: string[]): void {
    if (this.closed) return;
    try {
      this.connection.send(args.length === 0 ? type : type + ' ' + args.join(' '));
    } catch {
      // The socket is gone: disconnect once the current message is handled, never in the middle of a room update.
      queueMicrotask(() => this.server.disconnect(this));
    }
  }
}

export class ChessServer {
  private readonly clients = new Set<Client>();
  private waiting: Client | null = null;

  get connectedClients(): number { return this.clients.size; }

  connect(connection: Connection, now: number = Date.now()): Client {
    const c = new Client(this, connection, now);
    this.clients.add(c);
    return c;
  }

  /** One protocol line from {@code c}. */
  receive(c: Client, line: string, now: number = Date.now()): void {
    if (c.closed) return;
    c.lastSeen = now;
    const t = line.trim().split(/\s+/);
    if (t[0] === '') return;
    const [type, ...args] = t;
    switch (type) {
      case 'PING': c.send('PONG'); break;
      case 'HELLO': this.hello(c, args); break;
      default:
        if (c.room === null) c.send('ERROR', 'not', 'in', 'a', 'game');
        else c.room.handle(c, type, args);
    }
  }

  /** The connection of {@code c} ended (or must end): leave the waiting slot and the room, hang up. */
  disconnect(c: Client): void {
    if (c.closed) return;
    c.closed = true;
    this.clients.delete(c);
    if (this.waiting === c) this.waiting = null;
    c.room?.leave(c);
    try { c.connection.close(); } catch { /* already closed */ }
  }

  /** Drops every client silent for longer than {@link READ_TIMEOUT_MS}. */
  sweep(now: number = Date.now()): void {
    for (const c of [...this.clients]) if (now - c.lastSeen > READ_TIMEOUT_MS) this.disconnect(c);
  }

  private hello(c: Client, args: string[]): void {
    if (c.greeted) { c.send('ERROR', 'already', 'greeted'); return; }
    if (parseIntStrict(args[0]) !== VERSION) {
      c.send('ERROR', 'unsupported', 'protocol', 'version');
      this.disconnect(c);
      return;
    }
    c.minutes = Math.max(0, Math.min(parseIntStrict(args[1]) ?? 0, 180));
    c.name = sanitizeName(args[2]);
    c.greeted = true;
    c.send('WELCOME');
    if (this.waiting === null || this.waiting.closed) {
      this.waiting = c;
    } else {
      const partner = this.waiting;
      this.waiting = null;
      new Room(partner, c).start();
    }
  }
}

/** A game between two clients. */
class Room {
  /** Index = colour. */
  private readonly players: (Client | null)[];
  private readonly minutes: number;
  private readonly wantsRematch = [false, false];
  private game = new Game();
  private over = false;

  constructor(first: Client, second: Client) {
    this.players = [first, second];
    this.minutes = first.minutes;   // the first arrival's time control wins
    first.room = this;
    second.room = this;
  }

  start(): void {
    this.game = new Game();
    this.over = false;
    this.wantsRematch.fill(false);
    const [white, black] = this.players as Client[];
    for (const color of [WHITE, BLACK]) {
      const p = this.players[color]!;
      p.color = color;
      p.send('START', color === WHITE ? 'WHITE' : 'BLACK', String(this.minutes), white.name, black.name);
    }
  }

  handle(c: Client, type: string, args: string[]): void {
    const opp = this.players[c.color ^ 1];
    if (!opp) { c.send('ERROR', 'opponent', 'left'); return; }
    switch (type) {
      case 'MOVE': {
        if (this.over) { c.send('ERROR', 'game', 'over'); return; }
        if (this.game.sideToMove !== c.color) { c.send('ERROR', 'not', 'your', 'turn'); return; }
        const m = this.game.legalMoves().find(legal => lan(legal) === args[0]);
        if (!m) { c.send('ERROR', 'illegal', 'move', String(args[0] ?? null)); return; }
        this.game.applyMove(m);
        opp.send('MOVE', lan(m));
        if (this.game.isOver) this.finish();
        break;
      }
      case 'LEGAL': {
        // The browser client keeps no chess rules: it asks for the moves it may play.
        const moves = !this.over && this.game.sideToMove === c.color ? this.game.legalMoves().map(lan) : [];
        c.send('LEGAL', ...moves);
        break;
      }
      case 'RESIGN':
        opp.send('RESIGN');
        if (!this.over) { this.game.resign(c.color); this.finish(); }
        break;
      case 'DRAW_OFFER':
      case 'DRAW_DECLINE':
        opp.send(type);
        break;
      case 'DRAW_ACCEPT':
        opp.send('DRAW_ACCEPT');
        if (!this.over) { this.game.agreeDraw(); this.finish(); }
        break;
      case 'TIMEOUT':
        opp.send('TIMEOUT');
        if (!this.over) { this.game.timeout(c.color); this.finish(); }
        break;
      case 'REMATCH':
        this.wantsRematch[c.color] = true;
        opp.send('REMATCH');
        if (this.wantsRematch[0] && this.wantsRematch[1]) {
          this.players.reverse();   // colours swapped
          this.start();
        }
        break;
      default:
        c.send('ERROR', 'unknown', 'message', type);
    }
  }

  /** The game just ended in the referee's Game: tell both sides how. */
  private finish(): void {
    this.over = true;
    const { pgn, message } = this.game.result;
    for (const p of this.players) p?.send('RESULT', pgn, ...message.split(/\s+/));
  }

  leave(c: Client): void {
    const opp = this.players[c.color ^ 1];
    this.players[c.color] = null;
    c.room = null;
    this.over = true;
    if (opp) {
      opp.send('OPPONENT_LEFT');
      opp.room = null;
      this.players[c.color ^ 1] = null;
    }
  }
}
