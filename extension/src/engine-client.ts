// Asking the extension's own engine (engine.ts), from any of the extension's pages.
import { api, ENGINE_CHANNEL, isGecko } from './shared';

export interface EngineRequest {
  kind: 'ping' | 'message' | 'import';
  message?: Record<string, unknown>;
  name?: string;
  data?: ArrayBuffer;
}

/** Set in the page that runs the engine's worker (Firefox's background page), which needn't wake itself. */
let ownsEngine = false;

export function engineRunsHere() {
  ownsEngine = true;
}

/**
 * Firefox stops an idle background page after half a minute, and its engine's worker with it; and
 * talk over the channel doesn't count as activity. So a page asking the engine there first wakes
 * the background (which starts the engine), and while it waits, says so every few seconds.
 */
const KEEP_ALIVE_MS = 10_000;
const wakeBackground = () => api.runtime.sendMessage({ type: 'keepAlive' }).catch(() => undefined);

/** Sends [request] to the engine and waits for its answer; [onProgress] hears how an import goes. */
export async function askEngine(request: EngineRequest, onProgress?: (progress: any) => void, timeoutMs = 0): Promise<any> {
  const keepAlive = isGecko && !ownsEngine;
  if (keepAlive) await wakeBackground();
  const id = crypto.randomUUID();
  const channel = new BroadcastChannel(ENGINE_CHANNEL);
  const alive = keepAlive ? setInterval(wakeBackground, KEEP_ALIVE_MS) : undefined;
  return new Promise((resolve, reject) => {
    const done = () => {
      clearTimeout(timer);
      clearInterval(alive);
      channel.close();
    };
    const timer = timeoutMs ? setTimeout(() => { done(); reject(new Error('The engine didn’t answer')); }, timeoutMs) : undefined;
    channel.onmessage = (event: MessageEvent) => {
      const answer = event.data;
      if (answer?.to !== 'client' || answer.id !== id) return;
      if (answer.kind === 'progress') return onProgress?.(answer.progress);
      done();
      resolve(answer);
    };
    channel.postMessage({ to: 'engine', id, ...request });
  });
}

/**
 * Waits until the engine's worker is listening. While it imports a dictionary it can't answer,
 * but what it says of the import shows it's there: each word of that gives it longer.
 */
export async function engineReady(withinMs = 15000): Promise<void> {
  let until = Date.now() + withinMs;
  const channel = new BroadcastChannel(ENGINE_CHANNEL);
  channel.onmessage = (event: MessageEvent) => {
    if (event.data?.to === 'client' && event.data.kind === 'progress') until = Date.now() + withinMs;
  };
  try {
    for (;;) {
      try {
        await askEngine({ kind: 'ping' }, undefined, 500);
        return;
      } catch (e) {
        if (Date.now() > until) throw new Error('Mizune’s engine didn’t start');
      }
    }
  } finally {
    channel.close();
  }
}
