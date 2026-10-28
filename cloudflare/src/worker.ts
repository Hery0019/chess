import { DurableObject } from 'cloudflare:workers';
import { ChessServer } from './server.ts';

/**
 * Cloudflare entry point. The page (src/web/index.html, shared with the
 * Java server) is a static asset, served before this code runs; this
 * Worker only sees what is left: {@code /ws}, upgraded to a WebSocket and
 * handed to the single Lobby Durable Object, and 404 for anything else.
 */

interface Env {
  LOBBY: DurableObjectNamespace<Lobby>;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (new URL(request.url).pathname !== '/ws') return new Response('not found', { status: 404 });
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('WebSocket upgrade expected', { status: 400 });
    }
    return env.LOBBY.get(env.LOBBY.idFromName('lobby')).fetch(request);
  },
} satisfies ExportedHandler<Env>;

/** How often silent clients are looked for. */
const SWEEP_MS = 5_000;

/**
 * Every connection lands in this one object, so pairing in arrival order
 * works exactly as in the Java server. State lives in memory only: the
 * object stays loaded while any socket is open (no hibernation — the
 * clients ping every 5 s anyway), and a restart (a deploy) ends the games
 * in progress, like restarting app.ServerMain would.
 */
export class Lobby extends DurableObject<Env> {
  private readonly server = new ChessServer();
  private sweeper: ReturnType<typeof setInterval> | undefined;

  async fetch(_request: Request): Promise<Response> {
    const [browser, socket] = Object.values(new WebSocketPair());
    socket.accept();
    const client = this.server.connect({
      send: line => socket.send(line),
      close: () => socket.close(1000, 'bye'),
    });
    socket.addEventListener('message', e => this.server.receive(client, typeof e.data === 'string' ? e.data : ''));
    socket.addEventListener('close', () => this.server.disconnect(client));
    socket.addEventListener('error', () => this.server.disconnect(client));
    this.sweeper ??= setInterval(() => {
      this.server.sweep();
      if (this.server.connectedClients === 0) { clearInterval(this.sweeper); this.sweeper = undefined; }
    }, SWEEP_MS);
    return new Response(null, { status: 101, webSocket: browser });
  }
}
