# Sujets en suspens

Ce qui reste à faire ou à décider, tenu à jour au fil des versions (dernière mise à jour : 0.21).

## Effets (pad XY)
- Fait en 0.21 : la nouvelle convention (X = quoi, Y = combien en macro, coins, cases aimantées et nommées, étiquettes,
  deuxième doigt = HOLD) et les 26 effets du brief.
- **À régler à l'oreille, sur le téléphone** : les plages de chaque profil (`assets/fx_profiles.json`, sans recompiler)
  et la sensibilité des axes.
- **Retour à l'origine** quand on lève le doigt ou qu'on sort de HOLD : progressif ou immédiat, au choix (par effet,
  ou en réglage général). Aujourd'hui : chaque profil a son geste (`return`, `hold`, `action`) et ses temps.
- **Effets à revérifier en jeu** (retour de test du 24 novembre) : le granulaire (Texture, Boucle granulaire) ne
  semble pas du tout effectif ; repasser les autres un par un.
  Pistes pour le granulaire : (1) dans Clouds, DENSITY au milieu (0,5) ne joue **aucun** grain (à gauche des grains
  réguliers, à droite des grains au hasard) ; les coins du profil sont à 0,35-0,9, donc le milieu du pad tombe près de
  0,6 : presque rien ; à remapper sur 0,7-1,0 (ou 0-0,3) ; (2) doigt posé = gel immédiat : on n'entend que ce qui
  était déjà dans le tampon, et rien ne suit le son en direct ; essayer le gel seulement par HOLD ou après un appui
  long ; (3) vérifier le niveau d'entrée (Clouds attend un signal fort) et la position de lecture.
- Options du brief non faites : la pression du doigt sur Y, l'écran de debug (latence, CPU par effet), Ableton Link.

## Sensibilité des potards (encodeurs) : toujours un problème
- Constat : des sauts de valeur restent, au doigt sur l'écran comme depuis le FM-1 (PANEL MIDI).
- Ce que fait RemoteFM1 (Quixotic7, même protocole que notre PANEL MIDI) :
  - le FM-1 envoie les crans tournés depuis le dernier envoi (`BE 16+e 64+d`), découpés à ±63, rien de perdu ;
  - l'hôte les passe d'un coup à fm1-emu (`fm1_enc(e, d)`), qui les joue à son rythme (un cran = 4 contacts, 2 ms
    chacun) : pas de file d'attente en plus, pas de lissage ;
  - à l'écran : 8 px par cran ; pour les potards appris en MIDI, un réglage « vitesse » ½×, 1×, 2× (les fractions
    sont gardées). Rien sur l'accélération des firmwares.
- Hypothèse : beaucoup de firmwares accélèrent quand les crans arrivent vite (Chordism : 3 pas par cran quand il en lit
  plus de 2 d'un coup). Les crans arrivent par paquets (USB, Android) et l'app les rejoue à 9 ms d'écart : un tour lent
  à la main ressemble pour le firmware à un tour très rapide, d'où les sauts.
- Fait en 0.20 : le réglage de vitesse (¼×, ½×, 1×, 2×, par firmware). Reste à voir s'il suffit.
- Pistes encore ouvertes : (1) rejouer les crans au rythme où ils ont été tournés (étalés sur le temps écoulé depuis le paquet
  précédent) au lieu de 9 ms ; (2) à l'écran, revoir la distance par cran (22 unités aujourd'hui) ; (3) mesurer avec Chordism et 2 ou 3 autres
  firmwares (tests `enc_bursts.rs`) avant de choisir.

## Plusieurs sources audio USB (plusieurs FM-1, d'autres machines)
- Le but : brancher plus d'un FM-1, ou d'autres machines audio USB (une autre groovebox, une carte son, un synthé
  « class compliant »), par un hub USB-C, et que **tous jouent en même temps** : chacun sur sa tranche du mixeur,
  avec l'émulé, au même tempo, mixés, enregistrés et envoyés aux effets ensemble.
