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

## Type d'entité, et pourquoi la pièce

Deux modes, choisis dans l'application :

**Capteur binaire (`binary_sensor`)** — par défaut. L'état est poussé par l'API REST :

```
POST {url}/api/states/{entity_id}
Authorization: Bearer {token}

{"state": "on", "attributes": {"friendly_name": "...", "device_class": "sound", ...}}
```

L'entité n'a pas besoin d'exister au préalable, l'API la crée. En revanche elle n'entre
pas dans le registre d'entités de Home Assistant : elle **ne peut pas être rangée dans une
pièce**, ni renommée depuis l'interface (`config/entity_registry/update` répond
« Entity not found »).

**Interrupteur virtuel (`input_boolean`)** — l'application crée un helper via l'API
WebSocket (`input_boolean/create`), puis le range dans la pièce choisie
(`config/entity_registry/update`). C'est une vraie entité enregistrée : pièce, renommage
et personnalisation fonctionnent. L'état est publié avec les services
`input_boolean.turn_on` / `turn_off`.

La liste des pièces est lue dans Home Assistant (`config/area_registry/list`) et proposée
dans un menu déroulant, dans l'application comme dans la page web. Le client WebSocket est
écrit à la main (`HaWebSocket.kt`) pour éviter une dépendance : l'API REST ne sait ni
lister les pièces, ni créer un helper, ni affecter une pièce.

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
| `/offline?seconds=30` | fait croire à l'application qu'il n'y a plus de réseau, pour tester le comportement hors-ligne |

## Réglages

| Réglage | Par défaut | Rôle |
|---|---|---|
| Nom du capteur | modèle de la TV | nom affiché dans Home Assistant |
| Type d'entité | `binary_sensor` | voir ci-dessus |
| Pièce | aucune | uniquement avec l'interrupteur virtuel |
| Lancer au démarrage de la TV | désactivé | relance la détection après un redémarrage |
| Si le réseau est indisponible | ignorer l'envoi | ou conserver l'état et le publier au retour du réseau |
| Nombre de nouvelles tentatives | 3 | réessais après une réponse autre que 2xx |
| Délai d'attente par tentative | 8 s | délai de connexion et de lecture |
| Délai avant « son démarré » | 500 ms | filtre les bips d'interface |
| Délai avant « son arrêté » | 3 s | évite le clignotement entre deux pistes |

En mode « attendre le retour du réseau », c'est le **dernier** état connu qui est publié au
retour, pas la suite des changements manqués.

## Saisie à la télécommande

Le clavier virtuel ne s'ouvre pas au simple passage du focus sur un champ : il faut valider
par « OK », ce qui ouvre une fenêtre de saisie dédiée. Sans cela le clavier capte les
touches directionnelles dès l'arrivée sur l'écran.

## Interface

L'application suit le design system **Nocturne** (piste 1b de la maquette) : fond #161826,
accent blurple #9184D9, menu latéral persistant et une page par sujet — État, Entité,
Comportement, Journaux.

La maquette est dessinée sur un cadre de 1920 × 1080 px, soit la définition de la TV.
Celle-ci étant en 320 dpi, **1 px de maquette vaut 0,5 dp** : c'est le rapport utilisé
pour toutes les valeurs de `ui/theme/Nocturne.kt`.

L'accueil affiche l'état en grand, lisible du canapé, avec des barres d'égaliseur. Ces
barres sont décoratives : l'API de détection ne donne pas l'amplitude du son (il faudrait
le micro), elles signalent seulement qu'un flux est en cours.

## Écran des journaux

Rubrique « Journaux », deux catégories filtrables :

- **Changements TV** — détection locale : son qui démarre, son qui s'arrête, avec le détail
  (nombre de flux actifs, usages, `isMusicActive`, origine callback ou relevé).
- **Envois Home Assistant** — chaque requête, son code HTTP, sa durée et ses erreurs.

Les journaux sont conservés sur le disque (500 dernières entrées) et survivent au
redémarrage de l'application.

## Installation

L'APK de la dernière version est publié dans les
[releases](https://github.com/NzoSifou/audio2ha/releases/latest) ; ce lien pointe toujours
vers le fichier le plus récent :

```
https://github.com/NzoSifou/audio2ha/releases/latest/download/Audio2HA.apk
```

Les trois méthodes d'installation (Downloader, clé USB, ADB) sont détaillées dans
[RELEASE.md](RELEASE.md).

### Compiler soi-même

```bash
./gradlew :app:assembleDebug
adb connect <ip-de-la-tv>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pour un APK de release signé, créez `keystore.properties` à la racine (hors dépôt) :

```properties
storeFile=audio2ha-release.jks
storePassword=...
keyAlias=audio2ha
keyPassword=...
```

puis `./gradlew :app:assembleRelease`. Sans ce fichier, l'APK de release sort non signé,
donc non installable.

## Limites connues

- Le son transitant par une entrée HDMI ou le tuner TV n'apparaît pas dans
  `AudioPlaybackCallback` : seule la lecture audio des applications Android est détectée.
- Le token est stocké en clair dans les `SharedPreferences` de l'application, et le serveur
  de configuration local n'est pas protégé par mot de passe : à réserver à un réseau de
  confiance.
- Le lancement au démarrage n'a pas pu être vérifié par un vrai redémarrage (l'ADB de la TV
  passe par le WiFi) : le receveur est bien enregistré pour `BOOT_COMPLETED`, mais le
  comportement reste à confirmer après un redémarrage réel.
