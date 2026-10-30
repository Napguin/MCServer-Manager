'use strict';

const $ = (sel) => document.querySelector(sel);

const ICONS = [
  'GRASS_BLOCK', 'DIAMOND_SWORD', 'IRON_SWORD', 'BOW', 'NETHER_STAR', 'DIAMOND', 'EMERALD', 'GOLD_INGOT',
  'IRON_PICKAXE', 'DIAMOND_PICKAXE', 'TNT', 'RED_BED', 'ENDER_PEARL', 'ENDER_EYE', 'BEACON',
  'CRAFTING_TABLE', 'CHEST', 'BOOK', 'MAP', 'FILLED_MAP', 'COMPASS', 'CLOCK', 'OAK_SAPLING', 'WHEAT',
  'SAND', 'SANDSTONE', 'END_STONE', 'NETHERRACK', 'OBSIDIAN', 'LAVA_BUCKET', 'WATER_BUCKET',
  'ELYTRA', 'TOTEM_OF_UNDYING', 'GOLDEN_APPLE', 'CAKE', 'FISHING_ROD', 'SHIELD', 'TRIDENT', 'BRICKS',
];

const STATUS_TEXT = {
  online: 'Online',
  starting: 'Startet …',
  setup: 'Wird eingerichtet …',
  stopped: 'Gestoppt',
  missing: 'Wird erstellt …',
  unknown: 'Unbekannt',
};

let servers = [];
let editingId = null;
let consoleTarget = null; // { id } oder { proxy: true }
let consoleTimer = null;
let refreshTimer = null;

async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && url !== '/api/login') {
    showLogin();
    throw new Error('Nicht angemeldet');
  }
  if (!res.ok) throw new Error(data.error || `Fehler ${res.status}`);
  return data;
}

// ---------- Login ----------

function showLogin() {
  clearInterval(refreshTimer);
  $('#app-view').classList.add('hidden');
  $('#login-view').classList.remove('hidden');
  $('#login-password').focus();
}

function showApp() {
  $('#login-view').classList.add('hidden');
  $('#app-view').classList.remove('hidden');
  refresh();
  clearInterval(refreshTimer);
  refreshTimer = setInterval(refresh, 4000);
}

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#login-error').textContent = '';
  try {
    await api('POST', '/api/login', { password: $('#login-password').value });
    $('#login-password').value = '';
    showApp();
  } catch (err) {
    $('#login-error').textContent = err.message;
  }
});

$('#logout-btn').addEventListener('click', async () => {
  await api('POST', '/api/logout').catch(() => {});
  showLogin();
});

// ---------- Übersicht ----------

async function refresh() {
  try {
    const data = await api('GET', '/api/overview');
    servers = data.servers;
    const proxy = $('#proxy-status');
    proxy.className = `pill ${data.proxyOnline ? 'online' : 'offline'}`;
    proxy.textContent = data.proxyOnline ? 'Proxy online' : 'Proxy offline';
    $('#total-players').textContent = `${data.totalPlayers} Spieler online`;
    renderServers();
  } catch {
    /* nächster Versuch beim nächsten Intervall */
  }
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value);
  }
  for (const child of children) if (child != null) node.append(child);
  return node;
}

