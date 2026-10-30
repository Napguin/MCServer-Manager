import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import session from 'express-session';
import { Store } from './store.js';
import { chown } from 'node:fs/promises';
import { Minecraft, SERVER_TYPES, serverType, MC_UID, luckpermsServerName } from './minecraft.js';
import { ServerFiles, UserError } from './files.js';
import * as modrinth from './modrinth.js';
import { LuckPerms, permissionsRouter } from './luckperms.js';
import { LobbySettings, LOBBY_DEFAULTS } from './lobby.js';
import { Moderation, banMessage } from './moderation.js';
import { MotdSettings, MOTD_DEFAULTS } from './motd.js';

const config = {
  port: Number(process.env.PORT || 8080),
  adminPassword: required('ADMIN_PASSWORD'),
  sessionSecret: required('SESSION_SECRET'),
  apiKey: required('DASHBOARD_API_KEY'),
  forwardingSecret: required('FORWARDING_SECRET'),
  dataDir: process.env.DATA_DIR || '/opt/mcnetwork/data',
  pluginDir: process.env.PLUGIN_DIR || '/opt/mcnetwork/build',
  network: process.env.DOCKER_NETWORK || 'mcnetwork',
  internalUrl: process.env.INTERNAL_URL || 'http://dashboard:8080',
  cookieSecure: process.env.COOKIE_SECURE === 'true',
  dbPassword: required('DB_PASSWORD'),
  lpApiUrl: process.env.LUCKPERMS_API_URL || 'http://luckperms-api:8080',
  lpApiKey: required('LP_API_KEY'),
};

function required(name) {
  const value = process.env[name];
  if (!value) {
    console.error(`Umgebungsvariable ${name} fehlt.`);
    process.exit(1);
  }
  return value;
}

const store = new Store(path.join(config.dataDir, 'dashboard', 'servers.json'));
const mc = new Minecraft({
  dataDir: config.dataDir,
  network: config.network,
  forwardingSecret: config.forwardingSecret,
  pluginDir: config.pluginDir,
  dashboardUrl: config.internalUrl,
  apiKey: config.apiKey,
  dbPassword: config.dbPassword,
  store,
});

const files = new ServerFiles(mc, store);
const lp = new LuckPerms(config.lpApiUrl, config.lpApiKey);
const lobbySettings = new LobbySettings(path.join(config.dataDir, 'dashboard', 'lobby.json'));
const motdSettings = new MotdSettings(path.join(config.dataDir, 'dashboard', 'motd.json'));

// Live-Status, den das Velocity-Plugin alle paar Sekunden meldet
let liveStatus = { servers: {}, players: [], totalPlayers: 0, updatedAt: 0 };
const proxyOnline = () => Date.now() - liveStatus.updatedAt < 20_000;
const onlinePlayers = () => (proxyOnline() ? liveStatus.players : []);
const moderation = new Moderation(path.join(config.dataDir, 'dashboard', 'moderation.json'), onlinePlayers);

function sanitizePlayers(list) {
  if (!Array.isArray(list)) return [];
  return list
    .filter((p) => p && typeof p.uuid === 'string' && typeof p.name === 'string')
    .slice(0, 2000)
    .map((p) => ({
      uuid: p.uuid.toLowerCase(),
      name: p.name.slice(0, 16),
      server: String(p.server ?? ''),
      vanished: !!p.vanished,
      ping: Number(p.ping) || 0,
    }));
}

const ID_PATTERN = /^[a-z0-9][a-z0-9-]{1,29}$/;
const RESERVED = new Set(['lobby', 'velocity', 'proxy', 'dashboard']);
const MEMORY_PATTERN = /^\d{3,5}M$|^\d{1,2}G$/;
const VERSION_PATTERN = /^(LATEST|SNAPSHOT|[0-9][0-9A-Za-z.\-]{0,30})$/;

