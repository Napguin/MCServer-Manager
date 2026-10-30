import fs from 'node:fs/promises';
import path from 'node:path';
import Docker from 'dockerode';
import YAML from 'yaml';
import { ensurePlugin } from './modrinth.js';

const IMAGE = 'itzg/minecraft-server:latest';
export const MC_UID = 1000; // Benutzer, unter dem das itzg-Image Minecraft ausführt

/**
 * Unterstützte Servertypen. "loaders" sind die Modrinth-Kategorien, die auf dem Typ laufen.
 * Paper, Purpur und Folia nutzen die Velocity-Weiterleitung über paper-global.yml,
 * Fabric über die Mod FabricProxy-Lite.
 */
export const SERVER_TYPES = {
  PAPER: { label: 'Paper', contentDir: 'plugins', projectType: 'plugin', loaders: ['paper', 'spigot', 'bukkit'] },
  PURPUR: { label: 'Purpur', contentDir: 'plugins', projectType: 'plugin', loaders: ['purpur', 'paper', 'spigot', 'bukkit'] },
  FOLIA: { label: 'Folia', contentDir: 'plugins', projectType: 'plugin', loaders: ['folia'] },
  FABRIC: { label: 'Fabric', contentDir: 'mods', projectType: 'mod', loaders: ['fabric'] },
};

export function serverType(server) {
  return SERVER_TYPES[server.type] ?? SERVER_TYPES.PAPER;
}

/**
 * Version der Container-Einstellungen. Wird sie erhöht, erstellt das Dashboard
 * ältere Container beim nächsten Start neu (die Daten im Serverordner bleiben).
 */
export function containerRev(server) {
  // Lobby: 3 = Lobby-System (friedlich, kein Nether, Abenteuermodus)
  // 4 bzw. 3 = Dashboard-Zugang für alle Server (Vanish, Mutes, Admin-GUI)
  return server.id === 'lobby' ? 4 : 3;
}

/** EssentialsX gibt es nur für Paper/Purpur (nicht Folia/Fabric) und nicht in der Lobby. */
export function wantsEssentials(server) {
  return server.id !== 'lobby' && ['PAPER', 'PURPUR', undefined].includes(server.type);
}

/** LuckPerms erlaubt in Server-Namen (Kontexten) nur a-z und 0-9. */
export function luckpermsServerName(id) {
  return id.replace(/[^a-z0-9]/g, '');
}

/**
 * Erstellt, startet und stoppt die Minecraft-Server als Docker-Container.
 * Jeder Server bekommt einen Container "mc-<id>" im gemeinsamen Netzwerk,
 * damit Velocity ihn unter mc-<id>:25565 erreicht. Nach außen ist nur Velocity offen.
 */
export class Minecraft {
  constructor({ dataDir, network, forwardingSecret, pluginDir, dashboardUrl, apiKey, dbPassword, store }) {
    this.docker = new Docker({ socketPath: '/var/run/docker.sock' });
    this.dbPassword = dbPassword;
    this.dataDir = dataDir;
    this.network = network;
    this.forwardingSecret = forwardingSecret;
    this.pluginDir = pluginDir;
    this.dashboardUrl = dashboardUrl;
    this.apiKey = apiKey;
    this.store = store;
    this.provisioning = new Set();
  }

  containerName(id) {
    return `mc-${id}`;
  }

  serverDir(id) {
    return path.join(this.dataDir, 'servers', id);
  }

  container(id) {
    return this.docker.getContainer(this.containerName(id));
  }

  async state(id) {
    try {
      const info = await this.container(id).inspect();
      return info.State.Running ? 'running' : 'stopped';
    } catch (err) {
      if (err.statusCode === 404) return 'missing';
      throw err;
    }
  }

