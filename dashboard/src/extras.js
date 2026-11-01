import fs from 'node:fs/promises';
import path from 'node:path';
import { UserError } from './files.js';

// Lobby-Extras: Hologramme, NPCs, Server-Schilder, Jump & Runs und Cosmetics.
// Positionen setzt man im Spiel (das Lobby-Plugin schickt sie hierher), Texte lassen sich
// zusätzlich im Dashboard bearbeiten. Bestzeiten der Jump & Runs liegen ebenfalls hier.

export const KINDS = ['holograms', 'npcs', 'signs', 'parkours'];

/** Alle Cosmetics, die das Lobby-Plugin kennt (Reihenfolge = Reihenfolge im Menü). */
export const COSMETICS = {
  trail: ['hearts', 'flames', 'notes', 'clouds', 'snow', 'sparkle', 'magic', 'rainbow', 'souls', 'cherry'],
  hat: ['glass', 'tnt', 'melon', 'diamond', 'gold', 'slime', 'hay', 'beacon', 'lantern', 'ice'],
  gadget: ['firework', 'grapple', 'paintball', 'confetti', 'rocket', 'pearl'],
  pet: ['dog', 'cat', 'fox', 'rabbit', 'pig', 'chicken', 'bee', 'axolotl'],
};
const ALL_COSMETICS = new Set(Object.entries(COSMETICS).flatMap(([cat, ids]) => ids.map((id) => `${cat}.${id}`)));
// Ab Werkseinstellung nur mit dem Recht lobby.cosmetics.premium (z. B. für eine VIP-Rolle)
const DEFAULT_PREMIUM = ['trail.rainbow', 'trail.souls', 'hat.diamond', 'hat.beacon', 'gadget.rocket', 'pet.fox', 'pet.axolotl'];

export const NPC_ACTIONS = ['none', 'menu', 'cosmetics', 'server', 'parkour', 'command'];

const ID = /^[a-z0-9_-]{1,32}$/;
const MAX_POINTS = 100;

const empty = () => ({
  holograms: {},
  npcs: {},
  signs: {},
  parkours: {},
  cosmetics: { enabled: true, disabled: [], premium: [...DEFAULT_PREMIUM] },
  times: {},
});

function num(value, name) {
  const n = Number(value);
  if (!Number.isFinite(n) || Math.abs(n) > 30_000_000) throw new UserError(`${name}: ungültige Zahl`);
  return Math.round(n * 1000) / 1000;
}

function text(value, max = 1000) {
  return String(value ?? '').replace(/\r/g, '').slice(0, max);
}

function loc(raw, name = 'Position') {
  if (!raw || typeof raw !== 'object') throw new UserError(`${name} fehlt`);
  const world = text(raw.world, 64);
  if (!/^[a-zA-Z0-9_\-/:.]+$/.test(world)) throw new UserError(`${name}: ungültige Welt`);
  const out = { world, x: num(raw.x, 'x'), y: num(raw.y, 'y'), z: num(raw.z, 'z') };
  if (raw.yaw !== undefined) out.yaw = num(raw.yaw, 'yaw');
  if (raw.pitch !== undefined) out.pitch = num(raw.pitch, 'pitch');
  return out;
}

const optLoc = (raw, name) => (raw ? loc(raw, name) : null);

/** Ganze Einträge prüfen (vom Plugin) – unbekannte Felder fallen weg. */
const CLEAN = {
  holograms: (d) => ({
    location: loc(d.location),
    text: text(d.text, 2000),
    text_en: text(d.text_en, 2000),
    scale: Math.min(5, Math.max(0.2, Number(d.scale) || 1)),
    background: d.background !== false,
  }),
  npcs: (d) => {
    const type = text(d.type || 'PLAYER', 40).toUpperCase();
    if (!/^[A-Z_]+$/.test(type)) throw new UserError('NPC: ungültiger Typ');
    const action = NPC_ACTIONS.includes(d.action) ? d.action : 'none';
    return {
      location: loc(d.location),
      type,
      skin: text(d.skin, 16).replace(/[^A-Za-z0-9_]/g, ''),
      name: text(d.name, 300),
      name_en: text(d.name_en, 300),
      action,
      target: text(d.target, 200),
      look: d.look !== false,
    };
  },
  signs: (d) => {
    const server = text(d.server, 32);
    if (!ID.test(server)) throw new UserError('Schild: ungültige Server-ID');
    return { location: loc(d.location), server };
  },
  parkours: (d) => {
    const checkpoints = Array.isArray(d.checkpoints) ? d.checkpoints.slice(0, MAX_POINTS).map((c) => loc(c, 'Checkpoint')) : [];
    return {
      name: text(d.name, 64) || 'Jump & Run',
      name_en: text(d.name_en, 64),
      start: loc(d.start, 'Start'),
      checkpoints,
      finish: optLoc(d.finish, 'Ziel'),
      leaderboard: optLoc(d.leaderboard, 'Bestenliste'),
    };
  },
};

/** Felder, die das Dashboard ändern darf (Positionen nur im Spiel). */
const EDITABLE = {
  holograms: ['text', 'text_en', 'scale', 'background'],
  npcs: ['type', 'skin', 'name', 'name_en', 'action', 'target', 'look'],
  signs: ['server'],
  parkours: ['name', 'name_en'],
};

export class LobbyExtras {
  constructor(file) {
    this.file = file;
    this.data = empty();
    this.version = 1;
    this.writing = Promise.resolve();
  }