- Jouer ensemble demande en plus :
  - **un seul tempo** : une horloge maître (un des FM-1, ou l'app) relayée en MIDI vers tous les autres et vers
    l'émulé, Start et Stop compris, pour que tout parte et tombe ensemble ;
  - **l'alignement** : chaque appareil arrive avec son propre retard USB ; un décalage par source (mesuré ou réglé,
    comme « Alignement des deux FM-1 » aujourd'hui) pour que les temps tombent ensemble dans le mix ;
  - **la lecture en parallèle** : toutes les sources lues en continu, sans que l'une en attende une autre (un lecteur
    par appareil dans le pilote intégré), et une dérive corrigée pour chacune.
- Faisable, mais pas par le chemin d'Android : Android n'ouvre qu'un appareil audio USB à la fois. Par le pilote
  intégré de l'app (`UsbAudioIn` + `native/src/usbin.rs`, qui lit déjà le FM-1 en direct), chaque appareil peut être
  ouvert à part : un lecteur par appareil.
- À faire :
  1. **la reconnaissance** : identifier chaque appareil (fabricant, nom, numéro de série USB quand il en a un, sinon le
     port du hub), lui donner un nom (« FM-1 A », « FM-1 B », « Volca »…) et retenir ses réglages (volume, EQ, balance,
     coupé) d'une fois sur l'autre ; dire ce qu'il sait faire (entrée, sortie, MIDI, nombre de canaux, fréquences) ;
  2. **le moteur** : une file par source, chacune avec sa correction de dérive (chaque appareil a sa propre horloge),
     son EQ et son vumètre ; le mix et l'enregistrement « Deux pistes » deviennent N pistes ;
  3. **le mixeur** : une tranche par source branchée, qui apparaît et disparaît avec elle (le mixeur défile s'il y en
     a trop) ; le send des effets choisit parmi toutes ;
  4. **le MIDI** : chaque appareil a son port ; l'horloge vient d'un maître choisi ; PANEL MIDI : lequel des LOOM-8
     prête son panneau ;
  5. **la sortie** : « sur le FM-1 » devient « sur l'appareil choisi ».
- Limites à vérifier : le courant (un hub alimenté si plusieurs appareils tirent sur le téléphone), la bande passante
  USB (les FM-1 sont en « full speed » : plusieurs passent sur un hub, à mesurer), la charge du processeur, et les
  appareils qui ne sont pas « class compliant » (pilote propriétaire : impossibles).

## Optimisation du processeur (réflexion)
- Où on en est : l'émulateur tourne avec son JIT ARM64 (activé sur Android dans la version de fm1-emu utilisée),
  l'horloge suit le minimum mesuré de chaque firmware ; la charge affichée tourne autour de 77-82 % avec Chordism.
