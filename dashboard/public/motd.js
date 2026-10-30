'use strict';
// Seite "MOTD": Text, Icon, Hover und Wartungsmodus der Serverliste. Nutzt $ und api aus app.js.

const motdForm = $('#motd-form');
let motdIcon = '';
let motdNetwork = 'Netzwerk';

const NAMED_COLORS = {
  black: '#000000', dark_blue: '#0000AA', dark_green: '#00AA00', dark_aqua: '#00AAAA', dark_red: '#AA0000',
  dark_purple: '#AA00AA', gold: '#FFAA00', gray: '#AAAAAA', grey: '#AAAAAA', dark_gray: '#555555', dark_grey: '#555555',
  blue: '#5555FF', green: '#55FF55', aqua: '#55FFFF', red: '#FF5555', light_purple: '#FF55FF', yellow: '#FFFF55', white: '#FFFFFF',
};

const colorOf = (name) => NAMED_COLORS[name] ?? (/^#[0-9a-f]{6}$/i.test(name) ? name : null);

function mix(a, b, t) {
  const p = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const [x, y] = [p(a), p(b)];
  return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, '0')).join('');
}

/**
 * Sehr einfache MiniMessage-Vorschau: Farben, #hex, bold/italic/underlined/strikethrough,
 * gradient, reset und <network>. Reicht für eine ungefähre Ansicht.
 */
