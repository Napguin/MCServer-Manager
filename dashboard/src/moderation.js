import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { UserError } from './files.js';
import { mojangProfile } from './luckperms.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_DURATION = 10 * 365 * 86400;

/**
 * Netzwerkweite Banns, Mutes und Kicks. Gespeichert in moderation.json;
 * Velocity setzt Banns beim Login durch und führt Kicks aus,
 * die Server-Plugins prüfen Mutes im Chat.
 */
export class Moderation {
  constructor(file, onlinePlayers) {
    this.file = file;
    this.onlinePlayers = onlinePlayers; // () => [{ uuid, name, server, ... }]
    this.data = { bans: [], mutes: [], log: [] };
    this.pendingKicks = [];
  }

  async load() {
    try {
      this.data = { bans: [], mutes: [], log: [], ...JSON.parse(await fs.readFile(this.file, 'utf8')) };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
    }
  }

  async save() {
    await fs.mkdir(path.dirname(this.file), { recursive: true });
    await fs.writeFile(`${this.file}.tmp`, JSON.stringify(this.data, null, 2));
    await fs.rename(`${this.file}.tmp`, this.file);
  }

  static active(entry) {
    return !entry.expires || entry.expires > Date.now();
  }

  activeBans() {
    return this.data.bans.filter(Moderation.active);
  }

  activeMutes() {
    return this.data.mutes.filter(Moderation.active);
  }

  takeKicks() {
    const kicks = this.pendingKicks;
    this.pendingKicks = [];
    return kicks;
  }

  /** Spieler per Name oder UUID finden: erst online, dann in bekannten Einträgen, sonst Mojang. */
  async resolve(target, t = (de) => de) {
    const value = String(target ?? '').trim();
    if (!value) throw new UserError(t('Kein Spieler angegeben', 'No player given'));
    const online = this.onlinePlayers();
    const known = [...online, ...this.data.bans, ...this.data.mutes];
    const match = UUID_RE.test(value)
      ? known.find((p) => p.uuid === value.toLowerCase())
      : known.find((p) => p.name?.toLowerCase() === value.toLowerCase());
    if (match) return { uuid: match.uuid, name: match.name };
    if (UUID_RE.test(value)) return { uuid: value.toLowerCase(), name: value };
    if (!/^[A-Za-z0-9_]{2,16}$/.test(value)) throw new UserError(t('Ungültiger Spielername', 'Invalid player name'));
    const profile = await mojangProfile(value);
    return {
      uuid: profile.id.replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5').toLowerCase(),
      name: profile.name,
    };
  }

  /**
   * Führt eine Aktion aus: kick, ban, unban, mute, unmute.
   * duration in Sekunden, 0 = dauerhaft.
   */
  /** lang = 'en' für englische Fehlermeldungen (Befehle von Spielern mit englischem Minecraft). */
  async apply({ action, target, duration, reason, by, lang }) {
    const t = (de, en) => (lang === 'en' ? en : de);
    const player = await this.resolve(target, t);
    const seconds = Math.min(Math.max(Number(duration) || 0, 0), MAX_DURATION);
    const entry = {
      id: crypto.randomUUID(),
      uuid: player.uuid,
      name: player.name,
      reason: String(reason || '').slice(0, 200) || t('Kein Grund angegeben', 'No reason given'),
      by: String(by || 'Unbekannt').slice(0, 32),
      created: Date.now(),
      expires: seconds ? Date.now() + seconds * 1000 : null,
    };

    switch (action) {
      case 'kick':
        if (!this.onlinePlayers().some((p) => p.uuid === player.uuid)) throw new UserError(t(`${player.name} ist nicht online`, `${player.name} is not online`));
        this.pendingKicks.push({
          uuid: player.uuid,
          reason: `Du wurdest vom Netzwerk gekickt.\nGrund: ${entry.reason}`,
          reason_en: `You were kicked from the network.\nReason: ${entry.reason}`,
        });
        break;
      case 'ban':
        this.data.bans = this.data.bans.filter((b) => b.uuid !== player.uuid);
        this.data.bans.push(entry);
        this.pendingKicks.push({ uuid: player.uuid, reason: banMessage(entry, 'de'), reason_en: banMessage(entry, 'en') });
        break;
      case 'unban':
        if (!this.data.bans.some((b) => b.uuid === player.uuid && Moderation.active(b))) throw new UserError(t(`${player.name} ist nicht gebannt`, `${player.name} is not banned`));
        this.data.bans = this.data.bans.filter((b) => b.uuid !== player.uuid);
        break;
      case 'mute':
        this.data.mutes = this.data.mutes.filter((m) => m.uuid !== player.uuid);
        this.data.mutes.push(entry);
        break;
      case 'unmute':
        if (!this.data.mutes.some((m) => m.uuid === player.uuid && Moderation.active(m))) throw new UserError(t(`${player.name} ist nicht gemutet`, `${player.name} is not muted`));
        this.data.mutes = this.data.mutes.filter((m) => m.uuid !== player.uuid);
        break;
      default:
        throw new UserError(t('Unbekannte Aktion', 'Unknown action'));
    }

    // Verlauf (die letzten 300 Einträge) und abgelaufene Einträge aufräumen
    this.data.log.unshift({ action, uuid: player.uuid, name: player.name, reason: entry.reason, by: entry.by, at: entry.created, expires: entry.expires });
    this.data.log = this.data.log.slice(0, 300);
    this.data.bans = this.data.bans.filter(Moderation.active);
    this.data.mutes = this.data.mutes.filter(Moderation.active);
    await this.save();
    return { ...entry, action };
  }
}

export function banMessage(ban, lang = 'de') {
  if (lang === 'en') {
    const until = ban.expires ? `until ${new Date(ban.expires).toLocaleString('en-GB', { timeZone: 'Europe/Berlin' })}` : 'permanently';
    return `You have been banned from the network ${until}.\nReason: ${ban.reason}`;
  }
  const until = ban.expires ? `bis ${new Date(ban.expires).toLocaleString('de-DE', { timeZone: 'Europe/Berlin' })}` : 'dauerhaft';
  return `Du wurdest ${until} vom Netzwerk gebannt.\nGrund: ${ban.reason}`;
}
