import fs from 'node:fs/promises';
import path from 'node:path';

// Sehr einfache JSON-Datei als Speicher. Für ein paar Dutzend Server völlig ausreichend.
export class Store {
  constructor(file) {
    this.file = file;
    this.servers = [];
    this.writing = Promise.resolve();
  }

  async load() {
    try {
      this.servers = JSON.parse(await fs.readFile(this.file, 'utf8')).servers ?? [];
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
      this.servers = [];
    }
  }

  save() {
    // Schreibvorgänge nacheinander ausführen, damit sich nichts überschreibt
    this.writing = this.writing.then(async () => {
      await fs.mkdir(path.dirname(this.file), { recursive: true });
      const tmp = `${this.file}.tmp`;
      await fs.writeFile(tmp, JSON.stringify({ servers: this.servers }, null, 2));
      await fs.rename(tmp, this.file);
    });
    return this.writing;
  }

  get(id) {
    return this.servers.find((s) => s.id === id);
  }

  async add(server) {
    this.servers.push(server);
    await this.save();
  }

  async update(id, changes) {
    const server = this.get(id);
    if (!server) return null;
    Object.assign(server, changes);
    await this.save();
    return server;
  }

  async remove(id) {
    this.servers = this.servers.filter((s) => s.id !== id);
    await this.save();
  }
}