  async ensureImage() {
    try {
      await this.docker.getImage(IMAGE).inspect();
    } catch {
      console.log(`Lade Docker-Image ${IMAGE} ...`);
      const stream = await this.docker.pull(IMAGE);
      await new Promise((resolve, reject) =>
        this.docker.modem.followProgress(stream, (err) => (err ? reject(err) : resolve())),
      );
    }
  }

  async create(server) {
    await this.ensureImage();
    const dir = this.serverDir(server.id);
    const type = server.type in SERVER_TYPES ? server.type : 'PAPER';
    await fs.mkdir(path.join(dir, serverType(server).contentDir), { recursive: true });

    if (type === 'FABRIC') {
      // FabricProxy-Lite liest das Secret beim ersten Start aus dieser Datei
      const configFile = path.join(dir, 'config', 'FabricProxy-Lite.toml');
      if (!(await exists(configFile))) {
        await fs.mkdir(path.dirname(configFile), { recursive: true });
        await fs.writeFile(configFile, [
          'hackOnlineMode = true',
          'hackEarlySend = false',
          'hackMessageChain = true',
          'disconnectMessage = "Bitte verbinde dich über den Proxy."',
          `secret = "${this.forwardingSecret}"`,
          '',
        ].join('\n'));
      }
    }
    await chownRecursive(dir);

    const env = [
      'EULA=TRUE',
      `TYPE=${type}`,
      `VERSION=${server.version || 'LATEST'}`,
      `MEMORY=${server.memory || '2G'}`,
      'ONLINE_MODE=FALSE', // Login prüft Velocity, der Server vertraut der Weiterleitung
      `MOTD=${server.name}`,
      'ENABLE_RCON=TRUE',
      'TZ=Europe/Berlin',
      // LuckPerms: gemeinsame Datenbank, Änderungen werden über die Datenbank verteilt
      'LUCKPERMS_STORAGE_METHOD=mariadb',
      'LUCKPERMS_DATA_ADDRESS=mariadb:3306',
      'LUCKPERMS_DATA_DATABASE=luckperms',
      'LUCKPERMS_DATA_USERNAME=luckperms',
      `LUCKPERMS_DATA_PASSWORD=${this.dbPassword}`,
      'LUCKPERMS_MESSAGING_SERVICE=sql',
      `LUCKPERMS_SERVER=${luckpermsServerName(server.id)}`,
      // Für die Netzwerk-Plugins: Spielerliste, Mutes, Admin-GUI
      `DASHBOARD_URL=${this.dashboardUrl}`,
      `DASHBOARD_API_KEY=${this.apiKey}`,
      `NETWORK_SERVER_ID=${server.id}`,
    ];
    if (server.id === 'lobby') {
      env.push(
        // Reine Lobby: friedlich, keine Mobs, kein Nether/End, Abenteuermodus.
        // ALLOW_FLIGHT verhindert Kicks durch Doppelsprung und Launchpads.
        'DIFFICULTY=peaceful',
        'MODE=adventure',
        'FORCE_GAMEMODE=TRUE',
        'ALLOW_NETHER=FALSE',
        'ALLOW_FLIGHT=TRUE',
        'SPAWN_PROTECTION=0',
        'SPAWN_MONSTERS=FALSE',
        'SPAWN_ANIMALS=FALSE',
        'SPAWN_NPCS=FALSE',
        'GENERATE_STRUCTURES=FALSE',
        'PVP=FALSE',
        'VIEW_DISTANCE=6',
      );
    }
    if (type === 'FABRIC') {
      // Fabric API, die Proxy-Mod und LuckPerms werden bei jedem Start passend zur Version installiert
      env.push('MODRINTH_PROJECTS=fabric-api,fabricproxy-lite,luckperms', `FABRIC_PROXY_SECRET=${this.forwardingSecret}`);
    } else {
      env.push('MODRINTH_PROJECTS=luckperms');
    }

    await this.docker.createContainer({
      name: this.containerName(server.id),
      Hostname: this.containerName(server.id),
      Image: IMAGE,
      Env: env,
      Tty: true,
      OpenStdin: true,
      Labels: { 'mcnetwork.managed': 'true', 'mcnetwork.server': server.id },
      HostConfig: {
        Binds: [`${dir}:/data`],
        NetworkMode: this.network,
        RestartPolicy: { Name: 'unless-stopped' },
      },
    });
    await this.store.update(server.id, { containerRev: containerRev(server) });
  }

