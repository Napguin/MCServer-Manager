'use strict';
// Seite "Lobby & Tab": Texte und Schalter für Lobby, Tab-Liste und Chat. Nutzt $ und api aus app.js.
// Übersetzbare Texte gibt es zweimal: "feld" (Deutsch) und "feld_en" (Englisch).

const lobbyForm = $('#lobby-form');
const TRANSLATABLE = ['welcomeTitle', 'welcomeSubtitle', 'hologram', 'scoreboardTitle', 'scoreboardLines', 'tabHeader', 'tabFooter', 'tabEntry', 'bossBar'];
let lobbyDefaults = null;
let lobbyData = {};
let lobbyLang = 'de';

const keyFor = (name) => (lobbyLang === 'en' && TRANSLATABLE.includes(name) ? `${name}_en` : name);

function fillLobbyForm() {
  for (const field of lobbyForm.elements) {
    if (!field.name) continue;
    const value = lobbyData[keyFor(field.name)];
    if (field.type === 'checkbox') field.checked = !!value;
    else field.value = value ?? '';
  }
}

/** Formular → lobbyData (für die gerade gewählte Sprache). */
function readLobbyForm() {
  for (const field of lobbyForm.elements) {
    if (!field.name) continue;
    lobbyData[keyFor(field.name)] = field.type === 'checkbox' ? field.checked : field.value;
  }
}

document.querySelectorAll('[data-lang]').forEach((btn) => btn.addEventListener('click', () => {
  readLobbyForm();
  lobbyLang = btn.dataset.lang;
  document.querySelectorAll('[data-lang]').forEach((b) => b.classList.toggle('active', b === btn));
  fillLobbyForm();
}));

async function loadLobby() {
  $('#lobby-status').textContent = '';
  try {
    const data = await api('GET', '/api/lobby');
    lobbyDefaults = data.defaults;
    lobbyData = { ...data.settings };
    fillLobbyForm();
  } catch (err) {
    $('#lobby-status').textContent = err.message;
  }
}

lobbyForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  readLobbyForm();
  const status = $('#lobby-status');
  try {
    lobbyData = { ...(await api('PUT', '/api/lobby', lobbyData)).settings };
    fillLobbyForm();
    status.textContent = 'Gespeichert ✓ – in wenigen Sekunden im Spiel';
  } catch (err) {
    status.textContent = err.message;
  }
});

$('#lobby-reset').addEventListener('click', () => {
  if (!lobbyDefaults || !confirm('Alle Texte (Deutsch und Englisch) und Schalter auf die Standardwerte zurücksetzen? (Erst mit „Speichern“ übernommen)')) return;
  lobbyData = { ...lobbyDefaults };
  fillLobbyForm();
  $('#lobby-status').textContent = 'Standardwerte eingesetzt – noch nicht gespeichert';
});
