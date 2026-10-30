# Minecraft-Netzwerk mit Dashboard

Velocity-Proxy, Lobby mit Kompass-Serverauswahl und ein Web-Dashboard, in dem du per Klick neue Server erstellst.

```
Spieler ──▶ Velocity (Port 25565) ──▶ Lobby  (Kompass → GUI)
                 │                 ├─▶ Survival
                 │                 └─▶ ... (alles, was du im Dashboard anlegst)
                 ▲
     velocity-sync-Plugin holt sich die Serverliste
                 │
Du ──▶ Dashboard (https://deine-domain) ── erstellt/startet Server als Docker-Container
```

**Enthalten:** Velocity 4 (Proxy), Lobby-System mit Kompass-Menü, Dashboard (Server, Plugins von Modrinth, Dateien, Konsole, Rechte mit LuckPerms, Spieler/Moderation, Lobby & Tab, MOTD), netzwerkweite Tab-Liste, Netzwerk-Chat, Vanish, Admin-GUI, Server-Verwaltung im Spiel und alle Texte auf Deutsch und Englisch.

## Installation auf einem neuen VPS

**Du brauchst:**
- einen VPS mit **Ubuntu 22.04/24.04 oder Debian 12**, mindestens **4 GB RAM** (besser 8 GB) und root-Zugang
- optional eine **(Sub-)Domain für das Dashboard**, z. B. `panel.deine-domain.de`, deren DNS-A-Eintrag schon auf die IP des Servers zeigt. Ohne Domain erreichst du das Dashboard per SSH-Tunnel.

**1. Auf dem Server einloggen und installieren:**

```bash
ssh root@DEINE-SERVER-IP
```

```bash
apt update && apt install -y git && git clone https://github.com/Napguin/MCServer-Manager.git mcnetwork && cd mcnetwork && bash install.sh
```

Ist das Repository privat, fragt `git clone` nach deinem GitHub-Namen und als Passwort nach einem **Personal Access Token** (GitHub → Settings → Developer settings → Personal access tokens, Recht „Contents: Read“).

**2. Das Skript fragt nacheinander:**
1. ob du die **Minecraft-EULA** akzeptierst
2. nach dem **Admin-Passwort** fürs Dashboard (leer = zufällig erzeugt)
3. nach der **Domain** fürs Dashboard (leer = nur per SSH-Tunnel)
4. bei einer Domain: ob du den **Let's-Encrypt-Bedingungen** zustimmst, und optional nach einer E-Mail

Danach installiert es Docker, baut die Plugins (beim ersten Mal ein paar Minuten), richtet bei einer Domain nginx mit HTTPS ein und startet alles. Am Ende stehen die Adressen da.

**3. Erste Schritte im Dashboard:**
1. **Rechte** → „Standard-Rollen anlegen“, dann unter „Spieler“ deinen Minecraft-Namen suchen und dir die Rolle **Owner** geben.
2. **Lobby & Tab** → Netzwerk-Namen eintragen.
3. **MOTD** → Text und Server-Icon für die Serverliste festlegen.
4. **Server** → „+ Server hinzufügen“, z. B. Survival.
5. In Minecraft mit der IP (oder Domain) des Servers verbinden.

**Ports:** Nach außen nötig sind nur **25565** (Minecraft) und bei einer Domain **80/443** (Dashboard). Ist `ufw` aktiv, gibt das Skript diese Ports selbst frei. Das Dashboard (8080), die Datenbank und die Unterserver sind von außen nicht erreichbar.

Alles liegt danach in `/opt/mcnetwork`, die Welten in `/opt/mcnetwork/data/servers/<id>`, die Zugangsdaten in `/opt/mcnetwork/.env`.

## Benutzung

- **Server hinzufügen:** Namen, Servertyp, Icon-Item (z. B. `DIAMOND_SWORD`), Beschreibung und optional den Platz im GUI eingeben. Der Server wird heruntergeladen, für Velocity eingerichtet, einmal neu gestartet und erscheint danach automatisch im Kompass-Menü. Beim ersten Mal dauert das 1–3 Minuten.
- **Servertypen:**
  - **Paper** (empfohlen): für Plugins. Läuft auch mit allen Spigot- und Bukkit-Plugins.
  - **Purpur:** wie Paper, nur mit mehr Einstellungen.
  - **Folia:** für sehr viele Spieler. Viele Plugins laufen dort nicht.
  - **Fabric:** für Mods. Fabric API und FabricProxy-Lite (für Velocity) werden automatisch installiert.
