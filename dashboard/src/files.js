import fs from 'node:fs/promises';
import path from 'node:path';
import { MC_UID, serverType } from './minecraft.js';

const EDITABLE = new Set(['.yml', '.yaml', '.json', '.json5', '.toml', '.properties', '.txt', '.conf', '.cfg', '.ini', '.md', '.mcmeta', '.secret']);
const MAX_EDIT_SIZE = 1024 * 1024;

export class UserError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

/** Dateizugriff auf die Ordner der Server: Plugins/Mods und Config-Editor. */
export class ServerFiles {
  constructor(mc, store) {
    this.mc = mc;
    this.store = store;
  }

  /** Löst einen relativen Pfad auf und verhindert, dass man aus dem Serverordner ausbricht. */
  resolve(id, rel = '') {
    const root = path.resolve(this.mc.serverDir(id));
    const full = path.resolve(root, String(rel).replace(/^[/\\]+/, ''));
    if (full !== root && !full.startsWith(root + path.sep)) throw new UserError('Ungültiger Pfad');
    return full;
  }

  contentDir(server) {
    return this.resolve(server.id, serverType(server).contentDir);
  }

  // ---------- Plugins / Mods ----------

  async listContent(server) {
    const dir = this.contentDir(server);
    await fs.mkdir(dir, { recursive: true });
    const sources = Object.entries(server.modrinth ?? {});
    const files = [];
    for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.jar')) continue;
      const stat = await fs.stat(path.join(dir, entry.name));
      const source = sources.find(([, info]) => info.file === entry.name);
      files.push({
        name: entry.name,
        size: stat.size,
        modified: stat.mtime.toISOString(),
        modrinth: source ? { projectId: source[0], ...source[1] } : null,
        // Vom System verwaltet: wird beim Start automatisch installiert, Löschen wäre sinnlos
        managed: /^(luckperms|essentialsx|fabric-api|fabricproxy-lite|network-chat\.jar|lobby-selector\.jar)/i.test(entry.name),
      });
    }
    return files.sort((a, b) => a.name.localeCompare(b.name));
  }

  checkJarName(filename) {
    const name = path.basename(String(filename ?? ''));
    if (!/^[\w.\-+ ()[\]]{1,120}\.jar$/i.test(name)) throw new UserError('Nur .jar-Dateien mit normalem Dateinamen erlaubt');
    return name;
  }

  async saveContent(server, filename, buffer) {
    const name = this.checkJarName(filename);
    if (buffer.length < 4 || buffer.readUInt32BE(0) !== 0x504b0304) throw new UserError('Das ist keine gültige .jar-Datei');
    const dir = this.contentDir(server);
    await fs.mkdir(dir, { recursive: true });
    const target = path.join(dir, name);
    await fs.writeFile(target, buffer);
    await fs.chown(target, MC_UID, MC_UID).catch(() => {});
    return name;
  }

  async deleteContent(server, filename) {
    const name = this.checkJarName(filename);
    await fs.rm(path.join(this.contentDir(server), name), { force: true });
    const modrinth = { ...(server.modrinth ?? {}) };
    for (const [projectId, info] of Object.entries(modrinth)) {
      if (info.file === name) delete modrinth[projectId];
    }
    await this.store.update(server.id, { modrinth });
  }

  // ---------- Datei-Editor ----------

  async listDir(id, rel) {
    const dir = this.resolve(id, rel);
    let entries;
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch (err) {
      if (err.code === 'ENOENT' || err.code === 'ENOTDIR') throw new UserError('Ordner nicht gefunden', 404);
      throw err;
    }
    const list = [];
    for (const entry of entries) {
      if (!entry.isDirectory() && !entry.isFile()) continue;
      const stat = await fs.stat(path.join(dir, entry.name)).catch(() => null);
      if (!stat) continue;
      list.push({
        name: entry.name,
        type: entry.isDirectory() ? 'dir' : 'file',
        size: stat.size,
        editable: entry.isFile() && isEditable(entry.name) && stat.size <= MAX_EDIT_SIZE,
      });
    }
    return list.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1));
  }

  async readText(id, rel) {
    const file = this.resolve(id, rel);
    if (!isEditable(file)) throw new UserError('Dieser Dateityp kann nicht bearbeitet werden');
    const stat = await fs.stat(file).catch(() => null);
    if (!stat?.isFile()) throw new UserError('Datei nicht gefunden', 404);
    if (stat.size > MAX_EDIT_SIZE) throw new UserError('Datei ist zu groß für den Editor');
    return fs.readFile(file, 'utf8');
  }

  async writeText(id, rel, content) {
    const file = this.resolve(id, rel);
    if (!isEditable(file)) throw new UserError('Dieser Dateityp kann nicht bearbeitet werden');
    if (typeof content !== 'string' || Buffer.byteLength(content) > MAX_EDIT_SIZE) throw new UserError('Inhalt zu groß');
    const stat = await fs.stat(file).catch(() => null);
    if (!stat?.isFile()) throw new UserError('Datei nicht gefunden', 404);
    await fs.writeFile(file, content);
  }
}

function isEditable(name) {
  return EDITABLE.has(path.extname(name).toLowerCase());
}
