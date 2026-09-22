# Installation d'Audio2HA

L'application n'est pas sur le Play Store : elle s'installe par chargement manuel
(*sideload*). Trois méthodes, de la plus simple à la plus technique.

Le lien ci-dessous pointe **toujours** vers l'APK de la dernière version publiée :

```
https://github.com/NzoSifou/audio2ha/releases/latest/download/Audio2HA.apk
```

## Méthode recommandée : Downloader

[Downloader](https://www.aftvnews.com/downloader/) (AFTVnews) est un navigateur de
téléchargement conçu pour les télécommandes : c'est le moyen le plus rapide d'installer
une application sur une TV, sans PC ni clé USB.

1. Sur la TV, installez **Downloader** depuis le Play Store.
2. Ouvrez Downloader, onglet **Home**, champ URL.
3. Saisissez le code court **5670916** (ou l'adresse `http://aftv.news/5670916`).
4. **Download** : le téléchargement démarre, puis l'installation est proposée.
5. À la première installation, Android demande d'autoriser Downloader à installer des
   applications inconnues — acceptez, puis revenez en arrière et relancez l'installation.
6. Une fois installée, supprimez le fichier APK quand Downloader le propose.

| Code Downloader | Adresse équivalente |
|-----------------|---------------------|
| `5670916` | `http://aftv.news/5670916` |

Ce code pointe vers l'APK de la dernière version : il reste valable à chaque nouvelle
publication.

## Clé USB

1. Depuis un PC, téléchargez `Audio2HA.apk` (lien ci-dessus) et copiez-le sur une clé USB.
2. Branchez la clé sur la TV.
3. Installez un explorateur de fichiers depuis le Play Store (par exemple *X-plore* ou
   *File Commander*) — Android TV n'en fournit pas toujours.
4. Ouvrez la clé, lancez `Audio2HA.apk`, autorisez l'explorateur à installer des
   applications inconnues, puis installez.

## ADB

Pour qui a déjà le débogage activé sur la TV :

```bash
adb connect <ip-de-la-tv>:5555
adb install -r Audio2HA.apk
```

L'adresse IP de la TV se trouve dans *Paramètres > Réseau*, et le débogage ADB dans
*Paramètres > Préférences de l'appareil > Options pour les développeurs > Débogage USB /
réseau*.

## Après l'installation

1. Lancez Audio2HA : l'écran d'accueil affiche l'adresse du serveur de configuration
   local, du type `http://192.168.1.42:8099`.
2. Ouvrez cette adresse depuis un PC ou un téléphone du même réseau, collez l'adresse de
   Home Assistant et un token d'accès longue durée, puis enregistrez.
3. Revenez sur la TV et démarrez la surveillance.

Détail des réglages dans le [README](README.md).

## Signature

Les APK sont signés avec la clé de release du projet. Une mise à jour ne s'installe
par-dessus une version existante que si elle porte la même signature : n'installez que des
APK provenant des releases de ce dépôt.

## Notes de version

### v1.0.0

Première version publiée.

- Détection du son de la TV via `AudioManager.AudioPlaybackCallback`, toutes applications
  confondues, avec relevé de secours toutes les 2 s et anti-rebond réglable.
- Publication de l'état dans Home Assistant, au choix comme `binary_sensor` (API REST) ou
  comme helper `input_boolean` — ce dernier étant enregistré dans Home Assistant, il peut
  être rangé dans une pièce, que l'application propose dans une liste.
- Serveur de configuration local sur le port 8099, pour saisir l'adresse et le token
  depuis un PC ou un téléphone plutôt qu'à la télécommande.
- Réessais configurables, comportement au choix en l'absence de réseau (ignorer l'envoi ou
  attendre son retour), lancement au démarrage de la TV.
- Journaux à deux catégories, persistés : changements détectés sur la TV et envois vers
  Home Assistant.
- Interface d'après le design system Nocturne : menu latéral, une page par sujet, état
  géant lisible du canapé.
