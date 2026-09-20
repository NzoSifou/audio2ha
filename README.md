# Audio2HA

Application Android TV qui surveille la lecture audio de la TV et publie l'état
dans Home Assistant, façon « Peak Volume > 0 » de HASS Agent sous Windows.

Quand un son démarre, l'entité passe à `on` ; quand il s'arrête, elle passe à `off`.

## Fonctionnement

- Un service de premier plan écoute `AudioManager.AudioPlaybackCallback`, l'API système
  qui signale tout démarrage / arrêt de flux audio sur l'appareil, **toutes applications
  confondues** (vérifié sur MiTV-MOOQ1 / Android 10 avec le navigateur de la TV).
- Les flux dont l'usage est un bip d'interface ou une notification sont ignorés ;
  `AudioManager.isMusicActive` sert de signal complémentaire.
- Un relevé toutes les 2 s sert de filet de sécurité si le callback ne se déclenche pas
  (cas observé quand une application est tuée brutalement).
- Un anti-rebond évite de faire clignoter l'entité : 500 ms avant de publier « son démarré »,
  3 s avant « son arrêté » (réglables).
- L'état est renvoyé toutes les 5 minutes, car les entités créées via l'API REST
  disparaissent quand Home Assistant redémarre.

## Publication vers Home Assistant

```
POST {url}/api/states/{entity_id}
Authorization: Bearer {token}

{"state": "on", "attributes": {"friendly_name": "...", "device_class": "sound", ...}}
```

L'entité n'a pas besoin d'exister au préalable : l'API la crée.

## Configuration

L'adresse par défaut est `http://homeassistant.local:8123`. **Attention** : Android TV ne
résout généralement pas les noms `.local` (mDNS) — utilisez l'adresse IP de Home Assistant.

Saisir un token de 180 caractères à la télécommande étant pénible, l'application expose un
petit serveur web local, actif dès l'ouverture de l'application :

```
http://<ip-de-la-tv>:8099
```

Ouvrez cette adresse depuis un PC ou un téléphone du même réseau, collez le token, validez :
la configuration est enregistrée et testée immédiatement. L'adresse est rappelée sur
l'écran d'accueil de l'application.

Autres points d'entrée du serveur :

| Chemin     | Rôle                                                              |
|------------|-------------------------------------------------------------------|
| `/`        | formulaire de configuration                                       |
| `/status`  | JSON : configuration, surveillance active, état du son            |
| `/players` | JSON de diagnostic : ce que l'application voit via `AudioManager`  |
| `/tone`    | joue un bip de test sur la TV                                     |

## Écran des journaux

Deux catégories, filtrables :

- **Changements TV** — détection locale : son qui démarre, son qui s'arrête, avec le détail
  (nombre de flux actifs, usages, `isMusicActive`, origine callback ou relevé).
- **Envois Home Assistant** — chaque requête, son code HTTP, sa durée et ses erreurs.

Les journaux sont conservés sur le disque (500 dernières entrées) et survivent au
redémarrage de l'application.

## Installation

```bash
./gradlew :app:assembleDebug
adb connect <ip-de-la-tv>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Limites connues

- Le son transitant par une entrée HDMI ou le tuner TV n'apparaît pas dans
  `AudioPlaybackCallback` : seule la lecture audio des applications Android est détectée.
- Le token est stocké en clair dans les `SharedPreferences` de l'application, et le serveur
  de configuration local n'est pas protégé par mot de passe : à réserver à un réseau de
  confiance.
