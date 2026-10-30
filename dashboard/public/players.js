'use strict';
// Seite "Spieler": wer ist online, Banns, Mutes, Verlauf. Nutzt $, el und api aus app.js.

const modForm = $('#mod-form');
let modTimer = null;

const ACTION_LABELS = { kick: 'gekickt', ban: 'gebannt', unban: 'entbannt', mute: 'gemutet', unmute: 'entmutet' };

function until(expires) {
  if (!expires) return 'dauerhaft';
  return `bis ${new Date(expires).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' })}`;
}

async function modAction(body) {
  $('#mod-error').textContent = '';
  try {
    await api('POST', '/api/moderation', body);
    loadModeration();
  } catch (err) {
    $('#mod-error').textContent = err.message;
  }
}

function quickActions(player) {
  return el('div', { class: 'server-actions' },
    el('button', { class: 'btn small', onclick: () => {
      const reason = prompt(`${player.name} kicken – Grund:`, 'Vom Team gekickt');
      if (reason !== null) modAction({ action: 'kick', target: player.uuid, reason });
    } }, 'Kicken'),
    el('button', { class: 'btn small', onclick: () => fillForm(player.name, 'mute') }, 'Muten …'),
    el('button', { class: 'btn small danger', onclick: () => fillForm(player.name, 'ban') }, 'Bannen …'));
}

function fillForm(name, action) {
  modForm.elements.target.value = name;
  modForm.elements.action.value = action;
  modForm.elements.reason.focus();
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function loadModeration() {
  let data;
  try {
    data = await api('GET', '/api/moderation');
  } catch (err) {
    $('#mod-error').textContent = err.message;
    return;
  }
  $('#mod-count').textContent = data.proxyOnline ? `(${data.players.length})` : '(Proxy offline)';

  const players = $('#mod-players');
  players.replaceChildren();
  if (!data.players.length) players.append(el('div', { class: 'list-empty' }, 'Gerade ist niemand online.'));
  for (const p of data.players) {
    players.append(el('div', { class: 'list-item' },
      el('img', { src: `https://mc-heads.net/avatar/${p.uuid}/36`, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }),
      el('div', { class: 'grow' },
        el('strong', {}, p.name),
        el('small', {}, [`auf ${p.server || '?'}`, `${p.ping} ms`, p.vanished && 'unsichtbar',
          data.mutes.some((m) => m.uuid === p.uuid) && 'gemutet'].filter(Boolean).join(' · '))),
      quickActions(p)));
  }

  const entryList = (container, entries, undo) => {
    container.replaceChildren();
    if (!entries.length) container.append(el('div', { class: 'list-empty' }, 'Niemand.'));
    for (const e of entries) {
      container.append(el('div', { class: 'list-item' },
        el('div', { class: 'grow' },
          el('strong', {}, e.name),
          el('small', {}, `${until(e.expires)} · ${e.reason} · von ${e.by}`)),
        el('button', { class: 'btn small', onclick: () => modAction({ action: undo, target: e.uuid }) }, 'Aufheben')));
    }
  };
  entryList($('#mod-bans'), data.bans, 'unban');
  entryList($('#mod-mutes'), data.mutes, 'unmute');

  const log = $('#mod-log');
  log.replaceChildren();
  if (!data.log.length) log.append(el('div', { class: 'list-empty' }, 'Noch keine Einträge.'));
  for (const e of data.log) {
    log.append(el('div', { class: 'perm-row' },
      el('code', {}, `${e.name} ${ACTION_LABELS[e.action] ?? e.action}`),
      el('small', { class: 'muted' }, [e.action === 'ban' || e.action === 'mute' ? until(e.expires) : '', e.reason, `von ${e.by}`,
        new Date(e.at).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' })].filter(Boolean).join(' · '))));
  }
}

modForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const f = modForm.elements;
  modAction({ action: f.action.value, target: f.target.value.trim(), duration: Number(f.duration.value), reason: f.reason.value })
    .then(() => { if (!$('#mod-error').textContent) modForm.reset(); });
});

function startModeration() {
  loadModeration();
  clearInterval(modTimer);
  modTimer = setInterval(() => {
    if ($('#players-page').classList.contains('hidden')) clearInterval(modTimer);
    else loadModeration();
  }, 5000);
}
