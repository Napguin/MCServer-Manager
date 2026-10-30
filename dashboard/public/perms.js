'use strict';
// Rechte-Verwaltung (LuckPerms). Nutzt $, el und api aus app.js.

const MC_COLORS = {
  0: '#000000', 1: '#0000AA', 2: '#00AA00', 3: '#00AAAA', 4: '#AA0000', 5: '#AA00AA', 6: '#FFAA00', 7: '#AAAAAA',
  8: '#555555', 9: '#5555FF', a: '#55FF55', b: '#55FFFF', c: '#FF5555', d: '#FF55FF', e: '#FFFF55', f: '#FFFFFF',
};

const DURATIONS = [
  ['0', 'Dauerhaft'], ['3600', '1 Stunde'], ['86400', '1 Tag'], ['604800', '7 Tage'],
  ['2592000', '30 Tage'], ['7776000', '90 Tage'],
];

let permsServers = [];
let roles = [];
let editingRole = null;
let currentPlayer = null;

/** Wandelt &-Farbcodes (auch &#RRGGBB) in farbige Spans um – wie im Spiel. */
function mcText(text) {
  const frag = document.createDocumentFragment();
  let style = {};
  let buffer = '';
  const flush = () => {
    if (!buffer) return;
    const span = document.createElement('span');
    span.textContent = buffer;
    if (style.color) span.style.color = style.color;
    if (style.bold) span.style.fontWeight = '700';
    if (style.italic) span.style.fontStyle = 'italic';
    const deco = [style.underline && 'underline', style.strike && 'line-through'].filter(Boolean).join(' ');
    if (deco) span.style.textDecoration = deco;
    frag.append(span);
    buffer = '';
  };
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '&' && i + 1 < text.length) {
      const hex = text.slice(i + 1, i + 8).match(/^#[0-9a-fA-F]{6}/);
      const code = text[i + 1].toLowerCase();
      if (hex) { flush(); style = { color: hex[0] }; i += 7; continue; }
      if (code in MC_COLORS) { flush(); style = { color: MC_COLORS[code] }; i++; continue; }
      const formats = { l: 'bold', o: 'italic', n: 'underline', m: 'strike' };
      if (code in formats) { flush(); style = { ...style, [formats[code]]: true }; i++; continue; }
      if (code === 'r') { flush(); style = {}; i++; continue; }
    }
    buffer += text[i];
  }
  flush();
  return frag;
}

function mcSpan(text) {
  const span = el('span', { class: 'mc' });
  span.append(text ? mcText(text) : document.createTextNode(''));
  return span;
}

function serverLabel(id) {
  if (!id) return 'überall';
  return permsServers.find((s) => s.id === id)?.name ?? id;
}

function expiryLabel(expiry) {
  if (!expiry) return '';
  const date = new Date(expiry * 1000);
  return `bis ${date.toLocaleDateString('de-DE')} ${date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`;
}

function serverSelect(name = 'server') {
  return el('select', { name },
    el('option', { value: '' }, 'Auf allen Servern'),
    ...permsServers.map((s) => el('option', { value: s.id }, s.name)));
}

function durationSelect(name = 'duration') {
  return el('select', { name }, ...DURATIONS.map(([v, l]) => el('option', { value: v }, l)));
}

// ---------- Seite ----------

/** Vorschläge "network.server.<id>" für alle Server (Zugang zu geschützten Servern). */
function updateServerPermSuggestions() {
  const list = $('#perm-suggestions');
  list.querySelectorAll('[data-server]').forEach((o) => o.remove());
  for (const s of servers) {
    if (s.id === 'lobby') continue;
    list.append(el('option', { value: `network.server.${s.id}`, 'data-server': s.id },
      `Darf ${s.name} betreten${s.restricted ? ' (geschützt)' : ''}`));
  }
}

async function loadPerms() {
  updateServerPermSuggestions();
  try {
    const status = await api('GET', '/api/perms/status');
    permsServers = status.servers;
    $('#perms-offline').classList.toggle('hidden', status.online);
    if (!status.online) return;
  } catch {
    return;
  }
  await Promise.all([loadRoles(), loadPlayerList()]);
}

document.querySelectorAll('[data-ptab]').forEach((tab) => tab.addEventListener('click', () => {
  document.querySelectorAll('[data-ptab]').forEach((t) => t.classList.toggle('active', t === tab));
  document.querySelectorAll('[data-ppanel]').forEach((p) => p.classList.toggle('hidden', p.dataset.ppanel !== tab.dataset.ptab));
}));