function renderServers() {
  const grid = $('#server-grid');
  grid.replaceChildren();
  if (!servers.length) {
    grid.append(el('div', { class: 'card empty' }, 'Noch keine Server.'));
    return;
  }
  for (const s of servers) {
    const running = ['online', 'starting', 'setup'].includes(s.status);
    const isLobby = s.id === 'lobby';

    const meta = el('div', { class: 'server-meta' },
      el('span', { class: `pill ${s.status}` }, STATUS_TEXT[s.status] || s.status),
      el('span', { class: 'pill plain' }, `${s.players} Spieler`),
      el('span', { class: 'pill plain' }, `${s.typeLabel} ${s.version}`),
      el('span', { class: 'pill plain' }, `${s.memory} RAM`),
      isLobby ? el('span', { class: 'pill plain' }, 'Lobby') : null,
      s.hidden && !isLobby ? el('span', { class: 'pill plain' }, 'ausgeblendet') : null,
      s.restricted ? el('span', { class: 'pill plain', title: `Recht: network.server.${s.id}` }, '🔒 geschützt') : null,
    );

    const actions = el('div', { class: 'server-actions' },
      running
        ? el('button', { class: 'btn small', onclick: () => action(s.id, 'stop') }, 'Stoppen')
        : el('button', { class: 'btn small', onclick: () => action(s.id, 'start') }, 'Starten'),
      el('button', { class: 'btn small', onclick: () => action(s.id, 'restart') }, 'Neustart'),
      el('button', { class: 'btn small', onclick: () => openConsole({ id: s.id, name: s.name }) }, 'Konsole'),
      el('button', { class: 'btn small', onclick: () => openPlugins(s) }, s.contentLabel),
      el('button', { class: 'btn small', onclick: () => openFiles(s) }, 'Dateien'),
      el('button', { class: 'btn small', onclick: () => openEdit(s) }, 'Bearbeiten'),
      isLobby ? null : el('button', { class: 'btn small danger', onclick: () => removeServer(s) }, 'Löschen'),
    );

    grid.append(el('article', { class: 'card server' },
      el('div', { class: 'server-head' },
        el('div', { class: 'server-icon', title: s.icon }, s.icon.slice(0, 2)),
        el('div', { class: 'server-title' },
          el('strong', {}, s.name),
          el('small', {}, `${s.id} · ${s.icon}${s.slot >= 0 ? ` · Slot ${s.slot}` : ''}`)),
      ),
      s.description ? el('p', { class: 'server-desc' }, s.description) : null,
      meta,
      actions,
    ));
  }
}

async function action(id, name) {
  try {
    await api('POST', `/api/servers/${id}/${name}`);
  } catch (err) {
    alert(err.message);
  }
  refresh();
}

async function removeServer(s) {
  if (!confirm(`Server „${s.name}“ wirklich löschen?`)) return;
  const deleteFiles = confirm('Auch die Welt und alle Dateien des Servers löschen?\n\nOK = alles löschen, Abbrechen = Dateien behalten');
  try {
    await api('DELETE', `/api/servers/${s.id}${deleteFiles ? '?deleteFiles=1' : ''}`);
  } catch (err) {
    alert(err.message);
  }
  refresh();
}

// ---------- Server-Dialog ----------

const dialog = $('#server-dialog');
const form = $('#server-form');
$('#icon-list').append(...ICONS.map((icon) => el('option', { value: icon })));

function setMode(create) {
  form.querySelectorAll('.create-only').forEach((n) => n.classList.toggle('hidden', !create));
  form.querySelectorAll('.edit-only').forEach((n) => n.classList.toggle('hidden', create));
  form.elements.id.required = create;
  form.elements.restricted.disabled = false;
  form.querySelector('.restrict-row').classList.remove('hidden');
}

// Zeigt das genaue Recht passend zur ID an
function updateRestrictHint() {
  $('#restrict-perm').textContent = `network.server.${editingId || form.elements.id.value || '<id>'}`;
}
form.elements.id.addEventListener('input', updateRestrictHint);

$('#add-btn').addEventListener('click', () => {
  editingId = null;
  form.reset();
  setMode(true);
  $('#server-dialog-title').textContent = 'Server hinzufügen';
  $('#server-error').textContent = '';
  updateRestrictHint();
  dialog.showModal();
});

// ID automatisch aus dem Namen vorschlagen
form.elements.name.addEventListener('input', () => {
  if (editingId) return;
  form.elements.id.value = form.elements.name.value.toLowerCase()
    .replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30);
  updateRestrictHint();
});