- **Plugins / Mods:** Der Knopf auf jeder Serverkarte bietet drei Möglichkeiten:
  - **Modrinth durchsuchen:** zeigt nur Plugins, die zu Servertyp und Minecraft-Version passen. Ein Klick auf „Installieren“ lädt das Plugin herunter, prüft die Datei und installiert benötigte Abhängigkeiten mit.
  - **.jar hochladen:** für Plugins von anderen Seiten (Spigot, GitHub …), per Drag & Drop.
  - **Installiert:** Liste aller Plugins mit „Aktualisieren“ (bei Modrinth-Plugins) und „Entfernen“.
  - Danach den Server neu starten. Der Dialog bietet dafür einen eigenen Knopf an.
- **Dateien:** Hier kannst du die Ordner des Servers durchsuchen und Config-Dateien (`.yml`, `.properties`, `.json`, `.toml` …) direkt im Browser bearbeiten. Speichern geht mit Strg+S. Danach meist neu starten.
- **Konsole:** zeigt die Logs. Dort kannst du Befehle senden, z. B. `op DeinName`.
- **Bearbeiten:** Name, Beschreibung, Icon, Slot, „geschützt“ und „ausblenden“ wirken nach spätestens 3 Sekunden.
- **Löschen:** entfernt den Server. Auf Wunsch werden auch die Welt und alle Dateien gelöscht.
- **Im Spiel:** `/admin server` (oder `/admin` → „Server verwalten“) zeigt alle Server. Dort kannst du starten, stoppen, neu starten, hinspringen, schützen, ausblenden und den Wartungsmodus umschalten. Dafür braucht man das Recht `network.servers`.

## Lobby-System (Seite „Lobby“)

Die Lobby ist ein fertiges Lobby-System:

- **Lobby-Welt:** Beim ersten Start entsteht eine eigene Void-Welt („hub“) mit einer schwebenden Spawn-Insel. Darauf stehen ein Brunnen in der Mitte, Laternen, Wege und vier Launchpads.
- **Items:**
  - Kompass mit Serverauswahl
  - „Spieler verstecken“ (an/aus)
- **Bewegung:**
  - Doppelsprung
  - Launchpads (goldene Druckplatten)
  - Wer ins Void fällt, landet automatisch wieder am Spawn.
- **Anzeige:**
  - Scoreboard mit Rang, Spielern online und Servern
  - Tab-Kopf- und Fußzeile
  - Willkommens-Titel
  - Hologramm über dem Brunnen
  - optional eine Bossbar
- **Schutz:** Kein Schaden, kein Hunger, kein Abbauen, keine Truhen oder Türen, keine Mobs, kein Wetter, immer Mittag.
- **Im Dashboard** stellst du alle Texte (mit Farben und Platzhaltern) und alle Schalter ein. Änderungen sind nach wenigen Sekunden im Spiel sichtbar.

**Befehle im Spiel:**
- `/build`: Baumodus an oder aus, um die Lobby umzubauen. Braucht das Recht `lobby.build` (Admin und Owner haben es).
- `/setspawn`: setzt den Spawn auf deine aktuelle Position. Braucht das Recht `lobby.admin`.
- `/sethologram`: setzt das Hologramm auf deine Position. Braucht das Recht `lobby.admin`.
- `/spawn`: zurück zum Spawn.
- `/hub` oder `/lobby`: geht von jedem Server zurück in die Lobby.
- `/servers`: öffnet die Serverauswahl.

**Eigene Map statt Insel:**
1. Lobby im Dashboard stoppen.
2. In `/opt/mcnetwork/data/servers/lobby/world/dimensions/minecraft/hub/` die Ordner `region`, `entities` und `poi` durch die gleichnamigen Ordner deiner Map ersetzen.
3. `chown -R 1000:1000` auf den Ordner ausführen und die Lobby starten.
4. Im Spiel mit `/setspawn` und `/sethologram` Spawn und Hologramm setzen.

Um die Insel neu bauen zu lassen, löschst du den Ordner `hub` und die Datei `plugins/LobbySelector/hub-gebaut.txt`.

## Serverliste (Seite „MOTD“)

