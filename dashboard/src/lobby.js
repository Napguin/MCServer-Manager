import fs from 'node:fs/promises';
import path from 'node:path';
import { UserError } from './files.js';

// Gleiche Standardwerte wie im Lobby-Plugin (LobbySettings.defaults)
export const LOBBY_DEFAULTS = {
  networkName: 'Mein Netzwerk',
  welcomeTitle: '<gold><bold>Willkommen!',
  welcomeSubtitle: '<gray>Wähle mit dem Kompass einen Server',
  hologram: '<gold><bold><network></bold></gold>\n<gray>Rechtsklick auf den Kompass, um einen Server zu wählen',
  scoreboardTitle: '<gold><bold><network>',
  scoreboardLines: ' \n<white>Rang: <rank>\n<white>Online: <green><online>\n  \n<white>Server: <aqua><servers>',
  // Tab-Liste und Chat gelten im ganzen Netzwerk (setzt das Velocity-Plugin)
  tabHeader: '\n<gold><bold><network></bold></gold>\n<gray>Du bist auf <white><server></white> <dark_gray>·</dark_gray> <green><online></green> <gray>online\n',
  tabFooter: '\n<servers_list>\n\n<gray>Ping: <white><ping> ms\n',
  tabEntry: '<player_display> <dark_gray>[<gray><server></gray>]',
  globalChat: true,
  bossBar: '',
  doubleJump: true,
  playerHider: true,
  launchPads: true,
  hideJoinMessages: true,
  voidTeleport: true,

  // Englische Texte für Spieler, deren Minecraft nicht auf Deutsch steht (leer = deutscher Text)
  welcomeTitle_en: '<gold><bold>Welcome!',
  welcomeSubtitle_en: '<gray>Use the compass to pick a server',
  hologram_en: '<gold><bold><network></bold></gold>\n<gray>Right-click the compass to choose a server',
  scoreboardTitle_en: '<gold><bold><network>',
  scoreboardLines_en: ' \n<white>Rank: <rank>\n<white>Online: <green><online>\n  \n<white>Servers: <aqua><servers>',
  tabHeader_en: '\n<gold><bold><network></bold></gold>\n<gray>You are on <white><server></white> <dark_gray>·</dark_gray> <green><online></green> <gray>online\n',
  tabFooter_en: '\n<servers_list>\n\n<gray>Ping: <white><ping> ms\n',
  tabEntry_en: '<player_display> <dark_gray>[<gray><server></gray>]',
  bossBar_en: '',
};

/** Texte, die es auf Deutsch und Englisch gibt (englisch: "<feld>_en"). */
export const TRANSLATABLE = ['welcomeTitle', 'welcomeSubtitle', 'hologram', 'scoreboardTitle', 'scoreboardLines', 'tabHeader', 'tabFooter', 'tabEntry', 'bossBar'];
const TEXT_FIELDS = ['networkName', ...TRANSLATABLE, ...TRANSLATABLE.map((k) => `${k}_en`)];
const BOOL_FIELDS = ['doubleJump', 'playerHider', 'launchPads', 'hideJoinMessages', 'voidTeleport', 'globalChat'];

/** Einstellungen der Lobby, gespeichert als JSON-Datei neben servers.json. */
export class LobbySettings {
  constructor(file) {
    this.file = file;
    this.settings = { ...LOBBY_DEFAULTS };
  }

  async load() {
    try {
      const saved = JSON.parse(await fs.readFile(this.file, 'utf8'));
      // Alte Standard-Tabtexte (nur Lobby) durch die neuen netzwerkweiten ersetzen
      if (saved.tabHeader === '\n<gold><bold><network></bold></gold>\n') delete saved.tabHeader;
      if (saved.tabFooter === '\n<gray>Online: <green><online>\n') delete saved.tabFooter;
      this.settings = { ...LOBBY_DEFAULTS, ...saved };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
    }
  }

  get() {
    return this.settings;
  }

  async update(body) {
    const next = { ...this.settings };
    for (const key of TEXT_FIELDS) {
      if (body[key] === undefined) continue;
      const value = String(body[key]).replace(/\r/g, '');
      if (value.length > 2000) throw new UserError(`${key}: zu lang (max. 2000 Zeichen)`);
      next[key] = value;
    }
    for (const key of BOOL_FIELDS) {
      if (body[key] !== undefined) next[key] = !!body[key];
    }
    if (next.scoreboardLines.split('\n').length > 15 || next.scoreboardLines_en.split('\n').length > 15) {
      throw new UserError('Scoreboard: höchstens 15 Zeilen');
    }
    if (!next.networkName.trim()) throw new UserError('Bitte einen Netzwerk-Namen angeben');

    await fs.mkdir(path.dirname(this.file), { recursive: true });
    await fs.writeFile(`${this.file}.tmp`, JSON.stringify(next, null, 2));
    await fs.rename(`${this.file}.tmp`, this.file);
    this.settings = next;
    return next;
  }
}