function openEdit(s) {
  editingId = s.id;
  form.reset();
  setMode(false);
  $('#server-dialog-title').textContent = `${s.name} bearbeiten`;
  $('#server-error').textContent = '';
  form.elements.name.value = s.name;
  form.elements.description.value = s.description || '';
  form.elements.icon.value = s.icon;
  form.elements.slot.value = s.slot >= 0 ? s.slot : '';
  form.elements.hidden.checked = !!s.hidden;
  form.elements.hidden.disabled = s.id === 'lobby';
  form.elements.restricted.checked = !!s.restricted;
  // Die Lobby bleibt für alle offen
  form.querySelector('.restrict-row').classList.toggle('hidden', s.id === 'lobby');
  updateRestrictHint();
  dialog.showModal();
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = form.elements;
  const body = {
    name: f.name.value,
    description: f.description.value,
    icon: f.icon.value || 'GRASS_BLOCK',
    slot: f.slot.value === '' ? -1 : Number(f.slot.value),
    restricted: f.restricted.checked,
  };
  try {
    if (editingId) {
      body.hidden = f.hidden.checked;
      await api('PATCH', `/api/servers/${editingId}`, body);
    } else {
      Object.assign(body, {
        id: f.id.value, type: f.type.value, version: f.version.value || 'LATEST', memory: f.memory.value,
      });
      await api('POST', '/api/servers', body);
    }
    dialog.close();
    refresh();
  } catch (err) {
    $('#server-error').textContent = err.message;
  }
});

document.querySelectorAll('[data-close]').forEach((btn) =>
  btn.addEventListener('click', () => btn.closest('dialog').close()));

// ---------- Konsole ----------

const consoleDialog = $('#console-dialog');

function openConsole(target) {
  consoleTarget = target;
  $('#console-title').textContent = target.proxy ? 'Proxy (Velocity) – Logs' : `Konsole – ${target.name}`;
  $('#console-form').classList.toggle('hidden', !!target.proxy);
  $('#console-output').textContent = 'Lade …';
  consoleDialog.showModal();
  loadLogs(true);
  clearInterval(consoleTimer);
  consoleTimer = setInterval(() => loadLogs(false), 3000);
}

consoleDialog.addEventListener('close', () => clearInterval(consoleTimer));

async function loadLogs(forceScroll) {
  if (!consoleTarget) return;
  const out = $('#console-output');
  const atBottom = out.scrollTop + out.clientHeight >= out.scrollHeight - 30;
  try {
    const url = consoleTarget.proxy ? '/api/proxy/logs' : `/api/servers/${consoleTarget.id}/logs`;
    const data = await api('GET', url);
    out.textContent = data.logs || '(noch keine Ausgabe)';
    if (forceScroll || atBottom) out.scrollTop = out.scrollHeight;
  } catch (err) {
    out.textContent = err.message;
  }
}

$('#console-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const input = $('#console-command');
  const command = input.value.trim();
  if (!command || !consoleTarget?.id) return;
  input.value = '';
  try {
    await api('POST', `/api/servers/${consoleTarget.id}/command`, { command });
  } catch (err) {
    alert(err.message);
  }
  setTimeout(() => loadLogs(true), 500);
});

$('#proxy-logs-btn').addEventListener('click', () => openConsole({ proxy: true }));
$('#proxy-restart-btn').addEventListener('click', async () => {
  if (!confirm('Proxy neu starten? Alle Spieler werden kurz getrennt.')) return;
  try {
    await api('POST', '/api/proxy/restart');
  } catch (err) {
    alert(err.message);
  }
});

// ---------- Plugins / Mods ----------

const pluginsDialog = $('#plugins-dialog');
let pluginServer = null;

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatCount(n) {
  return n >= 1e6 ? `${(n / 1e6).toFixed(1)} Mio.` : n >= 1e3 ? `${(n / 1e3).toFixed(0)} Tsd.` : String(n);
}