// ---------- Rollen ----------

async function loadRoles() {
  const grid = $('#role-grid');
  try {
    roles = (await api('GET', '/api/perms/groups')).groups;
  } catch (err) {
    grid.replaceChildren(el('div', { class: 'card empty' }, err.message));
    return;
  }
  $('#presets-btn').classList.toggle('hidden', roles.length > 1);
  grid.replaceChildren();
  for (const role of roles) {
    grid.append(el('article', { class: 'card server' },
      el('div', { class: 'role-head' },
        el('strong', {}, role.displayName || role.name),
        el('small', { class: 'muted' }, `${role.name} · Gewicht ${role.weight}`)),
      el('div', {}, mcSpan(`${role.prefix}${role.displayName || 'Spielername'}${role.suffix}`)),
      el('div', { class: 'chips' },
        ...role.parents.map((p) => el('span', { class: 'chip static' }, el('small', {}, 'erbt '), p)),
        el('span', { class: 'chip static' }, `${role.permissions.length} Rechte`)),
      el('div', { class: 'server-actions' },
        el('button', { class: 'btn small', onclick: () => openRole(role) }, 'Bearbeiten')),
    ));
  }
}

$('#presets-btn').addEventListener('click', async () => {
  if (!confirm('Rollen Spieler, VIP, Moderator, Admin und Owner anlegen?\nAdmin und Owner bekommen alle Rechte (*).')) return;
  try {
    await api('POST', '/api/perms/presets');
  } catch (err) {
    alert(err.message);
  }
  loadRoles();
});

const roleDialog = $('#role-dialog');
const roleForm = $('#role-form');

// Farbcode-Knöpfe: fügen den Code an der Cursorposition im Prefix-Feld ein
let lastColorTarget = roleForm.elements.prefix;
[roleForm.elements.prefix, roleForm.elements.suffix].forEach((input) => {
  input.addEventListener('focus', () => { lastColorTarget = input; });
  input.addEventListener('input', updateRolePreview);
});
roleForm.elements.displayName.addEventListener('input', updateRolePreview);
for (const [code, color] of Object.entries(MC_COLORS)) {
  $('#color-help').append(el('button', {
    type: 'button', title: `&${code}`, style: `background:${color}`,
    onclick: () => insertCode(`&${code}`),
  }, code));
}
for (const [code, label] of [['l', 'B'], ['o', 'I'], ['n', 'U'], ['r', 'R']]) {
  $('#color-help').append(el('button', {
    type: 'button', title: { l: 'Fett', o: 'Kursiv', n: 'Unterstrichen', r: 'Zurücksetzen' }[code],
    style: 'background:#2d3540', onclick: () => insertCode(`&${code}`),
  }, label));
}

function insertCode(code) {
  const input = lastColorTarget;
  const start = input.selectionStart ?? input.value.length;
  input.value = input.value.slice(0, start) + code + input.value.slice(input.selectionEnd ?? start);
  input.focus();
  input.setSelectionRange(start + code.length, start + code.length);
  updateRolePreview();
}

function updateRolePreview() {
  const f = roleForm.elements;
  $('#role-preview').replaceChildren(mcText(`${f.prefix.value}${f.displayName.value || 'Spielername'}${f.suffix.value}&7: Hallo!`));
}

function openRole(role) {
  editingRole = role ?? null;
  roleForm.reset();
  $('#role-error').textContent = '';
  const f = roleForm.elements;
  roleForm.querySelectorAll('.role-create-only').forEach((n) => n.classList.toggle('hidden', !!role));
  f.name.required = !role;
  $('#role-title').textContent = role ? `Rolle: ${role.displayName || role.name}` : 'Neue Rolle';
  $('#role-delete').classList.toggle('hidden', !role || role.name === 'default');
  $('#role-perms').classList.toggle('hidden', !role);
  if (role) {
    f.displayName.value = role.displayName;
    f.prefix.value = role.prefix;
    f.suffix.value = role.suffix;
    f.weight.value = role.weight;
  }
  $('#role-parents').replaceChildren(...roles
    .filter((r) => r.name !== role?.name)
    .map((r) => el('label', {},
      el('input', { type: 'checkbox', value: r.name, ...(role?.parents.includes(r.name) || (!role && r.name === 'default') ? { checked: '' } : {}) }),
      r.displayName || r.name)));
  if (role) renderPermEditor($('#role-perm-editor'), role.permissions, {
    add: (body) => api('POST', `/api/perms/groups/${role.name}/permissions`, body),
    remove: (perm) => api('DELETE', `/api/perms/groups/${role.name}/permissions`, { node: perm.node }),
    reload: async () => {
      await loadRoles();
      return roles.find((r) => r.name === role.name)?.permissions ?? [];
    },
  });
  updateRolePreview();
  roleDialog.showModal();
}

