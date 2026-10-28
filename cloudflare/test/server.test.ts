import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ChessServer, Client, READ_TIMEOUT_MS, sanitizeName } from '../src/server.ts';

/** A connected client whose received lines pile up in {@code inbox}. */
class Peer {
  readonly inbox: string[] = [];
  closed = false;
  readonly client: Client;
  private readonly server: ChessServer;

  constructor(server: ChessServer, now = 0) {
    this.server = server;
    this.client = server.connect({ send: line => { this.inbox.push(line); }, close: () => { this.closed = true; } }, now);
  }

  say(line: string, now = 0): void { this.server.receive(this.client, line, now); }

  /** Everything received since the last call. */
  take(): string[] { return this.inbox.splice(0); }
}

function pair(server = new ChessServer()): [Peer, Peer, ChessServer] {
  const a = new Peer(server), b = new Peer(server);
  a.say('HELLO 1 5 Alice');
  b.say('HELLO 1 0 Bob');
  return [a, b, server];
}

test('pairing in arrival order: first is White, first time control wins', () => {
  const [a, b] = pair();
  assert.deepEqual(a.take(), ['WELCOME', 'START WHITE 5 Alice Bob']);
  assert.deepEqual(b.take(), ['WELCOME', 'START BLACK 5 Alice Bob']);
});

test('a move is relayed only when legal and in turn', () => {
  const [a, b] = pair();
  a.take(); b.take();
  b.say('MOVE e7e5');
  assert.deepEqual(b.take(), ['ERROR not your turn']);
  a.say('MOVE e2e5');
  assert.deepEqual(a.take(), ['ERROR illegal move e2e5']);
  a.say('MOVE f2f3');
  assert.deepEqual(b.take(), ['MOVE f2f3']);
  a.say('LEGAL');
  assert.deepEqual(a.take(), ['LEGAL'], 'nothing to play when it is not my turn');
  b.say('LEGAL');
  const legal = b.take()[0].split(' ');
  assert.equal(legal[0], 'LEGAL');
  assert.equal(legal.length - 1, 20);
});

test('checkmate is announced to both with RESULT', () => {
  const [a, b] = pair();
  a.say('MOVE f2f3'); b.say('MOVE e7e5'); a.say('MOVE g2g4'); b.say('MOVE d8h4');
  assert.deepEqual(a.take().slice(-2), ['MOVE d8h4', 'RESULT 0-1 Checkmate — Black wins']);
  assert.equal(b.take().at(-1), 'RESULT 0-1 Checkmate — Black wins');
  a.say('MOVE e2e4');
  assert.deepEqual(a.take(), ['ERROR game over']);
});

test('resign, draw offer / accept, timeout', () => {
  const [a, b] = pair();
  a.take(); b.take();
  b.say('RESIGN');
  assert.deepEqual(a.take(), ['RESIGN', 'RESULT 1-0 Black resigned — White wins']);

  const [c, d] = pair();
  c.take(); d.take();
  c.say('DRAW_OFFER');
  d.say('DRAW_ACCEPT');
  assert.deepEqual(c.take(), ['DRAW_ACCEPT', 'RESULT 1/2-1/2 Draw — by agreement']);
  assert.deepEqual(d.take(), ['DRAW_OFFER', 'RESULT 1/2-1/2 Draw — by agreement']);

  const [e, f] = pair();
  e.take(); f.take();
  e.say('TIMEOUT');
  assert.deepEqual(f.take(), ['TIMEOUT', 'RESULT 0-1 White lost on time — Black wins']);
});

test('rematch when both ask, colours swapped', () => {
  const [a, b] = pair();
  a.say('RESIGN');
  a.take(); b.take();
  a.say('REMATCH');
  assert.deepEqual(b.take(), ['REMATCH']);
  b.say('REMATCH');
  assert.deepEqual(a.take(), ['REMATCH', 'START BLACK 5 Bob Alice']);
  assert.deepEqual(b.take(), ['START WHITE 5 Bob Alice']);
  b.say('MOVE e2e4');
  assert.deepEqual(a.take(), ['MOVE e2e4']);
});