async function withStatus(server) {
  let container = 'unknown';
  try {
    container = await mc.state(server.id);
  } catch {
    /* Docker gerade nicht erreichbar */
  }
  const live = proxyOnline() ? liveStatus.servers[server.id] : undefined;
  let status = container;
  if (container === 'running') {
    if (mc.provisioning.has(server.id) || !server.provisioned) status = 'setup';
    else status = live?.online ? 'online' : 'starting';
  }
  return {
    ...server,
    type: server.type || 'PAPER',
    typeLabel: serverType(server).label,
    contentLabel: serverType(server).contentDir === 'mods' ? 'Mods' : 'Plugins',
    host: mc.containerName(server.id),
    port: 25565,
    status,
    online: status === 'online',
    players: live?.players ?? 0,
  };
}

async function allWithStatus() {
  const list = await Promise.all(store.servers.map(withStatus));
  return list.sort((a, b) => (a.slot ?? 99) - (b.slot ?? 99) || a.name.localeCompare(b.name));
}

// ---------------- App ----------------

const app = express();
app.set('trust proxy', 1);
app.disable('x-powered-by');
app.use(express.json({ limit: '100kb' }));
app.use((req, res, next) => {
  res.set('X-Frame-Options', 'DENY');
  res.set('X-Content-Type-Options', 'nosniff');
  res.set('Referrer-Policy', 'same-origin');
  next();
});

// ----- Interne API für die Plugins (geschützt per API-Key) -----

function requireApiKey(req, res, next) {
  if (!safeEqual(req.get('X-Api-Key') || '', config.apiKey)) return res.status(401).json({ error: 'unauthorized' });
  next();
}

app.get('/api/internal/servers', requireApiKey, async (req, res) => {
  const servers = await allWithStatus();
  res.json({
    servers: servers.map((s) => ({
      id: s.id,
      name: s.name,
      description: s.description,
      icon: s.icon,
      slot: s.slot,
      hidden: !!s.hidden,
      // Geschützt: Beitritt nur mit dem Recht network.server.<id>
      restricted: !!s.restricted,
      // Für die Server-Verwaltung im Spiel
      typeLabel: s.typeLabel,
      version: s.version,
      memory: s.memory,
      host: s.host,
      port: s.port,
      status: s.status,
      online: s.online,
      players: s.players,
    })),
    // Für die Plugins: Texte/Schalter, Spieler im ganzen Netzwerk, Banns und Mutes
    lobby: lobbySettings.get(),
    totalPlayers: proxyOnline() ? liveStatus.totalPlayers : 0,
    players: onlinePlayers(),
    bans: moderation.activeBans().map((b) => ({
      uuid: b.uuid, expires: b.expires, message: banMessage(b, 'de'), message_en: banMessage(b, 'en'),
    })),
    mutes: moderation.activeMutes().map((m) => ({ uuid: m.uuid, expires: m.expires, reason: m.reason })),
    // Der Proxy holt die MOTD (mit Icon) nur, wenn sich diese Version ändert
    motdVersion: motdSettings.version,
    maintenance: !!motdSettings.get().maintenance,
  });
});

// ----- Server-Verwaltung aus dem Spiel (Admin-GUI, Recht prüft das Plugin) -----

app.post('/api/internal/servers/:id/:action', requireApiKey, async (req, res) => {
  const server = store.get(req.params.id);
  const de = req.body?.lang !== 'en';
  if (!server) throw new UserError(de ? 'Server nicht gefunden' : 'Server not found', 404);
  const by = String(req.body?.by ?? '?').slice(0, 32);
  // Start/Stopp dauern bis zu 30 s: im Hintergrund ausführen, das Plugin zeigt den Status danach an
  const background = (task) => task.catch((err) => console.error(`${req.params.action} ${server.id} fehlgeschlagen:`, err.message));
  switch (req.params.action) {
    case 'start': background(mc.start(server)); break;
    case 'stop': background(mc.stop(server.id)); break;
    case 'restart': background(mc.restart(server)); break;
    case 'settings': {
      if (server.id === 'lobby') throw new UserError(de ? 'Die Lobby bleibt immer offen und sichtbar' : 'The lobby always stays open and visible');
      const changes = {};
      if (req.body?.restricted !== undefined) changes.restricted = !!req.body.restricted;
      if (req.body?.hidden !== undefined) changes.hidden = !!req.body.hidden;
      await store.update(server.id, changes);
      break;
    }
    default: throw new UserError(de ? 'Unbekannte Aktion' : 'Unknown action');
  }
  console.log(`${by} (im Spiel): ${req.params.action} ${server.id}`);
  res.json({ ok: true });
});