$('#add-role-btn').addEventListener('click', () => openRole(null));

roleForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = roleForm.elements;
  const body = {
    displayName: f.displayName.value.trim(),
    prefix: f.prefix.value,
    suffix: f.suffix.value,
    weight: Number(f.weight.value) || 0,
    parents: [...$('#role-parents').querySelectorAll('input:checked')].map((i) => i.value),
  };
  try {
    if (editingRole) {
      await api('PUT', `/api/perms/groups/${editingRole.name}`, body);
      roleDialog.close();
    } else {
      body.name = f.name.value.trim();
      await api('POST', '/api/perms/groups', body);
      await loadRoles();
      // Direkt weiter zu den Rechten der neuen Rolle
      const created = roles.find((r) => r.name === body.name);
      if (created) openRole(created);
      return;
    }
  } catch (err) {
    $('#role-error').textContent = err.message;
    return;
  }
  loadRoles();
});

$('#role-delete').addEventListener('click', async () => {
  if (!editingRole || !confirm(`Rolle „${editingRole.displayName || editingRole.name}“ löschen? Spieler verlieren diese Rolle.`)) return;
  try {
    await api('DELETE', `/api/perms/groups/${editingRole.name}`);
    roleDialog.close();
    loadRoles();
  } catch (err) {
    $('#role-error').textContent = err.message;
  }
});

// ---------- Rechte-Liste (für Rollen und Spieler) ----------

function renderPermEditor(container, permissions, { add, remove, reload }) {
  const list = el('div', { class: 'list' });
  const error = el('p', { class: 'error' });

  const draw = (perms) => {
    list.replaceChildren();
    if (!perms.length) list.append(el('div', { class: 'list-empty' }, 'Noch keine Rechte.'));
    for (const perm of perms) {
      list.append(el('div', { class: 'perm-row' },
        el('span', { class: perm.value ? 'allow' : 'deny', title: perm.value ? 'erlaubt' : 'verboten' }, perm.value ? '✔' : '✖'),
        el('code', {}, perm.key),
        el('small', { class: 'muted' }, [serverLabel(perm.server), expiryLabel(perm.expiry)].filter(Boolean).join(' · ')),
        el('button', {
          class: 'btn small danger',
          onclick: async () => {
            error.textContent = '';
            try {
              await remove(perm);
              draw(await reload());
            } catch (err) {
              error.textContent = err.message;
            }
          },
        }, 'Entfernen')));
    }
  };

  const form = el('form', { class: 'perm-add' },
    el('input', { name: 'key', list: 'perm-suggestions', placeholder: 'Recht, z. B. essentials.fly', required: '', autocomplete: 'off' }),
    el('select', { name: 'value' }, el('option', { value: 'true' }, 'Erlaubt'), el('option', { value: 'false' }, 'Verboten')),
    serverSelect(),
    durationSelect(),
    el('button', { class: 'btn primary', type: 'submit' }, 'Hinzufügen'));

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    error.textContent = '';
    const f = form.elements;
    try {
      await add({ key: f.key.value.trim(), value: f.value.value === 'true', server: f.server.value, duration: Number(f.duration.value) });
      f.key.value = '';
      draw(await reload());
    } catch (err) {
      error.textContent = err.message;
    }
  });

  container.replaceChildren(form, error, list);
  draw(permissions);
}

// ---------- Spieler ----------

async function loadPlayerList() {
  const list = $('#player-list');
  try {
    const { players } = await api('GET', '/api/perms/players');
    list.replaceChildren();
    if (!players.length) {
      list.append(el('div', { class: 'list-empty' }, 'Noch kein Spieler hat eine besondere Rolle.'));
      return;
    }
    for (const p of players) {
      list.append(el('div', { class: 'list-item files', style: 'cursor:pointer', onclick: () => showPlayer(p.uuid) },
        el('img', { src: `https://mc-heads.net/avatar/${p.uuid}/36`, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }),
        el('div', { class: 'grow' },
          el('strong', {}, p.username ?? p.uuid),
          el('small', {}, p.groups.map((g) => [g.group, g.server && `@${serverLabel(g.server)}`, g.expiry && expiryLabel(g.expiry)].filter(Boolean).join(' ')).join(', ')))));
    }
  } catch (err) {
    list.replaceChildren(el('div', { class: 'list-empty' }, err.message));
  }
}

