# Serveur PairDesk (optionnel)

PairDesk fonctionne sans serveur grâce à des relais publics. Héberger votre propre serveur apporte :

- une **mise en relation plus fiable** (vous ne dépendez plus de services tiers gratuits) ;
- des **identifiants TURN** distribués automatiquement, pour que les connexions passent même derrière des pare-feu stricts ;
- la réservation des ID : un ID ne peut plus être utilisé par un autre appareil pendant 180 jours.

Le serveur ne voit jamais les mots de passe ni le contenu des sessions : il ne fait que transmettre des messages déjà chiffrés de bout en bout.

## Démarrage rapide (Docker)

Sur un serveur Linux avec Docker et un nom de domaine pointant vers lui (ex. `pairdesk.exemple.fr`) :

```bash
git clone https://github.com/Azukkia/PairDesk.git
cd PairDesk/server
# Remplacez pairdesk.example.com par votre domaine et choisissez un secret TURN aléatoire
nano docker-compose.yml
docker compose up -d
```

Cela démarre le serveur PairDesk, **Caddy** (certificat HTTPS Let's Encrypt automatique) et **coturn** (relais TURN). Ouvrez les ports TCP 80/443, TCP+UDP 3478 et UDP 49160-49200.

Dans PairDesk, sur chaque ordinateur : *Paramètres → Réseau → Serveur PairDesk privé* → `wss://pairdesk.exemple.fr/ws`.

Pour que toutes les futures versions utilisent ce serveur par défaut, renseignez `"defaultServerUrl": "wss://pairdesk.exemple.fr/ws"` dans `pairdesk.config.json` à la racine du dépôt.

## Sans Docker

```bash
cd server
npm ci --omit=dev
PORT=8080 DATA_DIR=./data node src/server.js
```

Placez-le derrière un proxy HTTPS (Caddy, nginx…) : les clients se connectent en `wss://`. Pour un test sur un réseau local, `ws://adresse-ip:8080/ws` fonctionne aussi.

## Variables d'environnement

| Variable | Rôle |
|---|---|
| `PORT` (8080) / `HOST` (0.0.0.0) | Adresse d'écoute (HTTP + WebSocket sur `/ws`, santé sur `/health`). |
| `DATA_DIR` | Dossier où sont conservées les réservations d'ID (`bindings.json`). |
| `TURN_URLS` | URLs TURN séparées par des virgules, ex. `turn:pairdesk.exemple.fr:3478?transport=udp,turn:pairdesk.exemple.fr:3478?transport=tcp`. |
| `TURN_SECRET` | Secret partagé avec coturn (`--use-auth-secret`) : le serveur génère des identifiants temporaires. |
| `TURN_USERNAME` / `TURN_CREDENTIAL` | Alternative : identifiants TURN fixes. |
| `TURN_TTL` | Durée de validité des identifiants (secondes, 86400 par défaut). |
| `CF_TURN_KEY_ID` / `CF_TURN_API_TOKEN` | Alternative : utilise le service TURN de **Cloudflare** (offre gratuite généreuse) — créez une clé TURN dans le tableau de bord Cloudflare *Realtime*. |
| `STUN_URLS` | Serveurs STUN supplémentaires. |
| `TRUST_PROXY=1` | Utiliser `X-Forwarded-For` (derrière un proxy) pour la limite de connexions par IP. |
| `MAX_CLIENTS_PER_IP` (50) | Nombre maximal de connexions simultanées par adresse IP. |

## Hébergement

Le serveur consomme très peu de ressources (quelques Mo de RAM) : n'importe quel petit VPS, un Raspberry Pi ou une offre gratuite (Oracle Cloud *Always Free*, Google Cloud e2-micro…) suffit. Le relais TURN, lui, consomme de la bande passante uniquement pour les sessions qui ne peuvent pas se faire en direct.