app.post('/api/internal/maintenance', requireApiKey, async (req, res) => {
  await motdSettings.update({ maintenance: !!req.body?.enabled });
  console.log(`${String(req.body?.by ?? '?').slice(0, 32)} (im Spiel): Wartungsmodus ${req.body?.enabled ? 'an' : 'aus'}`);
  res.json({ ok: true });
});

app.get('/api/internal/motd', requireApiKey, (req, res) => {
  res.json({ version: motdSettings.version, ...motdSettings.get() });
});

app.post('/api/internal/status', requireApiKey, (req, res) => {
  const servers = req.body?.servers;
  if (servers && typeof servers === 'object') {
    liveStatus = {
      servers,
      players: sanitizePlayers(req.body.players),
      totalPlayers: Number(req.body.totalPlayers) || 0,
      updatedAt: Date.now(),
    };
  }
  // Velocity führt anstehende Kicks (auch nach einem Bann) sofort aus
  res.json({ ok: true, kicks: moderation.takeKicks() });
});

// Moderation aus dem Spiel (Admin-GUI und Befehle auf den Servern)
app.post('/api/internal/moderation', requireApiKey, async (req, res) => {
  res.json(await moderation.apply(req.body ?? {}));
});

// ----- Admin-Login -----

app.use(
  session({
    name: 'mcn.sid',
    secret: config.sessionSecret,
    resave: false,
    saveUninitialized: false,
    cookie: { httpOnly: true, sameSite: 'strict', secure: config.cookieSecure, maxAge: 7 * 24 * 3600 * 1000 },
  }),
);

const failedLogins = new Map(); // ip -> { count, until }

app.post('/api/login', (req, res) => {
  const ip = req.ip;
  const entry = failedLogins.get(ip);
  if (entry && entry.count >= 5 && entry.until > Date.now()) {
    return res.status(429).json({ error: 'Zu viele Versuche. Bitte in ein paar Minuten erneut probieren.' });
  }
  if (!safeEqual(String(req.body?.password ?? ''), config.adminPassword)) {
    const count = (entry && entry.until > Date.now() ? entry.count : 0) + 1;
    failedLogins.set(ip, { count, until: Date.now() + 10 * 60 * 1000 });
    return res.status(401).json({ error: 'Falsches Passwort.' });
  }
  failedLogins.delete(ip);
  req.session.regenerate((err) => {
    if (err) return res.status(500).json({ error: 'Session-Fehler' });
    req.session.admin = true;
    res.json({ ok: true });
  });
});

app.post('/api/logout', (req, res) => {
  req.session.destroy(() => res.json({ ok: true }));
});

app.get('/api/me', (req, res) => res.json({ admin: !!req.session.admin }));

function requireAdmin(req, res, next) {
  if (!req.session.admin) return res.status(401).json({ error: 'Nicht angemeldet.' });
  next();
}

// ----- Admin-API -----

const admin = express.Router();
admin.use(requireAdmin);

admin.get('/overview', async (req, res) => {
  res.json({
    proxyOnline: proxyOnline(),
    totalPlayers: proxyOnline() ? liveStatus.totalPlayers : 0,
    servers: await allWithStatus(),
    types: Object.entries(SERVER_TYPES).map(([id, t]) => ({ id, label: t.label })),
  });
});