- Ce que Harbor (markterm, autre app Android sur fm1-emu) a mesuré et fait :
  - une session ADPF (`PerformanceHintManager`, « 6 ms de travail pour 10 ms d'audio ») qui garde le moteur sur un
    gros cœur : de 72-93 % à environ 50 % de charge sur un firmware lourd, sans craquement ;
  - une version « release » (non debuggable) : le fil de l'interface tombe de environ un tiers de cœur à moins de 10 % ;
  - ne pas retenir une baisse d'horloge décidée quand l'app est en arrière-plan.
- 0.20 : la session ADPF et la priorité audio ont fait craquer et saturer plus vite. 0.20.1 retire la priorité ; l'ADPF
  devient un essai coupé par défaut (Réglages > Machine). À mesurer : avec et sans, et si besoin chercher ailleurs.
- Pour FM1 Mix, ensuite : (1) construire en release (en attente : d'autres modifications d'abord) ; (2) mesurer qui coûte quoi (moteur, capture USB et
  rééchantillonnage, effets, dessin de l'écran à 30 images/s) ; (3) dessiner moins souvent ce qui ne bouge pas.
- Plus loin : RemoteFM1 fait tourner beaucoup de firmwares recompilés pour l'hôte (`.rfm1`, en WebAssembly, et des
  cœurs natifs) au lieu de les émuler : bien moins de calcul, mais il faut le code source de chaque firmware et un
  moteur WebAssembly dans l'app.
- Faire tourner l'émulation sur un serveur web (un hébergeur) : non, la latence et les à-coups du réseau rendent le jeu
  en direct impossible.

## PANEL MIDI sur d'autres firmwares
- **Skiff** (illuminus5, GPL-3.0-only, base Felucca) : porter PANEL MIDI (lecture des touches, voyants, envoi MIDI,
  menu MIDI, raccourci GLO + FX, MASTER), avec les tests ; mêmes messages que LOOM-8, donc rien à changer dans l'app.
  Skiff a déjà REMOTE (le sens inverse). Le proposer ensuite à son auteur.
- **Dépôt automatique** (`fm1-panelmidi`) : une liste de firmwares suivis, les surcouches en patchs, un robot GitHub
  qui guette les nouvelles versions, applique, construit, teste, publie, met à jour un catalogue (page de liens +
  fichier lu par FM1 Mix) et ouvre une « issue » quand un patch ne passe plus. LOOM-8 en premier, Skiff ensuite.
- À terme : PANEL MIDI comme module commun dans fm1-base.

## L'app en deux langues
- Fait en 0.19 (français si le téléphone est en français, anglais sinon). À relire à l'usage : quelques textes
  anglais pourront être ajustés.

## Installateur web de l'app
- Préparé (24 novembre) : `.github/workflows/release-apk.yml` (« Publish the app », à la main ou sur un tag) publie
  `fm1mix.apk` dans une release ; lien fixe dans le README :
  `https://github.com/RichAldana/FM1-Studio/releases/latest/download/fm1mix.apk`. Signé avec la clé du dépôt
  (`app/debug.keystore`) : les versions s'installent l'une sur l'autre. Reste ci-dessous : la clé privée, la page,
  les mises à jour dans l'app, Obtainium.
- LOOM-8 PANEL MIDI : site et installateur web préparés pour LOOM8-Remote (`pages.yml`, appelé par `release.yml`
  sur un tag `v0.18.2-pm1`), lien `https://richaldana.github.io/LOOM8-Remote/install/`.
- Une page (GitHub Pages) avec un bouton « Installer » qui pointe toujours vers le dernier APK publié, la version et les
  nouveautés, un QR code pour qui ouvre la page sur ordinateur, et le mode d'emploi pour autoriser l'installation.
- À faire :
  1. une clé de signature privée (dans les secrets GitHub) à la place de la clé de debug publique du dépôt ; changer
     de clé oblige à désinstaller une fois (sauvegarder d'abord la mémoire des firmwares) ;
  2. le workflow : sur un tag `v0.19`, construire l'APK signé et créer une release GitHub avec `fm1mix.apk` attaché ;
  3. la page `docs/index.html` publiée par GitHub Pages, lien fixe
     `https://github.com/<toi>/FM1-Studio/releases/latest/download/fm1mix.apk` ;
  4. dans l'app : « Mises à jour » qui lit la dernière release et propose de télécharger et d'installer ;
  5. en plus : la recette pour Obtainium (mises à jour automatiques depuis GitHub), et plus tard IzzyOnDroid.
- Option : sur la même page, l'installateur de LOOM-8 PANEL MIDI dans le navigateur (Chrome ou Edge), comme celui
  de Skiff.

## Pistes ouvertes (du post)
- Plusieurs émulateurs en même temps, un bouton pour passer de l'un à l'autre.
- L'écran de l'émulé sur l'écran du FM-1 (mesuré faisable, pas fait).
- Un vrai travail de design d'interface ; l'optimisation (charge processeur).

## Petites choses
- Vidéo explicative : « prototype » à remplacer par « bêta ».
- Le lien GitHub de ton fork, pour le post et les crédits.
- Vérifier la ligne de crédits RemoteFM1 / Felucca / Harbor (ce que chacun a apporté).
