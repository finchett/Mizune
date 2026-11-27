// The extension's own engine, for when the Mizune app isn't installed: Mizune's own code
// (core, built for JavaScript) over dictionaries kept in a SQLite database in the extension's
// private file system. It runs in a worker, where SQLite can keep its file open and AnkiConnect
// can be asked and waited for, one request at a time. Requests come over ENGINE_CHANNEL.
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';
import { LocalEngine } from '../../core/build/dist/js/productionLibrary/Mizune-core.mjs';
import { ENGINE_CHANNEL } from './shared';

const channel = new BroadcastChannel(ENGINE_CHANNEL);

let opened: Promise<LocalEngine> | null = null;

function engine(): Promise<LocalEngine> {
  opened ??= (async () => {
    const sqlite3 = await sqlite3InitModule();
    // the pool keeps its files open for good, which only one worker at a time can do
    const pool = await openPool(sqlite3);
    const db = new pool.OpfsSAHPoolDb('/dictionaries.db');
    return new LocalEngine(db);
  })();
  opened.catch(() => (opened = null));
  return opened;
}

/** The pool, waiting a moment if an engine that's going away still has it. */
async function openPool(sqlite3: any) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await sqlite3.installOpfsSAHPoolVfs({ name: 'mizune', initialCapacity: 6 });
    } catch (e) {
      if (attempt >= 10) throw e;
      await new Promise((r) => setTimeout(r, 300));
    }
  }
}

const reply = (id: string, body: Record<string, unknown>) => channel.postMessage({ to: 'client', id, kind: 'reply', ...body });

channel.onmessage = async (event: MessageEvent) => {
  const request = event.data;
  if (request?.to !== 'engine') return;
  const { id } = request;
  if (request.kind === 'ping') return reply(id, { ok: true });
  try {
    const local = await engine();
    if (request.kind === 'message') {
      reply(id, { message: JSON.parse(local.handle(JSON.stringify(request.message))) });
    } else if (request.kind === 'import') {
      const answer = local.importDictionary(request.name, new Uint8Array(request.data), (progress) =>
        channel.postMessage({ to: 'client', id, kind: 'progress', progress: JSON.parse(progress) }));
      reply(id, { message: JSON.parse(answer) });
    }
  } catch (e) {
    reply(id, { message: { type: 'error', message: `Mizune's engine stopped: ${(e as Error)?.message ?? e}` } });
  }
};

// says it's there for whoever started it
channel.postMessage({ to: 'client', id: 'started', kind: 'reply' });