function showTab(name) {
  pluginsDialog.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  pluginsDialog.querySelectorAll('.tab-panel').forEach((p) => p.classList.toggle('hidden', p.dataset.panel !== name));
  if (name === 'search' && !$('#search-results').childElementCount) runSearch('');
  if (name === 'search') $('#search-query').focus();
}

pluginsDialog.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));

function openPlugins(s) {
  pluginServer = s;
  $('#plugins-title').textContent = `${s.contentLabel} – ${s.name}`;
  $('#plugins-sub').textContent = '';
  $('#plugins-error').textContent = '';
  $('#upload-status').textContent = '';
  $('#restart-hint').classList.add('hidden');
  $('#search-results').replaceChildren();
  $('#search-query').value = '';
  showTab('installed');
  pluginsDialog.showModal();
  loadPlugins();
}

function needsRestart() {
  $('#restart-hint').classList.remove('hidden');
}

$('#restart-from-plugins').addEventListener('click', async () => {
  $('#restart-hint').classList.add('hidden');
  await action(pluginServer.id, 'restart');
});

async function loadPlugins() {
  const list = $('#plugin-list');
  try {
    const data = await api('GET', `/api/servers/${pluginServer.id}/plugins`);
    $('#plugins-sub').textContent = `${data.type} · Minecraft ${data.gameVersion ?? '(Version wird nach dem ersten Start erkannt)'} · Ordner /${data.folder}`;
    list.replaceChildren();
    if (!data.files.length) {
      list.append(el('div', { class: 'list-empty' }, `Noch keine ${pluginServer.contentLabel} installiert.`));
      return;
    }
    for (const file of data.files) {
      const info = file.managed
        ? `${formatSize(file.size)} · vom System verwaltet`
        : file.modrinth
          ? `${file.modrinth.title} ${file.modrinth.version} · von Modrinth · ${formatSize(file.size)}`
          : `${formatSize(file.size)} · manuell hinzugefügt`;
      list.append(el('div', { class: 'list-item' },
        el('div', { class: 'grow' }, el('strong', {}, file.name), el('small', {}, info)),
        file.modrinth
          ? el('button', { class: 'btn small', onclick: () => installProject(file.modrinth.projectId, file.modrinth.title) }, 'Aktualisieren')
          : null,
        file.managed ? null : el('button', { class: 'btn small danger', onclick: () => deletePlugin(file.name) }, 'Entfernen'),
      ));
    }
  } catch (err) {
    $('#plugins-error').textContent = err.message;
  }
}

async function deletePlugin(name) {
  if (!confirm(`${name} entfernen?`)) return;
  try {
    await api('DELETE', `/api/servers/${pluginServer.id}/plugins/${encodeURIComponent(name)}`);
    needsRestart();
    loadPlugins();
  } catch (err) {
    $('#plugins-error').textContent = err.message;
  }
}

async function runSearch(query) {
  const results = $('#search-results');
  results.replaceChildren(el('div', { class: 'list-empty' }, 'Suche …'));
  $('#plugins-error').textContent = '';
  try {
    const data = await api('GET', `/api/servers/${pluginServer.id}/modrinth/search?q=${encodeURIComponent(query)}`);
    results.replaceChildren();
    if (!data.results.length) {
      results.append(el('div', { class: 'list-empty' }, 'Nichts gefunden, das zu diesem Server passt.'));
      return;
    }
    for (const r of data.results) {
      results.append(el('div', { class: 'list-item' },
        r.icon ? el('img', { src: r.icon, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }) : el('div', { class: 'ph' }),
        el('div', { class: 'grow' },
          el('strong', {}, r.title),
          el('small', {}, `${r.description}`),
          el('small', {}, `von ${r.author} · ${formatCount(r.downloads)} Downloads`)),
        el('a', { class: 'btn small ghost', href: `https://modrinth.com/project/${r.slug}`, target: '_blank', rel: 'noopener noreferrer' }, 'Info'),
        el('button', { class: 'btn small primary', onclick: (e) => installProject(r.projectId, r.title, e.target) }, 'Installieren'),
      ));
    }
  } catch (err) {
    results.replaceChildren();
    $('#plugins-error').textContent = err.message;
  }
}