  async start(server) {
    const state = await this.state(server.id);
    if (state === 'stopped' && server.containerRev !== containerRev(server)) {
      // Veralteter Container: neu erstellen, damit neue Einstellungen greifen
      await this.container(server.id).remove({ force: true });
      await this.create(server);
    } else if (state === 'missing') {
      await this.create(server);
    }
    const container = this.container(server.id);
    const info = await container.inspect();
    if (!info.State.Running) {
      // Plugins nur bei gestopptem Server austauschen, sonst stürzt Paper ab
      await this.installSystemPlugins(server);
      await container.start();
    }
    if (!server.provisioned) this.provision(server.id);
  }

  /** Erstellt einen laufenden Container mit veralteten Einstellungen neu. */
  async upgrade(server) {
    if (server.containerRev === containerRev(server)) return false;
    const state = await this.state(server.id);
    if (state === 'missing') return false;
    console.log(`Aktualisiere Container von ${server.id} ...`);
    if (state === 'running') await this.stop(server.id);
    await this.start(server);
    return true;
  }

  async stop(id) {
    const container = this.container(id);
    const info = await container.inspect();
    if (info.State.Running) await container.stop({ t: 30 });
  }

  async restart(server) {
    if ((await this.state(server.id)) === 'running') await this.stop(server.id);
    await this.start(server);
  }

  async remove(id, deleteFiles) {
    if ((await this.state(id)) !== 'missing') {
      await this.container(id).remove({ force: true });
    }
    if (deleteFiles) {
      await fs.rm(this.serverDir(id), { recursive: true, force: true });
    }
  }

  /**
   * Eigene Plugins des Netzwerks: Kompass-Menü auf der Lobby,
   * Chat/Tab-Prefixe auf allen Plugin-Servern.
   */
  async installSystemPlugins(server) {
    const jars = [];
    if (server.id === 'lobby') jars.push('lobby-selector.jar');
    if (serverType(server).contentDir === 'plugins') jars.push('network-chat.jar');

    // EssentialsX direkt von Modrinth (das Docker-Image lehnt es ab, solange die
    // Minecraft-Version offiziell noch nicht freigegeben ist)
    if (wantsEssentials(server)) {
      try {
        const dir = path.join(this.serverDir(server.id), 'plugins');
        const installed = await ensurePlugin(dir, 'hXiIvTyT', ['paper'], 'EssentialsX');
        if (installed) {
          await fs.chown(installed, MC_UID, MC_UID).catch(() => {});
          console.log(`EssentialsX auf ${server.id} installiert.`);
        }
      } catch (err) {
        console.error(`EssentialsX konnte auf ${server.id} nicht installiert werden:`, err.message);
      }
    }
    // LuckPerms installiert jetzt das Docker-Image. Eine früher von Hand
    // installierte Kopie entfernen, sonst wäre das Plugin doppelt vorhanden.
    const manual = server.modrinth?.Vebnzrzj;
    if (manual) {
      const dir = path.join(this.serverDir(server.id), serverType(server).contentDir);
      await fs.rm(path.join(dir, path.basename(manual.file)), { force: true });
      const { Vebnzrzj, ...rest } = server.modrinth;
      await this.store.update(server.id, { modrinth: rest });
    }

    for (const jar of jars) {
      const source = path.join(this.pluginDir, jar);
      const target = path.join(this.serverDir(server.id), 'plugins', jar);
      try {
        await fs.copyFile(source, target);
        await fs.chown(target, MC_UID, MC_UID);
      } catch (err) {
        console.error(`${jar} konnte nicht nach ${server.id} kopiert werden:`, err.message);
      }
    }
  }