test('several rooms at once; a third player waits for a fourth', () => {
  const server = new ChessServer();
  const [a, b] = pair(server);
  const c = new Peer(server);
  c.say('HELLO 1 0 Carol');
  assert.deepEqual(c.take(), ['WELCOME']);
  const d = new Peer(server);
  d.say('HELLO 1 0 Dave');
  assert.deepEqual(c.take(), ['START WHITE 0 Carol Dave']);
  a.say('MOVE e2e4');
  assert.deepEqual(b.take().at(-1), 'MOVE e2e4');
  assert.deepEqual(d.take(), ['WELCOME', 'START BLACK 0 Carol Dave']);
});

test('a disconnect tells the opponent; the waiting slot is freed', () => {
  const [a, b, server] = pair();
  a.take(); b.take();
  server.disconnect(b.client);
  assert.ok(b.closed);
  assert.deepEqual(a.take(), ['OPPONENT_LEFT']);
  a.say('MOVE e2e4');
  assert.deepEqual(a.take(), ['ERROR not in a game']);

  const w = new Peer(server);
  w.say('HELLO 1 0 Waiter');
  server.disconnect(w.client);
  const x = new Peer(server), y = new Peer(server);
  x.say('HELLO 1 0 X');
  y.say('HELLO 1 0 Y');
  assert.deepEqual(x.take(), ['WELCOME', 'START WHITE 0 X Y'], 'the gone player was not paired');
});

test('protocol hygiene: ping, version, double hello, names, unknown messages', () => {
  const server = new ChessServer();
  const p = new Peer(server);
  p.say('PING');
  p.say('MOVE e2e4');
  assert.deepEqual(p.take(), ['PONG', 'ERROR not in a game']);
  const old = new Peer(server);
  old.say('HELLO 2 0 Old');
  assert.deepEqual(old.take(), ['ERROR unsupported protocol version']);
  assert.ok(old.closed);
  p.say('HELLO 1 999 x');
  p.say('HELLO 1 0 x');
  assert.deepEqual(p.take(), ['WELCOME', 'ERROR already greeted']);
  assert.equal(sanitizeName('  Jean Pierre!  '), 'Jean_Pierre_');
  assert.equal(sanitizeName(''), 'Player');
  assert.equal(sanitizeName('abcdefghijklmnopqrstuvwxyz'), 'abcdefghijklmnop');
  const [a] = pair(server);
  a.take();
  a.say('NONSENSE');
  assert.deepEqual(a.take(), ['ERROR unknown message NONSENSE']);
});

test('time control is clamped to 0..180 minutes', () => {
  const server = new ChessServer();
  const a = new Peer(server), b = new Peer(server);
  a.say('HELLO 1 999 A');
  b.say('HELLO 1 0 B');
  assert.equal(a.take().at(-1), 'START WHITE 180 A B');
});

test('a silent client is dropped after the read timeout', () => {
  const server = new ChessServer();
  const a = new Peer(server, 0), b = new Peer(server, 0);
  a.say('HELLO 1 0 A', 0);
  b.say('HELLO 1 0 B', 0);
  a.take();
  a.say('PING', READ_TIMEOUT_MS);
  server.sweep(READ_TIMEOUT_MS + 1);
  assert.ok(b.closed);
  assert.ok(!a.closed);
  assert.deepEqual(a.take(), ['PONG', 'OPPONENT_LEFT']);
  assert.equal(server.connectedClients, 1);
});

test('a failing send disconnects that client after the current message', async () => {
  const server = new ChessServer();
  const a = new Peer(server);
  let broken = false;
  const b = server.connect({ send: () => { if (broken) throw new Error('gone'); }, close: () => {} });
  a.say('HELLO 1 0 A');
  server.receive(b, 'HELLO 1 0 B');
  a.take();
  broken = true;
  a.say('MOVE e2e4');
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.deepEqual(a.take(), ['OPPONENT_LEFT']);
});