- Zwei Zeilen MOTD mit Farben und Farbverläufen, dazu eine Live-Vorschau wie in der Minecraft-Serverliste.
- **Server-Icon:** Jedes Bild lässt sich hochladen, es wird automatisch auf 64×64 Pixel zugeschnitten.
- **Hover-Text** über der Spielerzahl: eigener Text, oder automatisch die Spieler, die online sind (Unsichtbare werden nicht mitgezählt).
- **Maximale Spielerzahl** in der Anzeige.
- **Wartungsmodus:** Die Serverliste zeigt rot „Wartung“, und nur wer das Recht `network.maintenance.bypass` hat, kommt rein.
- Änderungen sind nach wenigen Sekunden aktiv, ohne Neustart.

## Sprachen

Alle Texte im Spiel gibt es auf Deutsch und Englisch: Menüs, Items, Meldungen, Admin-GUI, Scoreboard, Tab-Liste und Ban-Nachrichten. Spieler mit deutschem Minecraft sehen Deutsch, alle anderen Englisch. Die englischen Texte für Lobby und Tab bearbeitest du unter „Lobby & Tab“ über den Umschalter 🇬🇧 English.

## Tab-Liste, Netzwerk-Chat, Vanish & Moderation

- **Tab-Liste:** Jeder sieht alle Spieler im ganzen Netzwerk, mit dem Server in Klammern dahinter. In der Kopfzeile steht, auf welchem Server du bist. Die Fußzeile zeigt alle Server mit ihrer Spielerzahl. Die Texte stellst du im Dashboard unter „Lobby & Tab“ ein.
- **Netzwerk-Chat:** Nachrichten erscheinen auf allen Servern, mit `[Servername]` davor. Abschaltbar unter „Lobby & Tab“.
- **EssentialsX** wird automatisch auf allen Paper- und Purpur-Spielservern installiert (nicht in der Lobby). Es bringt `/msg`, `/home`, `/tpa`, `/fly` und vieles mehr mit.
- **Vanish:** `/vanish` (oder `/v`) macht dich im **ganzen Netzwerk** unsichtbar: auf allen Servern, in der Tab-Liste, bei Join-Nachrichten und in den Spielerzahlen. Wer das Recht `network.vanish.see` hat, sieht dich trotzdem.
- **Admin-GUI:** `/admin` öffnet ein Menü mit allen Spielern im Netzwerk. Pro Spieler kannst du:
  - hinspringen (auch auf einen anderen Server)
  - Spielmodus ändern und Inventar ansehen (nur auf demselben Server)
  - Rang setzen
  - kicken, muten (mit Dauer) und bannen (mit Dauer)
- **Befehle:** `/kick`, `/ban <spieler> [7d|perm] [grund]`, `/unban`, `/mute <spieler> [1h|perm] [grund]` und `/unmute` gelten netzwerkweit. Sie ersetzen die Versionen von Essentials, die nur auf einem Server gelten würden.
- **Dashboard → „Spieler“:** zeigt, wer online ist und auf welchem Server. Dort kannst du kicken, bannen und muten, laufende Banns und Mutes wieder aufheben und den Verlauf einsehen.

**Geschützte Server:** Beim Anlegen oder unter „Bearbeiten“ kannst du einen Server als „geschützt“ markieren. Dann darf ihn nur betreten, wer das Recht `network.server.<id>` hat (z. B. `network.server.survival`). Der Proxy prüft das bei jedem Wechsel, auch bei `/server` oder über das Admin-Menü. Im Kompass-Menü sehen Spieler ohne Recht den Server mit Schloss und „Kein Zugang“. Die Lobby ist immer offen.

Die nötigen Rechte heißen `network.admin`, `network.vanish`, `network.vanish.see`, `network.kick`, `network.ban`, `network.mute` und `network.rank`. Admin und Owner haben automatisch alle (`*`).

## Rechte, Rollen und Prefixe (Seite „Rechte“)

LuckPerms läuft automatisch auf dem Proxy und auf allen Servern. Alle teilen sich eine Datenbank (MariaDB), deshalb gilt jede Änderung im Dashboard sofort im ganzen Netzwerk. Ein Neustart ist nicht nötig.