$('#search-form').addEventListener('submit', (e) => {
  e.preventDefault();
  runSearch($('#search-query').value.trim());
});

async function installProject(projectId, title, button) {
  $('#plugins-error').textContent = '';
  if (button) { button.disabled = true; button.textContent = 'Lädt …'; }
  try {
    const data = await api('POST', `/api/servers/${pluginServer.id}/modrinth/install`, { projectId });
    const names = data.installed.map((p) => `${p.title} ${p.version}`);
    if (button) button.textContent = 'Installiert ✓';
    $('#upload-status').textContent = '';
    $('#plugins-sub').textContent = names.length ? `Installiert: ${names.join(', ')}` : `${title} ist schon installiert.`;
    needsRestart();
    loadPlugins();
  } catch (err) {
    if (button) { button.disabled = false; button.textContent = 'Installieren'; }
    $('#plugins-error').textContent = err.message;
  }
}

async function uploadFiles(fileList) {
  const status = $('#upload-status');
  $('#plugins-error').textContent = '';
  for (const file of fileList) {
    if (!file.name.toLowerCase().endsWith('.jar')) {
      $('#plugins-error').textContent = `${file.name}: nur .jar-Dateien`;
      continue;
    }
    status.textContent = `Lade ${file.name} hoch …`;
    try {
      const res = await fetch(`/api/servers/${pluginServer.id}/plugins/upload?name=${encodeURIComponent(file.name)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: file,
        credentials: 'same-origin',
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `Fehler ${res.status}`);
      status.textContent = `${file.name} hochgeladen.`;
      needsRestart();
    } catch (err) {
      $('#plugins-error').textContent = `${file.name}: ${err.message}`;
    }
  }
  loadPlugins();
}

const dropzone = $('#dropzone');
$('#upload-input').addEventListener('change', (e) => {
  uploadFiles([...e.target.files]);
  e.target.value = '';
});
dropzone.addEventListener('dragover', (e) => { e.preventDefault(); dropzone.classList.add('over'); });
dropzone.addEventListener('dragleave', () => dropzone.classList.remove('over'));
dropzone.addEventListener('drop', (e) => {
  e.preventDefault();
  dropzone.classList.remove('over');
  uploadFiles([...e.dataTransfer.files]);
});

// ---------- Datei-Editor ----------

const filesDialog = $('#files-dialog');
let filesServer = null;
let currentDir = '';
let currentFile = null;
let fileDirty = false;

function openFiles(s) {
  filesServer = s;
  $('#files-title').textContent = `Dateien – ${s.name}`;
  filesDialog.showModal();
  browse('');
}

function renderBreadcrumb(dir, file) {
  const crumb = $('#files-breadcrumb');
  crumb.replaceChildren(el('button', { onclick: () => leaveEditor(() => browse('')) }, filesServer.id));
  let acc = '';
  for (const part of dir.split('/').filter(Boolean)) {
    acc = acc ? `${acc}/${part}` : part;
    const target = acc;
    crumb.append(' / ', el('button', { onclick: () => leaveEditor(() => browse(target)) }, part));
  }
  if (file) crumb.append(' / ', el('span', {}, file));
}

async function browse(dir) {
  currentDir = dir;
  currentFile = null;
  $('#files-error').textContent = '';
  $('#files-editor').classList.add('hidden');
  const list = $('#files-browser');
  list.classList.remove('hidden');
  renderBreadcrumb(dir);
  try {
    const data = await api('GET', `/api/servers/${filesServer.id}/files?path=${encodeURIComponent(dir)}`);
    list.replaceChildren();
    if (dir) {
      list.append(el('div', { class: 'list-item', onclick: () => browse(dir.split('/').slice(0, -1).join('/')) },
        el('div', { class: 'grow' }, el('strong', {}, '📁 ..'))));
    }
    if (!data.entries.length) list.append(el('div', { class: 'list-empty' }, 'Ordner ist leer.'));
    for (const entry of data.entries) {
      const full = dir ? `${dir}/${entry.name}` : entry.name;
      if (entry.type === 'dir') {
        list.append(el('div', { class: 'list-item', onclick: () => browse(full) },
          el('div', { class: 'grow' }, el('strong', {}, `📁 ${entry.name}`))));
      } else {
        list.append(el('div', {
          class: `list-item${entry.editable ? '' : ' disabled'}`,
          title: entry.editable ? 'Bearbeiten' : 'Dieser Dateityp kann nicht bearbeitet werden',
          onclick: entry.editable ? () => editFile(full) : () => {},
        },
        el('div', { class: 'grow' }, el('strong', {}, `📄 ${entry.name}`)),
        el('small', {}, formatSize(entry.size))));
      }
    }
  } catch (err) {
    $('#files-error').textContent = err.message;
  }
}

async function editFile(file) {
  $('#files-error').textContent = '';
  try {
    const data = await api('GET', `/api/servers/${filesServer.id}/file?path=${encodeURIComponent(file)}`);
    currentFile = file;
    fileDirty = false;
    $('#files-browser').classList.add('hidden');
    $('#files-editor').classList.remove('hidden');
    $('#files-content').value = data.content;
    $('#files-status').textContent = '';
    renderBreadcrumb(currentDir, file.split('/').pop());
  } catch (err) {
    $('#files-error').textContent = err.message;
  }
}

function leaveEditor(next) {
  if (currentFile && fileDirty && !confirm('Ungespeicherte Änderungen verwerfen?')) return;
  fileDirty = false;
  next();
}

$('#files-content').addEventListener('input', () => {
  fileDirty = true;
  $('#files-status').textContent = 'Ungespeichert';
});

$('#files-content').addEventListener('keydown', (e) => {
  // Tab fügt Leerzeichen ein statt das Feld zu verlassen; Strg+S speichert
  if (e.key === 'Tab') {
    e.preventDefault();
    document.execCommand('insertText', false, '  ');
  } else if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault();
    saveFile();
  }
});

async function saveFile() {
  if (!currentFile) return;
  try {
    await api('PUT', `/api/servers/${filesServer.id}/file`, { path: currentFile, content: $('#files-content').value });
    fileDirty = false;
    $('#files-status').textContent = 'Gespeichert ✓ – meist ist ein Neustart nötig';
  } catch (err) {
    $('#files-error').textContent = err.message;
  }
}

$('#files-save').addEventListener('click', saveFile);
$('#files-back').addEventListener('click', () => leaveEditor(() => browse(currentDir)));
filesDialog.addEventListener('cancel', (e) => {
  if (currentFile && fileDirty && !confirm('Ungespeicherte Änderungen verwerfen?')) e.preventDefault();
});

// ---------- Navigation ----------

document.querySelectorAll('.nav-btn').forEach((btn) => btn.addEventListener('click', () => {
  document.querySelectorAll('.nav-btn').forEach((b) => b.classList.toggle('active', b === btn));
  for (const page of ['servers', 'players', 'lobby', 'motd', 'perms']) {
    $(`#${page}-page`).classList.toggle('hidden', btn.dataset.page !== page);
  }
  if (btn.dataset.page === 'perms') loadPerms();
  if (btn.dataset.page === 'lobby') loadLobby();
  if (btn.dataset.page === 'players') startModeration();
  if (btn.dataset.page === 'motd') loadMotd();
}));

// ---------- Start ----------

api('GET', '/api/me')
  .then((me) => (me.admin ? showApp() : showLogin()))
  .catch(showLogin);