admin.post('/servers', async (req, res) => {
  const body = req.body ?? {};
  const id = String(body.id ?? '').trim().toLowerCase();
  const name = String(body.name ?? '').trim();
  const version = String(body.version || 'LATEST').trim();
  const memory = String(body.memory || '2G').trim().toUpperCase();
  const type = String(body.type || 'PAPER').toUpperCase();

  if (!ID_PATTERN.test(id)) return res.status(400).json({ error: 'ID: 2-30 Zeichen, nur a-z, 0-9 und -' });
  if (RESERVED.has(id) || store.get(id)) return res.status(400).json({ error: 'Diese ID ist schon vergeben.' });
  if (!name || name.length > 40) return res.status(400).json({ error: 'Name: 1-40 Zeichen' });
  if (!VERSION_PATTERN.test(version)) return res.status(400).json({ error: 'Ungültige Version' });
  if (!(type in SERVER_TYPES)) return res.status(400).json({ error: 'Unbekannter Servertyp' });
  if (!MEMORY_PATTERN.test(memory)) return res.status(400).json({ error: 'RAM z.B. 2G oder 1536M' });

  const server = {
    id,
    name,
    description: String(body.description ?? '').slice(0, 300),
    icon: sanitizeIcon(body.icon),
    slot: clampSlot(body.slot),
    type,
    version,
    memory,
    hidden: false,
    restricted: !!body.restricted,
    provisioned: false,
    createdAt: new Date().toISOString(),
  };
  await store.add(server);
  res.status(201).json(server);

  // Erstellen & Starten im Hintergrund (Image-Download kann dauern)
  mc.start(server).catch((err) => console.error(`Start von ${id} fehlgeschlagen:`, err.message));
});

admin.patch('/servers/:id', async (req, res) => {
  const server = store.get(req.params.id);
  if (!server) return res.status(404).json({ error: 'Server nicht gefunden' });
  const body = req.body ?? {};
  const changes = {};
  if (body.name !== undefined) {
    const name = String(body.name).trim();
    if (!name || name.length > 40) return res.status(400).json({ error: 'Name: 1-40 Zeichen' });
    changes.name = name;
  }
  if (body.description !== undefined) changes.description = String(body.description).slice(0, 300);
  if (body.icon !== undefined) changes.icon = sanitizeIcon(body.icon);
  if (body.slot !== undefined) changes.slot = clampSlot(body.slot);
  if (body.hidden !== undefined && server.id !== 'lobby') changes.hidden = !!body.hidden;
  // Die Lobby muss für alle offen bleiben, sonst kommt niemand ins Netzwerk
  if (body.restricted !== undefined && server.id !== 'lobby') changes.restricted = !!body.restricted;
  res.json(await store.update(server.id, changes));
});