- **Rollen:** Name, Anzeigename, Prefix, Suffix und Gewicht festlegen und auswählen, von welchen Rollen sie erbt. Prefixe nutzen Minecraft-Farbcodes (`&c`, `&l`, `&#FF8800` …). Die Farbknöpfe und die Live-Vorschau helfen dabei.
- **„Standard-Rollen anlegen“** erstellt Spieler → VIP → Moderator → Admin → Owner. Admin und Owner haben alle Rechte (`*`).
- **Rechte** gibst du einer Rolle oder einem einzelnen Spieler:
  - erlaubt oder verboten
  - auf allen Servern oder nur auf einem bestimmten
  - dauerhaft oder zeitlich begrenzt (z. B. VIP für 30 Tage)
- **Spieler:** Spieler per Namen suchen, auch wenn sie noch nie online waren. Dann kannst du Rollen geben oder entfernen, einen eigenen Prefix setzen und einzelne Rechte vergeben.
- **Chat und Tab-Liste:** Das Plugin NetworkChat zeigt den Prefix im Chat und in der Tab-Liste an. Dort wird nach Gewicht sortiert. Das Chat-Format lässt sich in `plugins/NetworkChat/config.yml` anpassen (über „Dateien“).

**Gut zu wissen:**
- Server-Namen werden für LuckPerms ohne Bindestrich geschrieben (`mein-server` → `meinserver`).
- Fabric-Server bekommen LuckPerms auch. Prefixe im Chat zeigt dort aber erst eine Chat-Mod an, z. B. „Styled Chat“.
- Mach dich zuerst selbst zum Owner: auf „Rechte“ → „Spieler“ deinen Namen suchen und die Rolle „Owner“ geben.

## Dashboard ohne Domain (SSH-Tunnel)

Ohne Domain ist das Dashboard aus Sicherheitsgründen nur auf dem Server selbst erreichbar. Von deinem PC aus öffnest du einen Tunnel:

```bash
ssh -L 8080:localhost:8080 root@DEINE-SERVER-IP
```

Solange das Fenster offen ist, erreichst du das Dashboard unter `http://localhost:8080`. Eine Domain kannst du später nachrüsten: `DOMAIN=panel.deine-domain.de bash /opt/mcnetwork/install.sh`.

## Aktualisieren / Ändern

- **Neue Version installieren:**
  ```bash
  cd ~/mcnetwork && git pull && bash install.sh
  ```
  Passwörter, Einstellungen und Welten bleiben erhalten.
- **Admin-Passwort ändern:** in `/opt/mcnetwork/.env` anpassen, dann `cd /opt/mcnetwork && docker compose up -d dashboard`.
- **Velocity-Einstellungen:** `/opt/mcnetwork/data/velocity/velocity.toml`, danach im Dashboard „Proxy neu starten“.
- **Kompass-Item und GUI-Titel:** `/opt/mcnetwork/data/servers/lobby/plugins/LobbySelector/config.yml`.

## Velocity statt BungeeCord?

Velocity ist der moderne Nachfolger von BungeeCord. Es macht dasselbe, ist aber schneller und sicherer, weil die Spielerdaten signiert an die Unterserver weitergegeben werden. Außerdem kann man dort Server zur Laufzeit hinzufügen, was das Dashboard braucht.

## Fehlersuche

| Problem | Lösung |
|---|---|
| Server bleibt auf „Wird eingerichtet“ | Konsole öffnen. Meist lädt der Server noch herunter oder hat zu wenig RAM. |
| „Proxy offline“ im Dashboard | Proxy-Logs ansehen. `docker logs mc-velocity` auf dem Server. |
| Lobby-Insel fehlt / Spieler fallen | Lobby-Konsole prüfen, ob „Baue die Lobby-Insel“ erscheint. Sonst wie oben beschrieben neu bauen lassen. |
| Kompass öffnet leeres Menü | Lobby-Konsole prüfen, ob „Dashboard nicht erreichbar“ erscheint. |
| „LuckPerms ist nicht erreichbar“ | Beim ersten Start 1–2 Minuten warten. Sonst `docker logs mc-luckperms-api` und `docker logs mc-mariadb` prüfen. |
| Prefix erscheint nicht im Chat | Server einmal neu starten, damit NetworkChat geladen wird. Bei Fabric ist eine Chat-Mod nötig. |
| Plugin-Build schlägt fehl | Ausgabe von `install.sh` prüfen. Die Plugins werden gegen Paper 26.3 und Velocity 4.3 gebaut. |
| Alle Container ansehen | `docker ps -a --filter name=mc-` |
