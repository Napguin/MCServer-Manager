'use strict';
// Seite "Lobby-Extras": Hologramme, NPCs, Server-Schilder, Jump & Runs und Cosmetics.
// Positionen setzt man im Spiel, hier lassen sich Texte und Einstellungen ändern. Nutzt $, el und api aus app.js.

let extrasData = null;

const COSMETIC_NAMES = {
  'trail.hearts': 'Herzen', 'trail.flames': 'Flammen', 'trail.notes': 'Noten', 'trail.clouds': 'Wolken',
  'trail.snow': 'Schnee', 'trail.sparkle': 'Glitzer', 'trail.magic': 'Magie', 'trail.rainbow': 'Regenbogen',
  'trail.souls': 'Seelenfeuer', 'trail.cherry': 'Kirschblüten',
  'hat.glass': 'Astronaut', 'hat.tnt': 'TNT', 'hat.melon': 'Melone', 'hat.diamond': 'Diamantkopf', 'hat.gold': 'Goldkopf',
  'hat.slime': 'Schleim', 'hat.hay': 'Strohhut', 'hat.beacon': 'Leuchtfeuer', 'hat.lantern': 'Kürbislaterne', 'hat.ice': 'Eiskopf',
  'gadget.firework': 'Feuerwerk', 'gadget.grapple': 'Enterhaken', 'gadget.paintball': 'Paintball',
  'gadget.confetti': 'Konfetti-Kanone', 'gadget.rocket': 'Raketenstiefel', 'gadget.pearl': 'Enderperle',
  'pet.dog': 'Hund', 'pet.cat': 'Katze', 'pet.fox': 'Fuchs', 'pet.rabbit': 'Hase', 'pet.pig': 'Schwein',
  'pet.chicken': 'Huhn', 'pet.bee': 'Biene', 'pet.axolotl': 'Axolotl',
};
const CATEGORY_NAMES = { trail: 'Partikel-Spuren', hat: 'Hüte', gadget: 'Gadgets', pet: 'Haustiere' };
const ACTION_NAMES = {
  none: 'Nichts', menu: 'Serverauswahl öffnen', cosmetics: 'Cosmetics öffnen',
  server: 'Auf einen Server schicken', parkour: 'Zum Jump & Run teleportieren', command: 'Befehl ausführen',
};
const NPC_TYPES = ['PLAYER', 'VILLAGER', 'WANDERING_TRADER', 'IRON_GOLEM', 'SNOW_GOLEM', 'ALLAY', 'ZOMBIE', 'SKELETON',
  'CREEPER', 'ENDERMAN', 'PIGLIN', 'BLAZE', 'WITCH', 'FOX', 'CAT', 'WOLF', 'PANDA', 'POLAR_BEAR', 'AXOLOTL', 'FROG',
  'SHEEP', 'PIG', 'COW', 'CHICKEN', 'HORSE', 'LLAMA', 'BEE', 'PARROT', 'SNIFFER', 'WARDEN'];

const fmtTime = (ms) => `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}.${String(ms % 1000).padStart(3, '0')}`;
const where = (l) => (l ? `${l.world} · ${Math.round(l.x)} / ${Math.round(l.y)} / ${Math.round(l.z)}` : '–');

function field(label, input, hint) {
  return el('label', {}, label, hint ? el('small', { class: 'muted' }, ` (${hint})`) : null, input);
}

function textInput(value, attrs = {}) {
  const input = el('input', attrs);
  input.value = value ?? '';
  return input;
}

function textArea(value, rows = 3) {
  const area = el('textarea', { rows: String(rows) });
  area.value = value ?? '';
  return area;
}

function checkbox(checked) {
  const box = el('input', { type: 'checkbox' });
  box.checked = !!checked;
  return box;
}

function select(options, value) {
  const s = el('select');
  for (const [key, label] of Object.entries(options)) s.append(el('option', { value: key }, label));
  s.value = value;
  return s;
}

/** Eine Karte mit Kopfzeile (Name, Position) und Speichern/Löschen. */
function itemCard(kind, id, title, location, body, collect, extraButtons = []) {
  const status = el('span', { class: 'muted small-text' });
  const save = el('button', { class: 'btn small primary', type: 'button' }, 'Speichern');
  save.addEventListener('click', async () => {
    try {
      await api('PUT', `/api/extras/${kind}/${encodeURIComponent(id)}`, collect());
      status.textContent = 'Gespeichert ✓';
      await loadExtras(false);
    } catch (err) {
      status.textContent = err.message;
    }
  });
  const del = el('button', { class: 'btn small danger', type: 'button' }, 'Löschen');
  del.addEventListener('click', async () => {
    if (!confirm(`„${id}“ wirklich löschen?`)) return;
    try {
      await api('DELETE', `/api/extras/${kind}/${encodeURIComponent(id)}`);
      await loadExtras(false);
    } catch (err) {
      status.textContent = err.message;
    }
  });
  return el('div', { class: 'card extra-item' },
    el('div', { class: 'extra-head' },
      el('div', {}, el('strong', {}, title), el('div', { class: 'muted small-text' }, location)),
      el('div', { class: 'server-actions' }, status, ...extraButtons, del, save)),
    body);
}