for (const action of ['start', 'stop', 'restart']) {
  admin.post(`/servers/:id/${action}`, async (req, res) => {
    const server = store.get(req.params.id);
    if (!server) return res.status(404).json({ error: 'Server nicht gefunden' });
    try {
      if (action === 'stop') await mc.stop(server.id);
      else await mc[action](server);
      res.json({ ok: true });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });
}

admin.delete('/servers/:id', async (req, res) => {
  const server = store.get(req.params.id);
  if (!server) return res.status(404).json({ error: 'Server nicht gefunden' });
  if (server.id === 'lobby') return res.status(400).json({ error: 'Die Lobby kann nicht gelöscht werden.' });
  try {
    await mc.remove(server.id, req.query.deleteFiles === '1');
    await store.remove(server.id);
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

admin.get('/servers/:id/logs', async (req, res) => {
  if (!store.get(req.params.id)) return res.status(404).json({ error: 'Server nicht gefunden' });
  try {
    res.json({ logs: await mc.logs(req.params.id, 300) });
  } catch (err) {
    res.json({ logs: `Keine Logs verfügbar: ${err.message}` });
  }
});

admin.post('/servers/:id/command', async (req, res) => {
  if (!store.get(req.params.id)) return res.status(404).json({ error: 'Server nicht gefunden' });
  const command = String(req.body?.command ?? '').trim().replace(/^\//, '');
  if (!command) return res.status(400).json({ error: 'Kein Befehl' });
  try {
    res.json({ output: await mc.command(req.params.id, command) });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ----- Plugins / Mods -----

function findServer(req) {
  const server = store.get(req.params.id);
  if (!server) throw new UserError('Server nicht gefunden', 404);
  return server;
}

/** Tatsächliche MC-Version: aus dem Log, sonst die eingetragene (außer LATEST/SNAPSHOT). */
async function gameVersionOf(server) {
  const detected = await mc.detectVersion(server.id);
  if (detected) return detected;
  return ['LATEST', 'SNAPSHOT'].includes(server.version) ? null : server.version;
}

admin.get('/servers/:id/plugins', async (req, res) => {
  const server = findServer(req);
  res.json({
    type: serverType(server).label,
    folder: serverType(server).contentDir,
    gameVersion: await gameVersionOf(server),
    files: await files.listContent(server),
  });
});

admin.post('/servers/:id/plugins/upload', express.raw({ type: 'application/octet-stream', limit: '100mb' }), async (req, res) => {
  const server = findServer(req);
  if (!Buffer.isBuffer(req.body) || !req.body.length) throw new UserError('Keine Datei empfangen');
  const name = await files.saveContent(server, req.query.name, req.body);
  res.json({ ok: true, name });
});

admin.delete('/servers/:id/plugins/:file', async (req, res) => {
  await files.deleteContent(findServer(req), req.params.file);
  res.json({ ok: true });
});

admin.get('/servers/:id/modrinth/search', async (req, res) => {
  const server = findServer(req);
  const gameVersion = await gameVersionOf(server);
  res.json({ gameVersion, results: await modrinth.search(server, req.query.q, gameVersion) });
});

admin.post('/servers/:id/modrinth/install', async (req, res) => {
  const server = findServer(req);
  const projectId = String(req.body?.projectId ?? '');
  if (!/^[A-Za-z0-9]{4,20}$/.test(projectId)) throw new UserError('Ungültige Projekt-ID');
  const installed = await modrinth.install(files, store, server.id, projectId, await gameVersionOf(server));
  res.json({ ok: true, installed });
});

// ----- Datei-Editor -----

admin.get('/servers/:id/files', async (req, res) => {
  const server = findServer(req);
  res.json({ path: String(req.query.path ?? ''), entries: await files.listDir(server.id, req.query.path) });
});

admin.get('/servers/:id/file', async (req, res) => {
  const server = findServer(req);
  res.json({ path: req.query.path, content: await files.readText(server.id, req.query.path) });
});

admin.put('/servers/:id/file', async (req, res) => {
  const server = findServer(req);
  await files.writeText(server.id, req.body?.path, req.body?.content);
  res.json({ ok: true });
});

// ----- Spieler & Moderation -----

admin.get('/moderation', (req, res) => {
  res.json({
    proxyOnline: proxyOnline(),
    players: onlinePlayers(),
    bans: moderation.activeBans(),
    mutes: moderation.activeMutes(),
    log: moderation.data.log.slice(0, 50),
  });
});

admin.post('/moderation', async (req, res) => {
  res.json(await moderation.apply({ ...req.body, by: 'Dashboard' }));
});

// ----- MOTD & Server-Icon -----

admin.get('/motd', (req, res) => {
  res.json({ settings: motdSettings.get(), defaults: MOTD_DEFAULTS, networkName: lobbySettings.get().networkName });
});

admin.put('/motd', express.json({ limit: '200kb' }), async (req, res) => {
  res.json({ settings: await motdSettings.update(req.body ?? {}) });
});

// ----- Lobby-Einstellungen -----

admin.get('/lobby', (req, res) => {
  res.json({ settings: lobbySettings.get(), defaults: LOBBY_DEFAULTS });
});

admin.put('/lobby', async (req, res) => {
  res.json({ settings: await lobbySettings.update(req.body ?? {}) });
});

admin.get('/proxy/logs', async (req, res) => {
  try {
    res.json({ logs: await mc.proxyLogs(300) });
  } catch (err) {
    res.json({ logs: `Keine Logs verfügbar: ${err.message}` });
  }
});

admin.post('/proxy/restart', async (req, res) => {
  try {
    await mc.proxyRestart();
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Rechte-Verwaltung (LuckPerms). Server-Namen für Kontexte: Proxy + alle Minecraft-Server
app.use('/api/perms', requireAdmin, permissionsRouter(lp, () => [
  { id: 'proxy', name: 'Proxy (Velocity)' },
  ...store.servers.map((s) => ({ id: luckpermsServerName(s.id), name: s.name })),
]));

app.use('/api', admin);

// Fehler aus den Routen als JSON zurückgeben
// eslint-disable-next-line no-unused-vars
app.use('/api', (err, req, res, next) => {
  if (err instanceof UserError) return res.status(err.status).json({ error: err.message });
  if (err.type === 'entity.too.large') return res.status(413).json({ error: 'Datei zu groß (max. 100 MB)' });
  if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'Ungültige Anfrage' });
  console.error(err);
  res.status(500).json({ error: err.message || 'Interner Fehler' });
});

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
app.use(express.static(publicDir));

// ---------------- Helfer ----------------

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(a).digest();
  const hb = crypto.createHash('sha256').update(b).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function sanitizeIcon(icon) {
  const value = String(icon || 'GRASS_BLOCK').trim().toUpperCase().replace(/^MINECRAFT:/, '');
  return /^[A-Z0-9_]{2,50}$/.test(value) ? value : 'GRASS_BLOCK';
}

function clampSlot(slot) {
  const n = Number.parseInt(slot, 10);
  return Number.isInteger(n) && n >= 0 && n < 54 ? n : -1;
}

// ---------------- Start ----------------

await store.load();
await lobbySettings.load();
await moderation.load();
await motdSettings.load();

// Lobby beim ersten Start automatisch anlegen
if (!store.get('lobby')) {
  await store.add({
    id: 'lobby',
    name: 'Lobby',
    description: 'Die Lobby',
    icon: 'NETHER_STAR',
    slot: -1,
    version: process.env.LOBBY_VERSION || 'LATEST',
    memory: process.env.LOBBY_MEMORY || '1G',
    hidden: true,
    provisioned: false,
    createdAt: new Date().toISOString(),
  });
}

app.listen(config.port, () => console.log(`Dashboard läuft auf Port ${config.port}`));

// Lobby starten, veraltete Container aktualisieren und nicht fertig eingerichtete Server weiter einrichten
(async () => {
  for (const server of store.servers) {
    try {
      if (await mc.upgrade(server)) continue;
      if (server.id === 'lobby') await mc.start(server);
      else if (!server.provisioned && (await mc.state(server.id)) === 'running') mc.provision(server.id);
    } catch (err) {
      console.error(`Server ${server.id}:`, err.message);
    }
  }
})();

// LuckPerms auch auf dem Proxy installieren (für Proxy-Befehle und Rechte)
(async () => {
  try {
    const dir = path.join(config.dataDir, 'velocity', 'plugins');
    const installed = await modrinth.ensurePlugin(dir, modrinth.LUCKPERMS_PROJECT, ['velocity'], 'LuckPerms');
    if (installed) {
      await chown(installed, MC_UID, MC_UID).catch(() => {});
      console.log('LuckPerms für Velocity installiert, starte Proxy neu ...');
      await mc.proxyRestart();
    }
  } catch (err) {
    console.error('LuckPerms für Velocity konnte nicht installiert werden:', err.message);
  }
})();
