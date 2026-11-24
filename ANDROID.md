# Daytona USA sur Android (Retroid Pocket 6 et appareils arm64)

Portage expérimental. Il compile, mais **n'a pas encore tourné sur un appareil** :
voir [État](#état) avant d'y passer une soirée.

## Ce qu'il faut

- Un **PC Linux x64** (Ubuntu, Fedora, Arch…) avec environ 15 Go libres.
- **Ton** paquet XBLA de Daytona USA : le fichier LIVE/STFS copié depuis ta
  Xbox 360 (pas d'extension, nom hexadécimal long). C'est le même fichier que
  pour la version PC.
- Un appareil Android **arm64**, Vulkan 1.1, Android 10 ou plus récent, avec un
  noyau à pages de 4 Ko (le Retroid Pocket 6 coche tout).
- Un câble USB, pour installer l'APK et copier les fichiers du jeu.

## Compiler

```sh
# outils (Debian/Ubuntu ; le script donne la ligne pour Fedora et Arch)
sudo apt install git cmake ninja-build python3 openjdk-17-jdk curl unzip pkg-config libx11-xcb-dev

git clone -c core.symlinks=true --recurse-submodules -b android https://github.com/Manadthan/daytona_recomp
cd daytona_recomp
scripts/build_android.sh "/chemin/vers/ton/paquet"
```

Le script fait tout, et saute ce qui est déjà fait aux lancements suivants :

1. récupère les sous-modules ;
2. télécharge le SDK et le NDK Android dans `android/.sdk/` (environ 2 Go, une
   seule fois ; mets `ANDROID_HOME` pour en utiliser un déjà installé) ;
3. compile le générateur de code `rexglue` pour le PC ;
4. extrait ton paquet dans `extracted/` (+ `assets/default.xex`) ;
5. recompile le code du jeu en C++ (`scripts/regen.sh` → `generated/`) ;
6. compile les bibliothèques arm64 (long la première fois) ;
7. fabrique l'APK : `android/app/build/outputs/apk/release/daytona-android.apk`.

## Installer sur la console

Sur le Retroid : **Paramètres → À propos → toucher 7 fois « Numéro de build »**,
puis **Options pour les développeurs → Débogage USB : activé**. Branche le
câble, accepte la demande sur l'écran, puis :

```sh
scripts/build_android.sh --install
```

Ça installe l'APK et copie les données du jeu (environ 240 Mo) dans :

```
/sdcard/Android/data/org.daytonarecomp.app/files/assets/      ← le contenu de extracted/
/sdcard/Android/data/org.daytonarecomp.app/files/daytona.ini  ← réglages (facultatif)
```

Sans PC ni câble : zippe le contenu de `extracted/` (`default.xex` à la racine
du zip ou dans un seul dossier), mets le zip sur la console, installe l'APK et
lance-le. Tant que les données ne sont pas installées, l'appli affiche
« Choisir daytona-assets.zip » : elle ouvre le sélecteur de fichiers d'Android,
décompresse le zip dans son dossier `assets/`, puis lance le jeu.

Les journaux sont dans `files/logs/`, et en direct avec
`adb logcat -s rexglue SDL Daytona`.

## Comment c'est fait

| Partie | Changement |
|---|---|
| SDK ([fork](https://github.com/Manadthan/rexglue-sdk), branche `android/arm64`) | cible Android : fenêtre ANativeWindow via SDL3, point d'entrée `SDL_main`, journaux vers logcat, fibres en assembleur AArch64 (Bionic n'a pas `swapcontext`), SDL3 en bibliothèque partagée, dossiers inscriptibles |
| Jeu (`src/`) | le rendu natif (plume, Vulkan) présente dans la fenêtre Android ; `daytona.ini`, `cache/` et `assets/` vont dans le dossier de l'appli |
| `android/` | projet Gradle : l'activité SDL3 (`DaytonaActivity`) qui charge `libmain.so` |
| `scripts/build_android.sh` | toute la chaîne, du paquet à l'APK |

Aucune donnée du jeu n'est dans le dépôt ni dans l'APK. L'APK contient en
revanche le code du jeu recompilé : il est pour **ta** console, ne le partage pas.

## État

- ✅ le SDK, le moteur de rendu et le code écrit à la main compilent pour Android arm64 ;
- ✅ les fibres (changement de contexte des threads du jeu) testées sous émulation arm64 ;
- ✅ l'APK se fabrique ;
- ❓ premier lancement sur un vrai appareil : à faire. Points à surveiller :
  la réservation de la mémoire du jeu, le rendu Vulkan sur Adreno, la
  manette intégrée, le son, les performances.

En cas de problème au lancement, le plus utile est la sortie de
`adb logcat -s rexglue SDL Daytona DEBUG AndroidRuntime`.