function empty(text) {
  return el('p', { class: 'muted' }, text);
}

function renderHolograms() {
  const list = $('#extras-holograms');
  const entries = Object.entries(extrasData.holograms);
  list.replaceChildren(...(entries.length ? [] : [empty('Noch keine Hologramme. Im Spiel: /holo create <name> <Text>')]));
  for (const [id, h] of entries) {
    const de = textArea(h.text, 4);
    const en = textArea(h.text_en, 4);
    const scale = textInput(h.scale, { type: 'number', min: '0.2', max: '5', step: '0.1' });
    const bg = checkbox(h.background);
    list.append(itemCard('holograms', id, id, where(h.location),
      el('div', { class: 'extra-body' },
        el('div', { class: 'row' }, field('Text Deutsch', de), field('Text Englisch', en, 'leer = deutsch')),
        el('div', { class: 'row' }, field('Größe', scale), el('div', { class: 'checks toggles' }, el('label', {}, bg, ' Dunkler Hintergrund')))),
      () => ({ text: de.value, text_en: en.value, scale: Number(scale.value) || 1, background: bg.checked })));
  }
}

function renderNpcs() {
  const list = $('#extras-npcs');
  const entries = Object.entries(extrasData.npcs);
  list.replaceChildren(...(entries.length ? [] : [empty('Noch keine NPCs. Im Spiel: /npc create <name> <Spielername oder Mob>')]));
  const serverOptions = Object.fromEntries(servers.filter((s) => s.id !== 'lobby').map((s) => [s.id, `${s.name} (${s.id})`]));
  for (const [id, n] of entries) {
    const types = Object.fromEntries(NPC_TYPES.map((t) => [t, t === 'PLAYER' ? 'Spieler (mit Skin)' : t.toLowerCase()]));
    if (!types[n.type]) types[n.type] = n.type.toLowerCase();
    const type = select(types, n.type);
    const skin = textInput(n.skin, { maxlength: '16', placeholder: 'Minecraft-Name' });
    const name = textArea(n.name, 2);
    const nameEn = textArea(n.name_en, 2);
    const action = select(ACTION_NAMES, n.action);
    const target = textInput(n.target);
    const serverSelect = select({ '': '– Server wählen –', ...serverOptions }, n.target);
    const look = checkbox(n.look);
    const targetWrap = el('div');
    const skinWrap = field('Skin', skin, 'Spielername');
    const update = () => {
      skinWrap.classList.toggle('hidden', type.value !== 'PLAYER');
      const a = action.value;
      if (a === 'server') targetWrap.replaceChildren(field('Server', serverSelect));
      else if (a === 'parkour') targetWrap.replaceChildren(field('Jump & Run', select(Object.fromEntries(Object.keys(extrasData.parkours).map((p) => [p, p])), target.value), ''));
      else if (a === 'command') targetWrap.replaceChildren(field('Befehl', target, 'ohne /, wird vom Spieler ausgeführt'));
      else targetWrap.replaceChildren();
    };
    type.addEventListener('change', update);
    action.addEventListener('change', update);
    update();
    const collectTarget = () => {
      if (action.value === 'server') return serverSelect.value;
      if (action.value === 'parkour') return targetWrap.querySelector('select')?.value ?? '';
      if (action.value === 'command') return target.value;
      return '';
    };
    list.append(itemCard('npcs', id, id, where(n.location),
      el('div', { class: 'extra-body' },
        el('div', { class: 'row' }, field('Aussehen', type), skinWrap),
        el('div', { class: 'row' }, field('Name Deutsch', name, 'mehrere Zeilen möglich'), field('Name Englisch', nameEn, 'leer = deutsch')),
        el('div', { class: 'row' }, field('Bei Klick', action), targetWrap),
        el('div', { class: 'checks toggles' }, el('label', {}, look, ' Schaut Spieler in der Nähe an'))),
      () => ({ type: type.value, skin: skin.value, name: name.value, name_en: nameEn.value, action: action.value, target: collectTarget(), look: look.checked })));
  }
}