function miniMessage(text) {
  const frag = document.createDocumentFragment();
  const stack = [{ color: null, bold: false, italic: false, underlined: false, strike: false, gradient: null }];
  const top = () => stack[stack.length - 1];
  const tokens = text.replace(/<network>/g, motdNetwork).split(/(<[^<>]+>)/);

  // Für Farbverläufe: Länge des Textes innerhalb des Verlaufs vorab zählen
  const emit = (str, style, offset = 0, total = 1) => {
    [...str].forEach((ch, i) => {
      const span = document.createElement('span');
      span.textContent = ch;
      const color = style.gradient ? mix(style.gradient[0], style.gradient[1], total > 1 ? (offset + i) / (total - 1) : 0) : style.color;
      if (color) span.style.color = color;
      if (style.bold) span.style.fontWeight = '700';
      if (style.italic) span.style.fontStyle = 'italic';
      const deco = [style.underlined && 'underline', style.strike && 'line-through'].filter(Boolean).join(' ');
      if (deco) span.style.textDecoration = deco;
      frag.append(span);
    });
  };

  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (!token) continue;
    const tag = /^<(\/?)([a-z_#0-9:]+)>$/i.exec(token);
    if (!tag) {
      const style = top();
      if (style.gradient) {
        emit(token, style, style.gradient.pos, style.gradient.len);
        style.gradient.pos += [...token].length;
      } else {
        emit(token, style);
      }
      continue;
    }
    const [, closing, nameRaw] = tag;
    const name = nameRaw.toLowerCase();
    if (closing) { if (stack.length > 1) stack.pop(); continue; }
    if (name === 'reset') { stack.length = 1; continue; }
    const next = { ...top(), gradient: top().gradient };
    if (name === 'bold' || name === 'b') next.bold = true;
    else if (name === 'italic' || name === 'i' || name === 'em') next.italic = true;
    else if (name === 'underlined' || name === 'u') next.underlined = true;
    else if (name === 'strikethrough' || name === 'st') next.strike = true;
    else if (name.startsWith('gradient')) {
      const colors = name.split(':').slice(1).map(colorOf).filter(Boolean);
      // Textlänge bis zum schließenden </gradient> zählen
      let len = 0;
      for (let j = i + 1; j < tokens.length && !/^<\/gradient/i.test(tokens[j]); j++) {
        if (!/^<[^<>]+>$/.test(tokens[j])) len += [...tokens[j]].length;
      }
      next.gradient = colors.length >= 2 ? Object.assign([colors[0], colors[colors.length - 1]], { pos: 0, len }) : null;
    } else if (colorOf(name.replace(/^color:/, ''))) {
      next.color = colorOf(name.replace(/^color:/, ''));
      next.gradient = null;
    } else {
      emit(token, top()); // unbekannter Tag: als Text zeigen
      continue;
    }
    stack.push(next);
  }
  return frag;
}

function updateMotdPreview() {
  const f = motdForm.elements;
  const maintenance = f.maintenance.checked;
  $('#motd-preview-1').replaceChildren(miniMessage(f.line1.value));
  $('#motd-preview-2').replaceChildren(miniMessage(maintenance ? f.maintenanceLine2.value : f.line2.value));
  const max = Number(f.maxPlayers.value) || 100;
  $('#motd-preview-count').textContent = maintenance ? '' : `0/${max}`;
  $('#motd-preview-ping').textContent = maintenance ? 'Wartung / Maintenance' : '▂▄▆█';
  $('#motd-preview-ping').style.color = maintenance ? '#ff5555' : '';
  $('#motd-preview-ping').style.letterSpacing = maintenance ? '0' : '';
  const fallback = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="#3a3a3a"/><text x="32" y="40" font-size="26" text-anchor="middle" fill="#888">?</text></svg>');
  $('#motd-preview-icon').src = motdIcon || fallback;
  $('#motd-icon').src = motdIcon || fallback;
}

motdForm.addEventListener('input', updateMotdPreview);
// In der Vorschau steht die Adresse, unter der das Dashboard läuft (meist dieselbe wie der Server)
if (location.hostname !== 'localhost') $('#motd-preview-name').textContent = location.hostname;

async function loadMotd() {
  $('#motd-status').textContent = '';
  try {
    const data = await api('GET', '/api/motd');
    motdNetwork = data.networkName || 'Netzwerk';
    const s = data.settings;
    for (const field of motdForm.elements) {
      if (!field.name || !(field.name in s)) continue;
      if (field.type === 'checkbox') field.checked = !!s[field.name];
      else field.value = s[field.name];
    }
    motdIcon = s.icon || '';
    updateMotdPreview();
  } catch (err) {
    $('#motd-status').textContent = err.message;
  }
}

// Beliebiges Bild → quadratisch aus der Mitte zuschneiden → 64×64 PNG
$('#motd-icon-input').addEventListener('change', (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file) return;
  const img = new Image();
  img.onload = () => {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    const ctx = canvas.getContext('2d');
    const side = Math.min(img.width, img.height);
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, 64, 64);
    motdIcon = canvas.toDataURL('image/png');
    URL.revokeObjectURL(img.src);
    updateMotdPreview();
    $('#motd-status').textContent = 'Neues Icon – noch nicht gespeichert';
  };
  img.onerror = () => { $('#motd-status').textContent = 'Das Bild konnte nicht gelesen werden'; };
  img.src = URL.createObjectURL(file);
});

$('#motd-icon-remove').addEventListener('click', () => {
  motdIcon = '';
  updateMotdPreview();
  $('#motd-status').textContent = 'Icon entfernt – noch nicht gespeichert';
});

motdForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = motdForm.elements;
  const body = {
    line1: f.line1.value,
    line2: f.line2.value,
    hover: f.hover.value,
    maxPlayers: Number(f.maxPlayers.value) || 0,
    maintenance: f.maintenance.checked,
    maintenanceLine2: f.maintenanceLine2.value,
    maintenanceKick: f.maintenanceKick.value,
    icon: motdIcon,
  };
  if (body.maintenance && !confirm('Wartungsmodus einschalten? Spieler ohne das Recht network.maintenance.bypass kommen dann nicht mehr rein (wer schon online ist, bleibt).')) return;
  try {
    await api('PUT', '/api/motd', body);
    $('#motd-status').textContent = 'Gespeichert ✓ – in wenigen Sekunden in der Serverliste';
  } catch (err) {
    $('#motd-status').textContent = err.message;
  }
});