  async load() {
    try {
      const saved = JSON.parse(await fs.readFile(this.file, 'utf8'));
      const base = empty();
      this.data = { ...base, ...saved, cosmetics: { ...base.cosmetics, ...saved.cosmetics } };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
    }
    this.version = Date.now();
  }

  save() {
    this.version = Date.now();
    const snapshot = JSON.stringify(this.data, null, 2);
    this.writing = this.writing.then(async () => {
      await fs.mkdir(path.dirname(this.file), { recursive: true });
      await fs.writeFile(`${this.file}.tmp`, snapshot);
      await fs.rename(`${this.file}.tmp`, this.file);
    });
    return this.writing;
  }

  static checkKind(kind) {
    if (!KINDS.includes(kind)) throw new UserError('Unbekannte Art');
  }

  static checkId(id) {
    if (!ID.test(id)) throw new UserError('Name: nur a–z, 0–9, _ und - (max. 32 Zeichen)');
  }

  /** Vom Plugin: ganzen Eintrag anlegen/ersetzen oder löschen (data = null). */
  async put(kind, id, data) {
    LobbyExtras.checkKind(kind);
    LobbyExtras.checkId(id);
    if (data === null) {
      delete this.data[kind][id];
      if (kind === 'parkours') delete this.data.times[id];
    } else {
      if (!this.data[kind][id] && Object.keys(this.data[kind]).length >= 200) throw new UserError('Zu viele Einträge');
      this.data[kind][id] = CLEAN[kind](data);
    }
    await this.save();
    return this.data[kind][id] ?? null;
  }

  /** Vom Dashboard: nur Texte und Einstellungen ändern. */
  async edit(kind, id, changes) {
    LobbyExtras.checkKind(kind);
    const current = this.data[kind][id];
    if (!current) throw new UserError('Nicht gefunden', 404);
    const next = { ...current };
    for (const key of EDITABLE[kind]) if (changes[key] !== undefined) next[key] = changes[key];
    this.data[kind][id] = CLEAN[kind](next);
    await this.save();
    return this.data[kind][id];
  }

  async remove(kind, id) {
    LobbyExtras.checkKind(kind);
    if (!this.data[kind][id]) throw new UserError('Nicht gefunden', 404);
    await this.put(kind, id, null);
  }

  async setCosmetics(body) {
    const pick = (list) => (Array.isArray(list) ? [...new Set(list.filter((k) => ALL_COSMETICS.has(k)))] : []);
    this.data.cosmetics = {
      enabled: body.enabled !== false,
      disabled: pick(body.disabled),
      premium: pick(body.premium),
    };
    await this.save();
    return this.data.cosmetics;
  }

  async resetTimes(parkour) {
    delete this.data.times[parkour];
    await this.save();
  }

  /** Bestzeiten einer Strecke, schnellste zuerst. */
  ranking(parkour) {
    return Object.entries(this.data.times[parkour] ?? {})
      .map(([uuid, t]) => ({ uuid, ...t }))
      .sort((a, b) => a.ms - b.ms);
  }

  /** Zeit eines Spielers melden; gespeichert wird nur eine neue Bestzeit. */
  async submitTime({ parkour, uuid, name, ms }) {
    if (!this.data.parkours[parkour]) throw new UserError('Jump & Run nicht gefunden', 404);
    uuid = String(uuid ?? '').toLowerCase();
    if (!/^[0-9a-f-]{32,36}$/.test(uuid)) throw new UserError('Ungültige UUID');
    ms = Math.round(Number(ms));
    if (!Number.isFinite(ms) || ms < 500 || ms > 24 * 3600_000) throw new UserError('Ungültige Zeit');
    const times = (this.data.times[parkour] ??= {});
    const previous = times[uuid]?.ms ?? null;
    const improved = previous === null || ms < previous;
    const recordBefore = this.ranking(parkour)[0]?.ms ?? null;
    if (improved) {
      times[uuid] = { name: text(name, 16), ms, at: new Date().toISOString() };
      await this.save();
    } else if (times[uuid].name !== name) {
      times[uuid].name = text(name, 16);
    }
    const ranking = this.ranking(parkour);
    return {
      improved,
      previous,
      best: times[uuid].ms,
      rank: ranking.findIndex((t) => t.uuid === uuid) + 1,
      record: improved && (recordBefore === null || ms < recordBefore),
    };
  }

  /** Alles, was das Lobby-Plugin braucht (ohne alle Zeiten, nur die Top 10). */
  forPlugin() {
    const leaderboards = {};
    for (const id of Object.keys(this.data.parkours)) {
      leaderboards[id] = this.ranking(id).slice(0, 10).map(({ name, ms }) => ({ name, ms }));
    }
    const { holograms, npcs, signs, parkours, cosmetics } = this.data;
    return { version: this.version, holograms, npcs, signs, parkours, cosmetics, leaderboards };
  }

  forDashboard() {
    const counts = {};
    const top = {};
    for (const id of Object.keys(this.data.parkours)) {
      const ranking = this.ranking(id);
      counts[id] = ranking.length;
      top[id] = ranking.slice(0, 10).map(({ name, ms, at }) => ({ name, ms, at }));
    }
    const { holograms, npcs, signs, parkours, cosmetics } = this.data;
    return { holograms, npcs, signs, parkours, cosmetics, catalog: COSMETICS, actions: NPC_ACTIONS, top, counts };
  }
}