function renderSigns() {
  const list = $('#extras-signs');
  const entries = Object.entries(extrasData.signs);
  list.replaceChildren(...(entries.length ? [] : [empty('Noch keine Server-Schilder. Im Spiel: Schild anschauen und /serversign <server-id>')]));
  const serverOptions = Object.fromEntries(servers.filter((s) => s.id !== 'lobby').map((s) => [s.id, `${s.name} (${s.id})`]));
  for (const [id, s] of entries) {
    const server = select({ ...serverOptions, ...(serverOptions[s.server] ? {} : { [s.server]: `${s.server} (gibt es nicht)` }) }, s.server);
    list.append(itemCard('signs', id, `Schild → ${s.server}`, where(s.location),
      el('div', { class: 'extra-body' }, field('Server', server)),
      () => ({ server: server.value })));
  }
}

function renderParkours() {
  const list = $('#extras-parkours');
  const entries = Object.entries(extrasData.parkours);
  list.replaceChildren(...(entries.length ? [] : [empty('Noch keine Jump & Runs. Im Spiel: /parkour create <name> – dann /parkour checkpoint und /parkour finish')]));
  for (const [id, p] of entries) {
    const name = textInput(p.name, { maxlength: '64' });
    const nameEn = textInput(p.name_en, { maxlength: '64' });
    const top = extrasData.top[id] ?? [];
    const table = top.length
      ? el('ol', { class: 'ranking' }, ...top.map((t) => el('li', {}, el('span', {}, t.name), el('code', {}, fmtTime(t.ms)))))
      : empty('Noch keine Zeiten.');
    const reset = el('button', { class: 'btn small', type: 'button' }, 'Zeiten zurücksetzen');
    reset.addEventListener('click', async () => {
      if (!confirm(`Alle ${extrasData.counts[id] ?? 0} Bestzeiten von „${p.name}“ löschen?`)) return;
      await api('DELETE', `/api/extras/parkours/${encodeURIComponent(id)}/times`);
      await loadExtras(false);
    });
    const info = `${p.checkpoints.length} Checkpoints · ${p.finish ? 'Ziel gesetzt' : '⚠ kein Ziel (/parkour finish)'} · ${p.leaderboard ? 'Bestenliste aufgestellt' : 'keine Bestenliste (/parkour leaderboard)'}`;
    list.append(itemCard('parkours', id, `${p.name} (${id})`, `Start: ${where(p.start)}`,
      el('div', { class: 'extra-body' },
        el('div', { class: 'row' }, field('Name Deutsch', name), field('Name Englisch', nameEn, 'leer = deutsch')),
        el('p', { class: 'muted small-text' }, info),
        el('div', {}, el('strong', {}, `Bestenliste (${extrasData.counts[id] ?? 0} Spieler)`), table)),
      () => ({ name: name.value, name_en: nameEn.value }), [reset]));
  }
}

function renderCosmetics() {
  const c = extrasData.cosmetics;
  $('#cosmetics-enabled').checked = c.enabled;
  const grid = $('#extras-cosmetics');
  grid.replaceChildren();
  for (const [cat, ids] of Object.entries(extrasData.catalog)) {
    const rows = ids.map((short) => {
      const key = `${cat}.${short}`;
      const on = checkbox(!c.disabled.includes(key));
      const premium = checkbox(c.premium.includes(key));
      on.dataset.key = key;
      on.dataset.role = 'on';
      premium.dataset.key = key;
      premium.dataset.role = 'premium';
      return el('tr', {}, el('td', {}, COSMETIC_NAMES[key] ?? key), el('td', {}, on), el('td', {}, premium));
    });
    grid.append(el('div', { class: 'card lobby-card' }, el('h3', {}, CATEGORY_NAMES[cat] ?? cat),
      el('table', { class: 'cosmetic-table' },
        el('thead', {}, el('tr', {}, el('th', {}, ''), el('th', {}, 'An'), el('th', {}, 'Premium'))),
        el('tbody', {}, ...rows))));
  }
}

async function loadExtras(clearStatus = true) {
  if (clearStatus) $('#extras-status').textContent = '';
  try {
    if (!servers.length) servers = (await api('GET', '/api/overview')).servers ?? servers;
  } catch {
    /* Serverliste ist nur für die Auswahlfelder */
  }
  try {
    extrasData = await api('GET', '/api/extras');
    renderHolograms();
    renderNpcs();
    renderSigns();
    renderParkours();
    renderCosmetics();
  } catch (err) {
    $('#extras-status').textContent = err.message;
  }
}

$('#cosmetics-save').addEventListener('click', async () => {
  const disabled = [];
  const premium = [];
  for (const box of document.querySelectorAll('#extras-cosmetics input[type=checkbox]')) {
    if (box.dataset.role === 'on' && !box.checked) disabled.push(box.dataset.key);
    if (box.dataset.role === 'premium' && box.checked) premium.push(box.dataset.key);
  }
  try {
    await api('PUT', '/api/extras/cosmetics', { enabled: $('#cosmetics-enabled').checked, disabled, premium });
    $('#cosmetics-status').textContent = 'Gespeichert ✓';
  } catch (err) {
    $('#cosmetics-status').textContent = err.message;
  }
});
