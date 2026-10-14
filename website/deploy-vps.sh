#!/usr/bin/env bash
# Installs (or updates) the PairDesk website on a Linux server, without
# touching anything that is already there:
#   - files in /var/www/pairdesk-site (its own folder),
#   - served on a free port (8080 or the next free one), never 80/443,
#   - by nginx if it is installed (one new file of its own, checked with
#     `nginx -t` before a graceful reload, removed again if the check fails),
#     otherwise by a small Python server (its own systemd service).
# Run again to update: same folder, same port.
#
#   curl -fsSL https://raw.githubusercontent.com/Azukkia/PairDesk/claude/remote-desktop-app-w7i925/website/deploy-vps.sh | sudo bash

set -euo pipefail

BASE="${PAIRDESK_SITE_BASE:-https://raw.githubusercontent.com/Azukkia/PairDesk/claude/remote-desktop-app-w7i925/website}"
FILES="index.html logo.svg accueil.jpg controle.jpg panneau.png connexion.mp4 connexion-poster.jpg controle.mp4 controle-poster.jpg"
DIR=/var/www/pairdesk-site
PORT_FILE=/etc/pairdesk-site.port
NGINX_CONF=pairdesk-site.conf
SERVICE=pairdesk-site
SERVER_PY=/opt/pairdesk-site/server.py

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mErreur :\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "lancez le script avec sudo."
command -v curl >/dev/null || die "curl est nécessaire."

port_used() { ss -ltnH "( sport = :$1 )" 2>/dev/null | grep -q .; }

# ── 1. The port: the one of a previous install, else the first free one from 8080.
if [ -f "$PORT_FILE" ]; then
  PORT="$(cat "$PORT_FILE")"
  say "Mise à jour de l'installation existante (port $PORT)."
else
  PORT=8080
  while port_used "$PORT"; do PORT=$((PORT + 1)); done
  say "Port libre choisi : $PORT."
fi

# ── 2. The files, downloaded to a temporary folder first.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for f in $FILES; do
  curl -fsSL "$BASE/$f" -o "$TMP/$f" || die "téléchargement de $f impossible."
done
mkdir -p "$DIR"
cp "$TMP"/* "$DIR"/
chmod 755 "$DIR"
chmod 644 "$DIR"/*
say "Site copié dans $DIR."

# ── 3. Who serves it.
NGINX_TARGET=""
if command -v nginx >/dev/null 2>&1; then
  if grep -Eqs 'include[[:space:]]+/etc/nginx/conf\.d/\*\.conf' /etc/nginx/nginx.conf; then
    NGINX_TARGET="/etc/nginx/conf.d/$NGINX_CONF"
  elif grep -Eqs 'include[[:space:]]+/etc/nginx/sites-enabled/\*' /etc/nginx/nginx.conf; then
    NGINX_TARGET="/etc/nginx/sites-enabled/$NGINX_CONF"
  fi
fi

# $1: extra listen line (IPv6) or nothing.
nginx_conf() {
  cat <<EOF
# PairDesk website (added by website/deploy-vps.sh; delete this file to remove it).
server {
    listen $PORT;
$1
    server_name _;
    root $DIR;
    index index.html;
    location / { try_files \$uri \$uri/ =404; }
    location ~* \.(mp4|jpg|png|svg)\$ { expires 7d; add_header Cache-Control "public"; }
}
EOF
}

use_nginx() {
  local previous=""
  if [ -f "$NGINX_TARGET" ]; then
    previous="$TMP/$NGINX_CONF.previous"
    cp "$NGINX_TARGET" "$previous"
  fi
  # Our file back as it was (or gone): nginx's configuration stays valid.
  undo() { if [ -n "$previous" ]; then cp "$previous" "$NGINX_TARGET"; else rm -f "$NGINX_TARGET"; fi; }
  # IPv6 too when the server has it.
  nginx_conf "    listen [::]:$PORT;" > "$NGINX_TARGET"
  if ! nginx -t >/dev/null 2>&1; then
    nginx_conf "" > "$NGINX_TARGET"
    if ! nginx -t >/dev/null 2>&1; then
      undo
      return 1
    fi
  fi
  if systemctl is-active --quiet nginx; then
    systemctl reload nginx
  else
    say "nginx est installé mais arrêté : il n'est pas démarré par ce script."
    undo
    return 1
  fi
  say "Servi par nginx (nouveau fichier $NGINX_TARGET, rien d'autre modifié)."
}

use_python() {
  command -v python3 >/dev/null || die "ni nginx ni python3 : impossible de servir le site."
  mkdir -p "$(dirname "$SERVER_PY")"
  cat > "$SERVER_PY" <<'EOF'
# Static file server for the PairDesk website, with byte ranges (video).
import http.server, os, re, socket, sys

class Handler(http.server.SimpleHTTPRequestHandler):
    def send_head(self):
        rng = self.headers.get('Range')
        path = self.translate_path(self.path)
        if not rng or os.path.isdir(path) or not os.path.isfile(path):
            return super().send_head()
        m = re.match(r'bytes=(\d*)-(\d*)$', rng.strip())
        size = os.path.getsize(path)
        if not m or (m.group(1) == '' and m.group(2) == ''):
            return super().send_head()
        if m.group(1) == '':
            start, end = max(0, size - int(m.group(2))), size - 1
        else:
            start = int(m.group(1))
            end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
        if start >= size or start > end:
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{size}')
            self.end_headers()
            return None
        f = open(path, 'rb')
        f.seek(start)
        self.send_response(206)
        self.send_header('Content-Type', self.guess_type(path))
        self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.send_header('Content-Length', str(end - start + 1))
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        remaining = getattr(self, '_remaining', None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(65536, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)
        self._remaining = None

    def end_headers(self):
        self.send_header('Accept-Ranges', 'bytes')
        super().end_headers()

    def log_message(self, *args):
        pass

port, root = int(sys.argv[1]), sys.argv[2]
os.chdir(root)
class Server(http.server.ThreadingHTTPServer):
    address_family = socket.AF_INET6
    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()
try:
    server = Server(('::', port), Handler)
except OSError:
    server = http.server.ThreadingHTTPServer(('0.0.0.0', port), Handler)
server.serve_forever()
EOF
  cat > "/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=PairDesk website (port $PORT)
After=network.target

[Service]
ExecStart=/usr/bin/python3 $SERVER_PY $PORT $DIR
User=www-data
Restart=always
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
  id www-data >/dev/null 2>&1 || sed -i 's/^User=www-data$/DynamicUser=yes/' "/etc/systemd/system/$SERVICE.service"
  systemctl daemon-reload
  systemctl enable --now "$SERVICE" >/dev/null 2>&1
  systemctl restart "$SERVICE"
  say "Servi par le service $SERVICE (python3, port $PORT)."
}

if [ -n "$NGINX_TARGET" ] && use_nginx; then
  :
else
  [ -n "$NGINX_TARGET" ] && say "nginx refuse la configuration : serveur Python à la place."
  use_python
fi
echo "$PORT" > "$PORT_FILE"

# ── 4. The firewall, only if ufw is active: one rule for this port.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
  ufw allow "$PORT/tcp" comment 'PairDesk website' >/dev/null
  say "Pare-feu (ufw) : port $PORT ouvert."
fi

# ── 5. Check.
sleep 1
if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/"; then
  IP="$(curl -fsS -4 --max-time 5 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
  echo
  say "Le site est en ligne : http://$IP:$PORT/"
else
  die "le site ne répond pas sur le port $PORT."
fi