  /**
   * Paper legt seine Konfiguration erst beim ersten Start an. Wir warten darauf,
   * schalten dann die Velocity-Weiterleitung ein und starten den Server neu.
   */
  async provision(id) {
    if (this.provisioning.has(id)) return;
    if (this.store.get(id)?.type === 'FABRIC') {
      // Fabric ist schon beim Erstellen eingerichtet (FabricProxy-Lite)
      await this.store.update(id, { provisioned: true });
      return;
    }
    this.provisioning.add(id);
    const file = path.join(this.serverDir(id), 'config', 'paper-global.yml');
    try {
      const deadline = Date.now() + 15 * 60 * 1000;
      while (!(await exists(file))) {
        if (Date.now() > deadline) throw new Error('paper-global.yml wurde nicht erstellt');
        if (!this.store.get(id)) return; // inzwischen gelöscht
        await sleep(3000);
      }
      await sleep(5000); // Paper fertig schreiben lassen

      const doc = YAML.parseDocument(await fs.readFile(file, 'utf8'));
      doc.setIn(['proxies', 'velocity', 'enabled'], true);
      doc.setIn(['proxies', 'velocity', 'online-mode'], true);
      doc.setIn(['proxies', 'velocity', 'secret'], this.forwardingSecret);
      await fs.writeFile(file, doc.toString());

      await this.store.update(id, { provisioned: true });
      const server = this.store.get(id);
      if (server) await this.restart(server);
      console.log(`Server ${id} ist eingerichtet (Velocity-Weiterleitung aktiv).`);
    } catch (err) {
      console.error(`Einrichtung von ${id} fehlgeschlagen:`, err.message);
    } finally {
      this.provisioning.delete(id);
    }
  }

  /** Liest die tatsächlich laufende Minecraft-Version aus dem Server-Log (wichtig bei "LATEST"). */
  async detectVersion(id) {
    try {
      const log = await fs.readFile(path.join(this.serverDir(id), 'logs', 'latest.log'), 'utf8');
      return log.match(/Starting minecraft server version (\S+)/)?.[1] ?? null;
    } catch {
      return null;
    }
  }

  async logs(id, tail = 300) {
    const buffer = await this.container(id).logs({ stdout: true, stderr: true, tail, follow: false });
    return stripAnsi(buffer.toString('utf8'));
  }

  async command(id, command) {
    const exec = await this.container(id).exec({
      Cmd: ['rcon-cli', command],
      AttachStdout: true,
      AttachStderr: true,
      Tty: true,
    });
    const stream = await exec.start({ hijack: true, stdin: false, Tty: true });
    const chunks = [];
    await new Promise((resolve, reject) => {
      stream.on('data', (chunk) => chunks.push(chunk));
      stream.on('end', resolve);
      stream.on('error', reject);
    });
    return stripAnsi(Buffer.concat(chunks).toString('utf8'));
  }

  // ----- Velocity-Proxy (läuft per docker compose, wir lesen nur Logs / starten neu) -----

  async proxyLogs(tail = 300) {
    const buffer = await this.docker.getContainer('mc-velocity').logs({ stdout: true, stderr: true, tail });
    return stripAnsi(buffer.toString('utf8'));
  }

  async proxyRestart() {
    await this.docker.getContainer('mc-velocity').restart({ t: 10 });
  }
}

async function exists(file) {
  try {
    await fs.access(file);
    return true;
  } catch {
    return false;
  }
}

export async function chownRecursive(dir) {
  await fs.chown(dir, MC_UID, MC_UID);
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) await chownRecursive(full);
    else await fs.chown(full, MC_UID, MC_UID);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function stripAnsi(text) {
  // eslint-disable-next-line no-control-regex
  return text.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '').replace(/\r/g, '');
}
