import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { UserError } from './files.js';

export const MOTD_DEFAULTS = {
  line1: '<gradient:gold:yellow><bold><network></bold></gradient>',
  line2: '<gray>Lobby · Survival · und mehr',
  // Hover über der Spielerzahl; leer = Liste der Spieler, die online sind
  hover: '',
  // 0 = Wert aus velocity.toml
  maxPlayers: 0,
  icon: '', // data:image/png;base64,... (64×64)
  maintenance: false,
  maintenanceLine2: '<red><bold>Wartungsarbeiten</bold> <gray>– wir sind gleich zurück!',
  maintenanceKick: 'Der Server ist gerade im Wartungsmodus. Bitte versuche es später noch einmal.\n\nThe server is under maintenance. Please try again later.',
};

/** MOTD, Server-Icon und Wartungsmodus – das Velocity-Plugin zeigt sie in der Serverliste an. */
export class MotdSettings {
  constructor(file) {
    this.file = file;
    this.settings = { ...MOTD_DEFAULTS };
    this.version = '';
  }

  async load() {
    try {
      this.settings = { ...MOTD_DEFAULTS, ...JSON.parse(await fs.readFile(this.file, 'utf8')) };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
    }
    this.updateVersion();
  }

  /** Kurzer Hash, damit der Proxy nur bei Änderungen neu lädt (das Icon ist recht groß). */
  updateVersion() {
    this.version = crypto.createHash('sha1').update(JSON.stringify(this.settings)).digest('hex').slice(0, 12);
  }

  get() {
    return this.settings;
  }

  async update(body) {
    const next = { ...this.settings };
    for (const key of ['line1', 'line2', 'hover', 'maintenanceLine2', 'maintenanceKick']) {
      if (body[key] === undefined) continue;
      const value = String(body[key]).replace(/\r/g, '');
      if (value.length > 1000) throw new UserError(`${key}: zu lang`);
      next[key] = value;
    }
    if (/\n/.test(next.line1) || /\n/.test(next.line2)) throw new UserError('Die MOTD-Zeilen dürfen keine Zeilenumbrüche enthalten');
    if (next.hover.split('\n').length > 15) throw new UserError('Hover-Text: höchstens 15 Zeilen');
    if (body.maxPlayers !== undefined) {
      const n = Number.parseInt(body.maxPlayers, 10);
      if (!Number.isInteger(n) || n < 0 || n > 100000) throw new UserError('Maximale Spieler: Zahl von 0 bis 100000');
      next.maxPlayers = n;
    }
    if (body.maintenance !== undefined) next.maintenance = !!body.maintenance;
    if (body.icon !== undefined) next.icon = checkIcon(body.icon);

    await fs.mkdir(path.dirname(this.file), { recursive: true });
    await fs.writeFile(`${this.file}.tmp`, JSON.stringify(next, null, 2));
    await fs.rename(`${this.file}.tmp`, this.file);
    this.settings = next;
    this.updateVersion();
    return next;
  }
}

/** Minecraft akzeptiert nur PNG mit genau 64×64 Pixeln (das Dashboard skaliert vorher). */
function checkIcon(icon) {
  if (!icon) return '';
  const match = /^data:image\/png;base64,([A-Za-z0-9+/=]+)$/.exec(String(icon));
  if (!match) throw new UserError('Das Icon muss ein PNG-Bild sein');
  const png = Buffer.from(match[1], 'base64');
  if (png.length > 64 * 1024) throw new UserError('Das Icon ist zu groß (max. 64 KB)');
  const isPng = png.length > 24 && png.readUInt32BE(0) === 0x89504e47;
  if (!isPng || png.readUInt32BE(16) !== 64 || png.readUInt32BE(20) !== 64) {
    throw new UserError('Das Icon muss genau 64×64 Pixel groß sein');
  }
  return icon;
}
