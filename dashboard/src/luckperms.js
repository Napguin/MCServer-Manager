import express from 'express';
import { UserError } from './files.js';

/**
 * Client für die offizielle LuckPerms-REST-API. Alle Server teilen sich dieselbe Datenbank,
 * nach jeder Änderung schicken wir ein Update über den Messaging-Service, damit alle Server
 * sofort neu laden.
 */
export class LuckPerms {
  constructor(url, key) {
    this.url = url.replace(/\/$/, '');
    this.key = key;
  }

  async req(method, path, body) {
    let res;
    try {
      res = await fetch(this.url + path, {
        method,
        headers: {
          Authorization: `Bearer ${this.key}`,
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(10000),
      });
    } catch {
      throw new UserError('LuckPerms ist nicht erreichbar. Läuft der Container mc-luckperms-api?', 503);
    }
    if (res.status === 404) return null;
    const text = await res.text();
    if (!res.ok) {
      let message = text;
      try {
        message = JSON.parse(text).message ?? text;
      } catch { /* kein JSON */ }
      throw new UserError(`LuckPerms: ${message || `HTTP ${res.status}`}`, res.status >= 500 ? 502 : res.status);
    }
    return text ? JSON.parse(text) : null;
  }

  async pushUpdate(uuid) {
    await this.req('POST', uuid ? `/messaging/update/${uuid}` : '/messaging/update').catch(() => {});
  }
}

// ---------- Umwandlung LuckPerms-Nodes <-> Dashboard ----------

const META_TYPES = new Set(['prefix', 'suffix', 'weight', 'display_name']);

const serverOf = (node) => node.context?.find((c) => c.key === 'server')?.value ?? '';

function textAfter(key, parts) {
  // "prefix.100.&c[Admin] " -> "&c[Admin] " (der Text selbst darf Punkte enthalten)
  return key.split('.').slice(parts).join('.');
}

function toNewNode(node) {
  const out = { key: node.key, value: node.value ?? true, context: node.context ?? [] };
  if (node.expiry) out.expiry = node.expiry;
  return out;
}

function summarizeNodes(nodes) {
  const global = (n) => !n.context?.length;
  const prefix = nodes.find((n) => n.type === 'prefix' && global(n));
  const suffix = nodes.find((n) => n.type === 'suffix' && global(n));
  const weight = nodes.find((n) => n.type === 'weight' && global(n));
  return {
    prefix: prefix ? textAfter(prefix.key, 2) : '',
    suffix: suffix ? textAfter(suffix.key, 2) : '',
    weight: weight ? Number(textAfter(weight.key, 1)) || 0 : 0,
    groups: nodes
      .filter((n) => n.type === 'inheritance')
      .map((n) => ({ group: textAfter(n.key, 1), server: serverOf(n), expiry: n.expiry ?? null, node: toNewNode(n) })),
    permissions: nodes
      .filter((n) => n.type === 'permission' || n.type === 'regex_permission')
      .map((n) => ({ key: n.key, value: n.value, server: serverOf(n), expiry: n.expiry ?? null, node: toNewNode(n) }))
      .sort((a, b) => a.key.localeCompare(b.key)),
  };
}

function groupView(group) {
  const summary = summarizeNodes(group.nodes ?? []);
  const display = (group.nodes ?? []).find((n) => n.type === 'display_name' && !n.context?.length);
  return {
    name: group.name,
    displayName: display ? textAfter(display.key, 1) : group.displayName && group.displayName !== group.name ? group.displayName : '',
    prefix: summary.prefix,
    suffix: summary.suffix,
    weight: summary.weight,
    parents: summary.groups.filter((g) => !g.server && !g.expiry).map((g) => g.group),
    permissions: summary.permissions,
  };
}

// ---------- Prüfung der Eingaben ----------

const GROUP_RE = /^[a-z0-9]{1,36}$/;
const PERMISSION_RE = /^[A-Za-z0-9_.*\-]{1,200}$/;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function checkGroupName(name) {
  const value = String(name ?? '').trim().toLowerCase();
  if (!GROUP_RE.test(value)) throw new UserError('Rollen-Name: nur Kleinbuchstaben und Zahlen, max. 36 Zeichen');
  return value;
}

function checkText(text, label) {
  const value = String(text ?? '');
  if (value.length > 64) throw new UserError(`${label}: max. 64 Zeichen`);
  if (/[\n\r]/.test(value)) throw new UserError(`${label}: keine Zeilenumbrüche`);
  return value;
}

function checkWeight(weight) {
  const n = Number.parseInt(weight ?? 0, 10);
  if (!Number.isInteger(n) || n < 0 || n > 1000) throw new UserError('Gewicht: Zahl von 0 bis 1000');
  return n;
}

function checkUuid(uuid) {
  if (!UUID_RE.test(uuid)) throw new UserError('Ungültige UUID');
  return uuid.toLowerCase();
}

/** Baut die Meta-Nodes einer Rolle. Das Gewicht dient gleichzeitig als Priorität für Prefix/Suffix. */
function metaNodes({ displayName, prefix, suffix, weight }) {
  const nodes = [];
  if (weight) nodes.push({ key: `weight.${weight}`, value: true, context: [] });
  if (displayName) nodes.push({ key: `displayname.${displayName}`, value: true, context: [] });
  if (prefix) nodes.push({ key: `prefix.${weight}.${prefix}`, value: true, context: [] });
  if (suffix) nodes.push({ key: `suffix.${weight}.${suffix}`, value: true, context: [] });
  return nodes;
}

function contextFor(server) {
  return server ? [{ key: 'server', value: server }] : [];
}

/** Sucht einen Minecraft-Account bei Mojang (zwei Endpunkte, falls einer ausfällt). */
export async function mojangProfile(name) {
  const urls = [
    `https://api.minecraftservices.com/minecraft/profile/lookup/name/${encodeURIComponent(name)}`,
    `https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(name)}`,
  ];
  let reachable = false;
  for (const url of urls) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
      reachable = true;
      if (res.ok && res.status !== 204) {
        const profile = await res.json();
        if (profile?.id && profile?.name) return profile;
      }
      if (res.status === 404 || res.status === 204) throw new UserError('Diesen Minecraft-Account gibt es nicht', 404);
    } catch (err) {
      if (err instanceof UserError) throw err;
    }
  }
  throw new UserError(reachable ? 'Mojang hat keine gültige Antwort geliefert' : 'Mojang ist gerade nicht erreichbar', 502);
}