$('#player-search').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#player-error').textContent = '';
  const name = $('#player-name').value.trim();
  if (!name) return;
  try {
    renderPlayer(await api('GET', `/api/perms/players/lookup?name=${encodeURIComponent(name)}`));
  } catch (err) {
    $('#player-error').textContent = err.message;
  }
});

async function showPlayer(uuid) {
  $('#player-error').textContent = '';
  try {
    renderPlayer(await api('GET', `/api/perms/players/${uuid}`));
    $('#player-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (err) {
    $('#player-error').textContent = err.message;
  }
}

function renderPlayer(player) {
  currentPlayer = player;
  const panel = $('#player-panel');
  panel.classList.remove('hidden');
  const base = `/api/perms/players/${player.uuid}`;
  const error = el('p', { class: 'error' });
  const run = async (fn) => {
    error.textContent = '';
    try {
      const updated = await fn();
      renderPlayer(updated);
      loadPlayerList();
    } catch (err) {
      error.textContent = err.message;
    }
  };

  // Rollen des Spielers
  const groupChips = el('div', { class: 'chips' });
  const groups = player.groups.length ? player.groups : [];
  for (const g of groups) {
    const role = roles.find((r) => r.name === g.group);
    groupChips.append(el('span', { class: 'chip' },
      role?.displayName || g.group,
      g.server || g.expiry ? el('small', {}, [g.server && serverLabel(g.server), g.expiry && expiryLabel(g.expiry)].filter(Boolean).join(' · ')) : null,
      g.group === 'default' && !g.server ? null : el('button', {
        title: 'Rolle entfernen',
        onclick: () => run(() => api('DELETE', `${base}/nodes`, { node: g.node })),
      }, '×')));
  }
  if (!groups.length) groupChips.append(el('span', { class: 'muted' }, 'Nur Standard-Rolle'));

  const addGroup = el('form', { class: 'inline-form' },
    el('select', { name: 'group' }, ...roles.filter((r) => r.name !== 'default').map((r) => el('option', { value: r.name }, r.displayName || r.name))),
    serverSelect(),
    durationSelect(),
    el('button', { class: 'btn primary', type: 'submit' }, 'Rolle geben'));
  addGroup.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = addGroup.elements;
    if (!f.group.value) return;
    run(() => api('POST', `${base}/groups`, { group: f.group.value, server: f.server.value, duration: Number(f.duration.value) }));
  });

  // Eigener Prefix
  const meta = el('form', { class: 'inline-form' },
    el('input', { name: 'prefix', placeholder: 'Eigener Prefix (leer = von der Rolle)', maxlength: '64', value: player.prefix }),
    el('input', { name: 'suffix', placeholder: 'Eigener Suffix', maxlength: '64', value: player.suffix }),
    el('button', { class: 'btn', type: 'submit' }, 'Speichern'));
  meta.addEventListener('submit', (e) => {
    e.preventDefault();
    run(() => api('PUT', `${base}/meta`, { prefix: meta.elements.prefix.value, suffix: meta.elements.suffix.value }));
  });

  const permEditor = el('div', { class: 'perm-editor' });
  renderPermEditor(permEditor, player.permissions, {
    add: (body) => api('POST', `${base}/permissions`, body),
    remove: (perm) => api('DELETE', `${base}/nodes`, { node: perm.node }),
    reload: async () => {
      currentPlayer = await api('GET', base);
      return currentPlayer.permissions;
    },
  });

  panel.replaceChildren(
    el('div', { class: 'player-head' },
      el('img', { src: `https://mc-heads.net/avatar/${player.uuid}/48`, alt: '', referrerpolicy: 'no-referrer' }),
      el('div', { class: 'grow' },
        el('strong', {}, player.username ?? 'Unbekannt'),
        el('small', {}, player.uuid)),
      el('div', {}, mcSpan(`${player.effectivePrefix}${player.username ?? ''}${player.effectiveSuffix}`))),
    el('div', {}, el('h2', { class: 'subhead' }, 'Rollen'), groupChips),
    addGroup,
    el('div', {}, el('h2', { class: 'subhead' }, 'Prefix'), meta),
    el('div', {}, el('h2', { class: 'subhead' }, 'Einzelne Rechte'), permEditor),
    error,
  );
}
