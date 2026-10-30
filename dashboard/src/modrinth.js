import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { serverType } from './minecraft.js';
import { UserError } from './files.js';

const API = 'https://api.modrinth.com/v2';
const HEADERS = { 'User-Agent': 'mcnetwork-dashboard/1.0 (self-hosted)' };
const MAX_DOWNLOAD = 100 * 1024 * 1024;

// Diese Projekte installiert das Docker-Image selbst: LuckPerms überall,
// bei Fabric zusätzlich Fabric API und FabricProxy-Lite
const LUCKPERMS = 'Vebnzrzj';
const MANAGED_BY_IMAGE = new Set([LUCKPERMS, 'P7dR8mSH', '8dI2tmqs']);

/** Lädt die neueste Version eines Projekts in einen Ordner, falls noch keine Datei mit dem Präfix existiert. */
export async function ensurePlugin(dir, projectId, loaders, filePrefix) {
  await fs.mkdir(dir, { recursive: true });
  const existing = (await fs.readdir(dir)).find((f) => f.toLowerCase().startsWith(filePrefix.toLowerCase()) && f.endsWith('.jar'));
  if (existing) return null;
  const params = new URLSearchParams({ loaders: JSON.stringify(loaders) });
  const versions = await get(`${API}/project/${projectId}/version?${params}`);
  const version = versions.find((v) => v.version_type === 'release') ?? versions[0];
  const file = version?.files.find((f) => f.primary) ?? version?.files[0];
  if (!file) throw new Error(`Keine Version von ${projectId} für ${loaders.join(', ')}`);
  const url = new URL(file.url);
  if (url.hostname !== 'cdn.modrinth.com') throw new Error('Unerwartete Download-Adresse');
  const res = await fetch(url, { headers: HEADERS, signal: AbortSignal.timeout(120000) });
  if (!res.ok) throw new Error(`Download fehlgeschlagen (HTTP ${res.status})`);
  const buffer = Buffer.from(await res.arrayBuffer());
  if (file.hashes?.sha512 && crypto.createHash('sha512').update(buffer).digest('hex') !== file.hashes.sha512) {
    throw new Error('Prüfsumme stimmt nicht');
  }
  const target = path.join(dir, path.basename(file.filename));
  await fs.writeFile(target, buffer);
  return target;
}

export const LUCKPERMS_PROJECT = LUCKPERMS;

async function get(url) {
  const res = await fetch(url, { headers: HEADERS, signal: AbortSignal.timeout(15000) });
  if (res.status === 404) throw new UserError('Nicht auf Modrinth gefunden', 404);
  if (!res.ok) throw new Error(`Modrinth antwortet mit HTTP ${res.status}`);
  return res.json();
}

/** Sucht Plugins (bzw. Mods bei Fabric) auf Modrinth, passend zu Servertyp und Version. */
export async function search(server, query, gameVersion) {
  const type = serverType(server);
  const facets = [[`project_type:${type.projectType}`], type.loaders.map((l) => `categories:${l}`)];
  if (gameVersion) facets.push([`versions:${gameVersion}`]);
  const params = new URLSearchParams({
    query: String(query ?? '').slice(0, 100),
    facets: JSON.stringify(facets),
    limit: '20',
    index: query ? 'relevance' : 'downloads',
  });
  const data = await get(`${API}/search?${params}`);
  return data.hits.map((hit) => ({
    projectId: hit.project_id,
    slug: hit.slug,
    title: hit.title,
    description: hit.description,
    downloads: hit.downloads,
    icon: hit.icon_url || null,
    author: hit.author,
  }));
}

/**
 * Installiert die neueste passende Version eines Projekts in den plugins/- bzw. mods/-Ordner,
 * inklusive benötigter Abhängigkeiten. Gibt die Liste der installierten Projekte zurück.
 */
export async function install(files, store, serverId, projectId, gameVersion, installed = [], depth = 0) {
  const server = store.get(serverId);
  const type = serverType(server);
  if (projectId === LUCKPERMS && depth === 0) {
    throw new UserError('LuckPerms ist auf allen Servern schon automatisch installiert. Rechte verwaltest du im Dashboard unter „Rechte“.');
  }
  if (MANAGED_BY_IMAGE.has(projectId) && (projectId === LUCKPERMS || server.type === 'FABRIC')) return installed;
  if (installed.some((p) => p.projectId === projectId)) return installed;

  const params = new URLSearchParams({ loaders: JSON.stringify(type.loaders) });
  if (gameVersion) params.set('game_versions', JSON.stringify([gameVersion]));
  const [project, versions] = await Promise.all([
    get(`${API}/project/${encodeURIComponent(projectId)}`),
    get(`${API}/project/${encodeURIComponent(projectId)}/version?${params}`),
  ]);
  if (!versions.length) {
    throw new UserError(`„${project.title}“ hat keine Version für ${type.label}${gameVersion ? ` ${gameVersion}` : ''}`);
  }
  const version = versions.find((v) => v.version_type === 'release') ?? versions[0];
  const file = version.files.find((f) => f.primary) ?? version.files[0];
  if (!file || !file.filename.endsWith('.jar')) throw new UserError('Keine .jar-Datei in dieser Version');

  const url = new URL(file.url);
  if (url.protocol !== 'https:' || url.hostname !== 'cdn.modrinth.com') throw new Error('Unerwartete Download-Adresse');
  if (file.size > MAX_DOWNLOAD) throw new UserError('Datei ist zu groß');

  const res = await fetch(url, { headers: HEADERS, signal: AbortSignal.timeout(120000) });
  if (!res.ok) throw new Error(`Download fehlgeschlagen (HTTP ${res.status})`);
  const buffer = Buffer.from(await res.arrayBuffer());
  const hash = crypto.createHash('sha512').update(buffer).digest('hex');
  if (file.hashes?.sha512 && hash !== file.hashes.sha512) throw new Error('Prüfsumme stimmt nicht, Download abgebrochen');

  // Ältere Version desselben Projekts ersetzen
  const previous = server.modrinth?.[projectId]?.file;
  const name = await files.saveContent(server, file.filename, buffer);
  if (previous && previous !== name) {
    await fs.rm(path.join(files.contentDir(server), path.basename(previous)), { force: true });
  }
  await store.update(serverId, {
    modrinth: {
      ...(store.get(serverId).modrinth ?? {}),
      [projectId]: { file: name, title: project.title, version: version.version_number, slug: project.slug },
    },
  });
  installed.push({ projectId, title: project.title, version: version.version_number, file: name });

  if (depth < 3) {
    for (const dep of version.dependencies ?? []) {
      if (dep.dependency_type !== 'required' || !dep.project_id) continue;
      if (store.get(serverId).modrinth?.[dep.project_id]) continue;
      await install(files, store, serverId, dep.project_id, gameVersion, installed, depth + 1);
    }
  }
  return installed;
}