// ---------- Router ----------

/**
 * @param lp LuckPerms-Client
 * @param listServers liefert [{ id, name }] mit LuckPerms-tauglichen Server-Namen
 */
export function permissionsRouter(lp, listServers) {
  const router = express.Router();

  function checkServer(server) {
    const value = String(server ?? '');
    if (value && !listServers().some((s) => s.id === value)) throw new UserError('Unbekannter Server');
    return value;
  }

  function checkPermissionInput(body) {
    const key = String(body?.key ?? '').trim();
    if (!PERMISSION_RE.test(key)) throw new UserError('Ungültiges Recht (erlaubt: Buchstaben, Zahlen, . _ - *)');
    if (key.startsWith('group.') || /^(prefix|suffix|weight|displayname|meta)\./.test(key)) {
      throw new UserError('Rollen, Prefixe und Gewicht bitte über die Felder oben setzen');
    }
    const duration = Number(body?.duration) || 0;
    const node = { key, value: body?.value !== false, context: contextFor(checkServer(body?.server)) };
    if (duration > 0) node.expiry = Math.floor(Date.now() / 1000) + Math.min(duration, 10 * 365 * 86400);
    return node;
  }

  function checkNode(node) {
    if (!node || typeof node.key !== 'string' || !Array.isArray(node.context ?? [])) throw new UserError('Ungültiger Eintrag');
    return toNewNode(node);
  }

  router.get('/status', async (req, res) => {
    try {
      await lp.req('GET', '/health');
      res.json({ online: true, servers: listServers() });
    } catch {
      res.json({ online: false, servers: listServers() });
    }
  });

  // ----- Rollen -----

  async function loadGroups() {
    const names = (await lp.req('GET', '/group')) ?? [];
    const groups = await Promise.all(names.map((n) => lp.req('GET', `/group/${encodeURIComponent(n)}`)));
    return groups.filter(Boolean).map(groupView).sort((a, b) => b.weight - a.weight || a.name.localeCompare(b.name));
  }

  router.get('/groups', async (req, res) => {
    res.json({ groups: await loadGroups() });
  });

  async function writeRoleMeta(name, body, existingNodes) {
    const weight = checkWeight(body.weight);
    const displayName = checkText(body.displayName, 'Anzeigename');
    const prefix = checkText(body.prefix, 'Prefix');
    const suffix = checkText(body.suffix, 'Suffix');
    const parents = [...new Set((Array.isArray(body.parents) ? body.parents : []).map(checkGroupName))].filter((p) => p !== name);

    // Rechte und server-/zeitgebundene Vererbung bleiben, globale Meta-Daten und Vererbung werden ersetzt
    const keep = existingNodes.filter((n) => {
      if (META_TYPES.has(n.type)) return n.context?.length > 0;
      if (n.type === 'inheritance') return n.context?.length > 0 || n.expiry;
      return true;
    });
    const nodes = [
      ...keep.map(toNewNode),
      ...metaNodes({ displayName, prefix, suffix, weight }),
      ...parents.map((p) => ({ key: `group.${p}`, value: true, context: [] })),
    ];
    await lp.req('PUT', `/group/${name}/nodes`, nodes);
  }

  router.post('/groups', async (req, res) => {
    const name = checkGroupName(req.body?.name);
    const created = await lp.req('POST', '/group', { name }).catch((err) => {
      if (err.status === 409) throw new UserError('Diese Rolle gibt es schon');
      throw err;
    });
    await writeRoleMeta(name, req.body ?? {}, created?.nodes ?? []);
    await lp.pushUpdate();
    res.status(201).json({ ok: true });
  });

  router.put('/groups/:name', async (req, res) => {
    const name = checkGroupName(req.params.name);
    const group = await lp.req('GET', `/group/${name}`);
    if (!group) throw new UserError('Rolle nicht gefunden', 404);
    await writeRoleMeta(name, req.body ?? {}, group.nodes ?? []);
    await lp.pushUpdate();
    res.json({ ok: true });
  });

  router.delete('/groups/:name', async (req, res) => {
    const name = checkGroupName(req.params.name);
    if (name === 'default') throw new UserError('Die Standard-Rolle kann nicht gelöscht werden');
    await lp.req('DELETE', `/group/${name}`);
    await lp.pushUpdate();
    res.json({ ok: true });
  });

  router.post('/groups/:name/permissions', async (req, res) => {
    const name = checkGroupName(req.params.name);
    const node = checkPermissionInput(req.body);
    if (!(await lp.req('GET', `/group/${name}`))) throw new UserError('Rolle nicht gefunden', 404);
    await lp.req('POST', `/group/${name}/nodes`, node);
    await lp.pushUpdate();
    res.json({ ok: true });
  });

  router.delete('/groups/:name/permissions', async (req, res) => {
    const name = checkGroupName(req.params.name);
    await lp.req('DELETE', `/group/${name}/nodes`, [checkNode(req.body?.node)]);
    await lp.pushUpdate();
    res.json({ ok: true });
  });

  // Typische Rollen mit einem Klick anlegen
  router.post('/presets', async (req, res) => {
    const presets = [
      { name: 'default', displayName: 'Spieler', prefix: '&7', weight: 0, parents: [] },
      { name: 'vip', displayName: 'VIP', prefix: '&6[VIP] &e', weight: 20, parents: ['default'] },
      { name: 'mod', displayName: 'Moderator', prefix: '&9[Mod] &b', weight: 50, parents: ['vip'] },
      { name: 'admin', displayName: 'Admin', prefix: '&c[Admin] &f', weight: 90, parents: ['mod'], permissions: ['*'] },
      { name: 'owner', displayName: 'Owner', prefix: '&4&l[Owner] &f', weight: 100, parents: ['admin'], permissions: ['*'] },
    ];
    const existing = new Set((await lp.req('GET', '/group')) ?? []);
    const created = [];
    for (const preset of presets) {
      if (existing.has(preset.name) && preset.name !== 'default') continue;
      let group = existing.has(preset.name) ? await lp.req('GET', `/group/${preset.name}`) : await lp.req('POST', '/group', { name: preset.name });
      await writeRoleMeta(preset.name, preset, group?.nodes ?? []);
      for (const perm of preset.permissions ?? []) {
        await lp.req('POST', `/group/${preset.name}/nodes`, { key: perm, value: true, context: [] });
      }
      created.push(preset.name);
    }
    await lp.pushUpdate();
    res.json({ ok: true, created });
  });

  // ----- Spieler -----

  async function playerView(uuid) {
    const user = await lp.req('GET', `/user/${uuid}`);
    if (!user) return null;
    const own = summarizeNodes(user.nodes ?? []);
    return {
      uuid: user.uniqueId,
      username: user.username ?? null,
      primaryGroup: user.metadata?.primaryGroup ?? null,
      effectivePrefix: user.metadata?.prefix ?? '',
      effectiveSuffix: user.metadata?.suffix ?? '',
      prefix: own.prefix,
      suffix: own.suffix,
      groups: own.groups,
      permissions: own.permissions,
    };
  }

  // Alle Spieler, die mehr als nur die Standard-Rolle haben
  router.get('/players', async (req, res) => {
    const results = (await lp.req('GET', '/user/search?type=inheritance')) ?? [];
    const interesting = results
      .map((r) => ({
        uuid: r.uniqueId,
        groups: (r.results ?? [])
          .filter((n) => n.key !== 'group.default')
          .map((n) => ({ group: textAfter(n.key, 1), server: serverOf(n), expiry: n.expiry ?? null })),
      }))
      .filter((p) => p.groups.length)
      .slice(0, 200);
    await Promise.all(interesting.map(async (p) => {
      const lookup = await lp.req('GET', `/user/lookup?uniqueId=${p.uuid}`).catch(() => null);
      p.username = lookup?.username ?? null;
    }));
    interesting.sort((a, b) => (a.username ?? '').localeCompare(b.username ?? ''));
    res.json({ players: interesting });
  });

  // Spieler per Name suchen; unbekannte Spieler werden über Mojang angelegt,
  // damit man Rechte schon vor dem ersten Join vergeben kann
  router.get('/players/lookup', async (req, res) => {
    const name = String(req.query.name ?? '').trim();
    if (!/^[A-Za-z0-9_]{2,16}$/.test(name)) throw new UserError('Ungültiger Spielername');
    let uuid = (await lp.req('GET', `/user/lookup?username=${encodeURIComponent(name)}`))?.uniqueId;
    if (!uuid) {
      const profile = await mojangProfile(name);
      uuid = profile.id.replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
      await lp.req('POST', '/user', { uniqueId: uuid, username: profile.name });
    }
    res.json(await playerView(uuid));
  });

  async function requirePlayer(uuid) {
    if (!(await lp.req('GET', `/user/lookup?uniqueId=${uuid}`))) throw new UserError('Spieler nicht gefunden', 404);
  }

  router.get('/players/:uuid', async (req, res) => {
    const player = await playerView(checkUuid(req.params.uuid));
    if (!player) throw new UserError('Spieler nicht gefunden', 404);
    res.json(player);
  });

  router.post('/players/:uuid/groups', async (req, res) => {
    const uuid = checkUuid(req.params.uuid);
    const group = checkGroupName(req.body?.group);
    const duration = Number(req.body?.duration) || 0;
    const node = { key: `group.${group}`, value: true, context: contextFor(checkServer(req.body?.server)) };
    if (duration > 0) node.expiry = Math.floor(Date.now() / 1000) + Math.min(duration, 10 * 365 * 86400);
    if (!(await lp.req('GET', `/group/${group}`))) throw new UserError('Rolle nicht gefunden', 404);
    await requirePlayer(uuid);
    await lp.req('POST', `/user/${uuid}/nodes`, node);
    await lp.pushUpdate(uuid);
    res.json(await playerView(uuid));
  });

  router.post('/players/:uuid/permissions', async (req, res) => {
    const uuid = checkUuid(req.params.uuid);
    const node = checkPermissionInput(req.body);
    await requirePlayer(uuid);
    await lp.req('POST', `/user/${uuid}/nodes`, node);
    await lp.pushUpdate(uuid);
    res.json(await playerView(uuid));
  });

  // Entfernt einen Eintrag (Rolle oder Recht) genau so, wie er geladen wurde
  router.delete('/players/:uuid/nodes', async (req, res) => {
    const uuid = checkUuid(req.params.uuid);
    await lp.req('DELETE', `/user/${uuid}/nodes`, [checkNode(req.body?.node)]);
    await lp.pushUpdate(uuid);
    res.json(await playerView(uuid));
  });

  // Eigener Prefix/Suffix für einen Spieler (überschreibt den der Rolle)
  router.put('/players/:uuid/meta', async (req, res) => {
    const uuid = checkUuid(req.params.uuid);
    const prefix = checkText(req.body?.prefix, 'Prefix');
    const suffix = checkText(req.body?.suffix, 'Suffix');
    const user = await lp.req('GET', `/user/${uuid}`);
    if (!user) throw new UserError('Spieler nicht gefunden', 404);
    const old = (user.nodes ?? []).filter((n) => (n.type === 'prefix' || n.type === 'suffix') && !n.context?.length);
    if (old.length) await lp.req('DELETE', `/user/${uuid}/nodes`, old.map(toNewNode));
    const add = [];
    if (prefix) add.push({ key: `prefix.10000.${prefix}`, value: true, context: [] });
    if (suffix) add.push({ key: `suffix.10000.${suffix}`, value: true, context: [] });
    if (add.length) await lp.req('PATCH', `/user/${uuid}/nodes`, add);
    await lp.pushUpdate(uuid);
    res.json(await playerView(uuid));
  });

  return router;
}
