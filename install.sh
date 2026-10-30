#!/usr/bin/env bash
# Installiert bzw. aktualisiert das Minecraft-Netzwerk (Velocity + Lobby + Dashboard).
# Kann jederzeit erneut ausgeführt werden: vorhandene Passwörter, Einstellungen und Welten bleiben erhalten.
#
# Ohne Rückfragen (z. B. per Skript):
#   ACCEPT_EULA=yes [DOMAIN=panel.example.de ACCEPT_LE=yes LE_EMAIL=du@example.de] sudo -E bash install.sh
set -euo pipefail

INSTALL_DIR=/opt/mcnetwork
DATA_DIR=$INSTALL_DIR/data
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
yes_answer() { [[ "${1,,}" =~ ^(ja|j|yes|y)$ ]]; }

if [[ $EUID -ne 0 ]]; then
  red "Bitte als root ausführen:  sudo bash install.sh"
  exit 1
fi

INTERACTIVE=true
[[ -t 0 ]] || INTERACTIVE=false
FIRST_INSTALL=true
[[ -f "$INSTALL_DIR/.env" ]] && FIRST_INSTALL=false

# ---------- Grundpakete & Docker ----------
if command -v apt-get >/dev/null 2>&1; then
  missing=()
  for pkg in curl ca-certificates; do dpkg -s "$pkg" >/dev/null 2>&1 || missing+=("$pkg"); done
  if (( ${#missing[@]} )); then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing[@]}" >/dev/null
  fi
fi
if ! command -v docker >/dev/null 2>&1; then
  green "Docker wird installiert ..."
  curl -fsSL https://get.docker.com | sh
fi
if ! docker compose version >/dev/null 2>&1; then
  red "docker compose fehlt. Bitte das Paket docker-compose-plugin installieren."
  exit 1
fi
systemctl enable --now docker >/dev/null 2>&1 || true

# Port 25565 muss frei sein (z. B. kein alter Minecraft-Server)
if $FIRST_INSTALL && command -v ss >/dev/null 2>&1; then
  if ss -tlnp 2>/dev/null | grep -E ':25565\b' | grep -vq docker-proxy; then
    red "Port 25565 ist schon belegt:"
    ss -tlnp | grep -E ':25565\b' || true
    red "Bitte den alten Minecraft-Server zuerst stoppen, dann install.sh erneut starten."
    exit 1
  fi
fi

# ---------- Dateien nach /opt/mcnetwork ----------
mkdir -p "$INSTALL_DIR"
if [[ "$SRC_DIR" != "$INSTALL_DIR" ]]; then
  green "Kopiere Dateien nach $INSTALL_DIR ..."
  cp -r "$SRC_DIR"/dashboard "$SRC_DIR"/plugins "$SRC_DIR"/velocity "$SRC_DIR"/docker-compose.yml "$INSTALL_DIR"/
  cp "$SRC_DIR"/install.sh "$INSTALL_DIR"/
  [[ -f "$SRC_DIR/README.md" ]] && cp "$SRC_DIR/README.md" "$INSTALL_DIR"/
fi
cd "$INSTALL_DIR"

# ---------- Konfiguration (.env) ----------
rand() { local s; s="$(head -c 128 /dev/urandom | base64 -w0 | tr -dc 'A-Za-z0-9')"; printf '%s' "${s:0:$1}"; }

if [[ ! -f .env ]]; then
  echo
  echo "Minecraft-Server dürfen nur betrieben werden, wenn du die Minecraft-EULA akzeptierst:"
  echo "  https://aka.ms/MinecraftEULA"
  eula="${ACCEPT_EULA:-}"
  if [[ -z "$eula" ]] && $INTERACTIVE; then
    read -rp "Akzeptierst du die EULA? (ja/nein): " eula
  fi
  if ! yes_answer "$eula"; then
    red "Ohne EULA geht es leider nicht. Abbruch."
    exit 1
  fi

  admin_pw=""
  if $INTERACTIVE; then
    echo
    read -rsp "Admin-Passwort fürs Dashboard (leer = zufällig erzeugen): " admin_pw; echo
  fi
  if [[ -z "$admin_pw" ]]; then
    admin_pw="$(rand 20)"
    GENERATED_PW="$admin_pw"
  fi

  cat > .env <<EOF
# Wird von install.sh erzeugt. Diese Datei geheim halten!
DATA_DIR=$DATA_DIR
DASHBOARD_PORT=8080
ADMIN_PASSWORD=$admin_pw
SESSION_SECRET=$(rand 48)
DASHBOARD_API_KEY=$(rand 40)
FORWARDING_SECRET=$(rand 32)
# Wird automatisch auf true gesetzt, wenn das Dashboard per HTTPS läuft
COOKIE_SECURE=false
LOBBY_VERSION=LATEST
LOBBY_MEMORY=1G
EOF
  chmod 600 .env
fi

# Neue Einstellungen ergänzen, falls die .env von einer älteren Version stammt
add_secret() {
  grep -q "^$1=" .env || echo "$1=$(rand "$2")" >> .env
}
add_secret DB_PASSWORD 32
add_secret DB_ROOT_PASSWORD 32
add_secret LP_API_KEY 40
grep -q "^DASHBOARD_DOMAIN=" .env || echo "DASHBOARD_DOMAIN=" >> .env

# ---------- Domain für das Dashboard (optional, mit HTTPS) ----------
# shellcheck disable=SC1091
source .env
DOMAIN="${DOMAIN:-$DASHBOARD_DOMAIN}"
if [[ -z "$DOMAIN" ]] && $FIRST_INSTALL && $INTERACTIVE; then
  echo
  echo "Soll das Dashboard über eine eigene (Sub-)Domain mit HTTPS erreichbar sein?"
  echo "Die Domain muss schon per DNS (A-Eintrag) auf diesen Server zeigen, z. B. panel.deine-domain.de"
  read -rp "Domain (leer = nur per SSH-Tunnel erreichbar): " DOMAIN
fi
sed -i "s|^DASHBOARD_DOMAIN=.*|DASHBOARD_DOMAIN=$DOMAIN|" .env

# ---------- Velocity vorbereiten ----------
mkdir -p "$DATA_DIR/velocity/plugins" "$DATA_DIR/servers" "$DATA_DIR/dashboard" build
[[ -f "$DATA_DIR/velocity/velocity.toml" ]] || cp velocity/velocity.toml "$DATA_DIR/velocity/velocity.toml"
printf '%s' "$FORWARDING_SECRET" > "$DATA_DIR/velocity/forwarding.secret"

# ---------- Plugins bauen (in Docker, kein Java auf dem Server nötig) ----------
green "Baue Plugins (beim ersten Mal dauert das ein paar Minuten) ..."
for plugin in velocity-sync lobby-selector network-chat; do
  docker run --rm \
    -v "$INSTALL_DIR/plugins/$plugin":/src \
    -v mcnetwork-gradle-cache:/home/gradle/.gradle \
    -w /src gradle:9-jdk25 \
    gradle build --no-daemon -q
  cp "$INSTALL_DIR/plugins/$plugin/build/libs/$plugin.jar" build/
done
cp build/velocity-sync.jar "$DATA_DIR/velocity/plugins/"

chown -R 1000:1000 "$DATA_DIR/velocity" "$DATA_DIR/servers"

# ---------- HTTPS mit nginx + Let's Encrypt ----------
HTTPS=false
if [[ -n "$DOMAIN" ]]; then
  if [[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
    HTTPS=true
  elif ! command -v apt-get >/dev/null 2>&1; then
    yellow "HTTPS wird automatisch nur auf Debian/Ubuntu eingerichtet – überspringe."
  else
    server_ip="$(curl -s -4 -m 5 https://ifconfig.me || true)"
    domain_ip="$(getent ahostsv4 "$DOMAIN" | awk '{print $1; exit}' || true)"
    if [[ -n "$server_ip" && "$domain_ip" != "$server_ip" ]]; then
      yellow "Achtung: $DOMAIN zeigt auf '${domain_ip:-nichts}', dieser Server hat $server_ip."
      yellow "Das Zertifikat klappt erst, wenn der DNS-Eintrag stimmt."
    fi
    echo
    echo "Für das HTTPS-Zertifikat muss man den Bedingungen von Let's Encrypt zustimmen:"
    echo "  https://letsencrypt.org/repository/"
    le="${ACCEPT_LE:-}"
    if [[ -z "$le" ]] && $INTERACTIVE; then read -rp "Akzeptierst du die Let's-Encrypt-Bedingungen? (ja/nein): " le; fi
    if yes_answer "$le"; then
      email="${LE_EMAIL:-}"
      if [[ -z "$email" ]] && $INTERACTIVE; then read -rp "E-Mail für Ablauf-Warnungen (optional, leer = keine): " email; fi
      green "Richte nginx und HTTPS für $DOMAIN ein ..."
      DEBIAN_FRONTEND=noninteractive apt-get update -qq
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx certbot python3-certbot-nginx >/dev/null
      cat > /etc/nginx/sites-available/mcnetwork-dashboard <<NGINX
# Dashboard des Minecraft-Netzwerks (HTTPS ergänzt certbot automatisch)
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    client_max_body_size 110m;  # Plugin-Uploads bis 100 MB

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
}
NGINX
      ln -sf /etc/nginx/sites-available/mcnetwork-dashboard /etc/nginx/sites-enabled/mcnetwork-dashboard
      nginx -t && systemctl reload nginx
      if [[ -n "$email" ]]; then mail_arg=(--email "$email"); else mail_arg=(--register-unsafely-without-email); fi
      if certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos "${mail_arg[@]}" --redirect; then
        HTTPS=true
      else
        yellow "Zertifikat fehlgeschlagen – prüfe den DNS-Eintrag und starte install.sh danach erneut."
      fi
    else
      yellow "Ohne Zustimmung kein Zertifikat – das Dashboard bleibt nur per SSH-Tunnel erreichbar."
    fi
  fi
fi
if $HTTPS; then
  sed -i "s/^COOKIE_SECURE=.*/COOKIE_SECURE=true/" .env
fi

# Firewall (falls ufw aktiv ist): Minecraft und Web freigeben
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 25565/tcp >/dev/null
  if $HTTPS; then ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; fi
fi

# ---------- Starten ----------
UPDATE=false
docker ps --format '{{.Names}}' | grep -qx mc-velocity && UPDATE=true

green "Starte Dashboard und Proxy ..."
docker compose up -d --build

# Docker hinterlässt beim Neuerstellen manchmal einen Namen wie "abc123_mc-dashboard"
fix_name() {
  docker ps --format '{{.Names}}' | grep -qx "$1" && return
  local old
  old="$(docker ps --format '{{.Names}}' | grep -E "^[0-9a-f]+_$1$" | head -1 || true)"
  [[ -n "$old" ]] && docker rename "$old" "$1"
  return 0
}
fix_name mc-dashboard

# Bei einem Update: Proxy und Lobby neu starten, damit die neuen Plugins geladen werden
if $UPDATE; then
  docker restart mc-velocity >/dev/null
fi
if $UPDATE && docker ps --format '{{.Names}}' | grep -qx mc-lobby; then
  docker stop mc-lobby >/dev/null
  docker restart mc-dashboard >/dev/null
fi

IP="$(curl -s -4 -m 5 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
echo
green "Fertig!"
echo "  Minecraft:   ${IP:-DEINE-IP}  (Port 25565)"
if $HTTPS; then
  echo "  Dashboard:   https://$DOMAIN"
else
  echo "  Dashboard:   per SSH-Tunnel – auf deinem PC:"
  echo "                 ssh -L 8080:localhost:8080 root@${IP:-DEINE-IP}"
  echo "               dann im Browser http://localhost:8080 öffnen"
fi
if [[ -n "${GENERATED_PW:-}" ]] && ! $INTERACTIVE; then
  echo "  Passwort:    steht in $INSTALL_DIR/.env (ADMIN_PASSWORD)"
elif [[ -n "${GENERATED_PW:-}" ]]; then
  echo "  Passwort:    $GENERATED_PW   (steht auch in $INSTALL_DIR/.env)"
fi
echo
echo "Die Lobby wird jetzt im Hintergrund heruntergeladen und eingerichtet (1-3 Minuten)."
echo "Den Fortschritt siehst du im Dashboard unter Server -> Lobby -> Konsole."
