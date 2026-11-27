// Builds the extension for Chrome and for Firefox from the same sources:
//   node build.mjs           dist/chrome and dist/firefox
//   node build.mjs --watch   the same, again on every change
//   node build.mjs --zip     and a zip of each, for the stores
// The two differ only in their manifests: manifest.json holds what they share, and its
// "chrome" and "firefox" parts are merged into each one's own.
import * as esbuild from 'esbuild';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const watch = process.argv.includes('--watch');
const zip = process.argv.includes('--zip');
// the extension's version follows the app's
const version = readFileSync(join(here, '../gradle.properties'), 'utf8').match(/^appVersion=(.+)$/m)[1].trim();

const entries = {
  background: 'src/background.ts',
  offscreen: 'src/offscreen.ts',
  page: 'src/page/page.ts',
  content: 'src/content/main.ts',
  options: 'src/options.ts',
  popup: 'src/popup.ts',
  welcome: 'src/welcome.ts',
  card: 'src/card.ts',
};

// Mizune's own code, built for JavaScript (core's js target), which the engine runs
const repo = join(here, '..');
const BUNDLED = 'JMdict_english.zip';
if (!process.env.MIZUNE_SKIP_CORE) {
  execFileSync(join(repo, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew'), ['-q', ':core:jsBrowserProductionLibraryDistribution'], { cwd: repo, stdio: 'inherit' });
}

function manifest(target) {
  const base = JSON.parse(readFileSync(join(here, 'manifest.json'), 'utf8'));
  const own = base[target];
  delete base.chrome;
  delete base.firefox;
  const merged = { ...base, ...own, version };
  merged.permissions = [...(base.permissions ?? []), ...(own.permissions ?? [])];
  merged.host_permissions = [...(base.host_permissions ?? []), ...(own.host_permissions ?? [])];
  // a site one browser has from the start isn't also one it asks for later
  merged.optional_host_permissions = (base.optional_host_permissions ?? []).filter((o) => !merged.host_permissions.includes(o));
  if (!merged.optional_host_permissions.length) delete merged.optional_host_permissions;
  return merged;
}

async function build(target) {
  const out = join(here, 'dist', target);
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  cpSync(join(here, 'public'), out, { recursive: true });
  if (target === 'firefox') rmSync(join(out, 'offscreen.html'));
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest(target), null, 2));
  // SQLite finds its WebAssembly beside the engine's script
  cpSync(join(here, 'node_modules/@sqlite.org/sqlite-wasm/dist/sqlite3.wasm'), join(out, 'sqlite3.wasm'));
  // the dictionary that comes built in, the apps' own
  mkdirSync(join(out, 'dictionaries'));
  cpSync(join(repo, 'app/src/main/assets/dictionaries', BUNDLED), join(out, 'dictionaries', BUNDLED));
  const common = {
    outdir: out,
    bundle: true,
    target: target === 'chrome' ? 'chrome116' : 'firefox128',
    sourcemap: watch ? 'inline' : false,
    logLevel: 'warning',
    // core's build imports fflate, which is installed here
    nodePaths: [join(here, 'node_modules')],
  };
  const builds = [
    {
      ...common,
      entryPoints: Object.fromEntries(Object.entries(entries).filter(([name]) => target === 'chrome' || name !== 'offscreen')
        .map(([name, file]) => [name, join(here, file)])),
      format: 'iife',
    },
    // the engine's worker is a module, for SQLite's import.meta.url
    { ...common, entryPoints: { engine: join(here, 'src/engine.ts') }, format: 'esm' },
  ];
  for (const options of builds) {
    if (watch) {
      const context = await esbuild.context(options);
      await context.watch();
    } else {
      await esbuild.build(options);
    }
  }
  if (zip) {
    const file = join(here, 'dist', `mizune-${version}-${target}.zip`);
    rmSync(file, { force: true });
    execFileSync('zip', ['-qr', file, '.'], { cwd: out });
    console.log(`dist/mizune-${version}-${target}.zip`);
  }
}

await build('chrome');
await build('firefox');
if (watch) console.log('watching for changes');
