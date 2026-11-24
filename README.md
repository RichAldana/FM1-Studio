# FM1 Mix (bêta, v0.28)

Licence : GPL-3.0-only pour l'ensemble (voir `NOTICE.md` : le découpeur reprend le code de SLOOP).

**Télécharger la dernière version** (Android, arm64), lien pour tout le monde :
[fm1mix.apk](https://github.com/RichAldana/FM1-Studio/releases/latest/download/fm1mix.apk) ·
[toutes les versions](https://github.com/RichAldana/FM1-Studio/releases) (ou, si `PUBLIC_REPO` est réglé, le dépôt public des seules versions publiées,
avec le code source de chacune ; ce dépôt-ci peut rester privé).
Ce lien ne change qu'à la publication d'une version (Actions > « Publish the app » > Run workflow, qui prend
`versionName` de `app/build.gradle.kts`), jamais à un simple push. Chaque version s'installe par-dessus la précédente.
Réglage : `.github/workflows/release-apk.yml` (variable `PUBLIC_REPO`, secret `PUBLIC_REPO_TOKEN`).

Un FM-1 émulé sur Android (cœur : [fm1-emu](https://github.com/joakimeriksson/fm1-emu), MIT) qui
mélange sa sortie avec l'audio USB d'un vrai FM-1, et qui reçoit l'horloge MIDI de ce FM-1.

    FM-1 réel (LOOM-8) --USB-C--> téléphone
        |-- audio USB ---> AudioRecord ---+
        |-- MIDI (horloge) -> MidiManager -> MIDI IN émulé (Chordism)
                                           +-> AudioTrack <- mixage <- sortie de l'émulateur

## v0.28 : motion recording, un effet par piste, bandeau des projets, export
- **Un effet par piste** (page Effets) : une rangée de pistes en haut du pad (TOUT, ÉMU, FM-1, puis chaque boucle) ;
  chacune garde son effet et son type. TOUT agit sur tout le mix (boucles comprises) : l'option « aussi dans TOUT »
  disparaît. FM-1 ne prend un effet que si son son passe par le téléphone.
- **Trois effets à la fois au plus** : un quatrième est refusé avec un message. **Garde-fou du processeur** : si le
  son prend plus de 85 % du temps (ou le téléphone 98 %) pendant 2 s, le dernier effet ajouté s'éteint, avec un
  message, même longtemps après l'avoir ajouté.
- **Motion recording** (gestes enregistrés, calés sur l'horloge ; 1 mesure = 16 pas) :
  - sous le pad, une bande MOTION : REC, la longueur (1, 2, 4 ou 8 mesures), les patterns A B C D, HOLD ;
  - une page MOTION (zone du milieu) : toutes les pistes, leurs quatre patterns (geste dessiné en petit), REC, STOP ;
  - un jeu de 4 patterns par piste ; REC arme, l'enregistrement part à la mesure suivante et rejoue tout de suite ;
    un appui sur un pattern le lance à la mesure suivante ; appui long : jouer, écraser, ajouter par-dessus,
    longueur, copier, effacer ;
  - enregistre le pad, les faders et les EQ du mixeur, le potard et les encodeurs de l'écran ; un doigt ou un fader
    touché reprend la main pendant la lecture ;
  - gardé entre deux lancements (et dans les projets).
- **Bandeau du haut en deux pages** : glisser vers la gauche (ou toucher les points dessous). Page 1 : le bandeau
  habituel. Page 2 : le **nom du projet** (le toucher pour le renommer ; point orange = des changements depuis le
  dernier enregistrement), **Enreg.** (par-dessus le fichier du projet ; la première fois, demande le nom),
  **Ouvrir**, **Exporter**.
- **Exporter** : Complet ou Léger (sans les sons : les boucles reviennent vides avec leurs noms et réglages),
  `.fm1mix` ou `.zip` (s'ouvre partout ; l'app le rouvre aussi), sons compressés sans perte en **FLAC** (environ
  moitié moins lourd), taille estimée et avertissement au-delà de 20 Mo pour un mail ; « Enregistrer sous… » (où on
  veut : dossier, carte SD, Drive) ou « Envoyer… » (mail, WhatsApp, Signal…).
- Projets version 2 : ils gardent aussi l'effet de chaque piste et les motions ; les projets 0.27 s'ouvrent toujours.
- Encodeur FLAC maison (`native/src/flac.rs`), lecture par la bibliothèque claxon (Apache-2.0, voir `NOTICE.md`).
- **Son du FM-1 sur la sortie téléphone** (craquements, son métallique et étouffé) : la capture USB ne passe plus par
  la source « MIC » d'Android (traitée comme une voix sur certains téléphones) mais par UNPROCESSED, sinon
  VOICE_RECOGNITION (plate, sans contrôle de gain ni réduction de bruit), et l'app coupe ce que le téléphone ajouterait
  encore (réduction de bruit, gain automatique, annulation d'écho). La ligne d'état dit la source utilisée. Le son
  n'attend plus les effets : pendant un changement d'effet, un bloc passe sans effet plutôt qu'en retard.

## v0.27 : projets, noms des boucles, mode boucle visible
- **Projets** (Réglages > Projet) : « Enregistrer le projet… » écrit un fichier `.fm1mix` dans Documents/Fm1mix projects
  avec le firmware (son id, et son paquet s'il a été importé), sa mémoire (ce qu'il a sauvé lui-même), ses réglages
  dans l'app (horloge, MIDI, profil, volume, EQ, octave…), le mixeur (FM-1 branché, master, boucles) et les quatre
  boucles avec leurs réglages, leurs noms et leurs sons (le passage joué, en WAV : le projet se suffit à lui-même).
  « Ouvrir un projet… » liste ceux de ce dossier (et « Autre fichier… ») ; à l'ouverture, la mémoire actuelle du
  firmware est gardée en copie dans l'app. Format : un zip (`project.json`, `firmware.fm1s`, `firmware.fwsc`,
  `loop1.wav`…), voir `Project.kt`.
- **Nom des boucles** (option) : dans les réglages d'une boucle, « Nom sur le bouton » (10 caractères) ; vide, le
  bouton garde son numéro.
- **Mode boucle visible** : sur chaque piste, ↻ (en boucle) et 1× (une fois) côte à côte après → ← ↔ ; de même dans
  les réglages (« En boucle ↻ » / « Une seule fois 1× »).

## v0.26 : les boucles cherchent dans Musique/FM1 Mix
- Charger un son dans une boucle ouvre la liste des sons de l'app, dans Musique/FM1 Mix : les enregistrements, puis les
  découpes du découpeur (avec leur dossier), les plus récents d'abord, avec leur durée (plus de 30 s : choix du
  passage). « Autre fichier WAV… » ouvre le sélecteur d'Android directement dans ce dossier.

## v0.25 : choisir le passage d'un fichier long (écran « Passage 1 »)
- **L'écran du passage** (`PassageView.kt`) : tout le fichier en haut avec la fenêtre du passage à glisser ; le passage
  en grand, la grille des mesures depuis son début (au tempo du moment) et les attaques marquées, deux poignées à
  tirer ; début, durée, fin, avec ◀ ▶ (10 ms) ; la longueur en mesures (libre, 1, 2, 4, 8, 16 : grisée au-delà de
  30 s) ; l'aimant au lâcher (aux attaques à 80 ms près, au passage par zéro, aucun) ; « Écouter en boucle » (par la
  tranche BOUCLES) ; OK garde le passage.
- **Quand** : tout seul quand un fichier de plus de 30 s est choisi ; à tout moment par le bouton [ ] de chaque piste
  (et « Passage » dans les réglages de la boucle). Le passage est gardé et rechargé au démarrage.
- **Moteur** (`loops.rs`) : le fichier entier gardé à son débit (10 min au plus), ses crêtes, ses attaques, le zéro le
  plus proche, l'aperçu en boucle, la découpe vers une case ; le rééchantillonnage passe par une table (30 s à 48 kHz :
  0,09 s au lieu de 1,4 s sur le serveur). Les fichiers sont lus en flux (`WavStream.kt`), sans tout garder en mémoire.

## v0.24 : les boucles au mixeur et dans les effets
- **Mixeur** : une tranche BOUCLES fine entre FM-1 et MASTER (maquette « Mixeur 2 » validée) : allumer/couper, fader,
  vumètre, sans EQ (chaque boucle a son volume dans ses réglages). Aussi dans Réglages > Mixeur.
- **Effets** : l'envoi devient un sélecteur compact TOUT / ÉMU / FM-1 / BOUCLES. BOUCLES n'envoie que les boucles ;
  TOUT reste les deux FM-1, sauf avec le réglage « Aussi dans TOUT (effets) » (Réglages > Mixeur > Boucles).

## v0.23 : le lecteur de boucles (page « Boucles », zone du milieu)
- **Quatre cases**, une piste chacune (maquette B validée) : le bouton (noir vide, contour vert chargée et arrêtée,
  rouge en attente de son départ, vert joue), le nom et la durée, la forme d'onde avec la tête de lecture, les modes
  → ← ↔ 1× (en avant, à l'envers, aller-retour, une seule fois) et le pas de départ. En tête : le tempo (horloge MIDI
  du FM-1, sinon TAP : toucher = TAP) et STOP (tout, tout de suite).
- **Départ calé sur l'horloge** : au prochain temps, mesure, 2 ou 4 mesures (toucher le pas le fait tourner). Avec
  l'horloge du FM-1, le départ est placé là où son son de ce temps passe dans le mix (son retard USB compté). Sans
  horloge et sans rien qui joue, la première boucle part tout de suite et pose la mesure. **Arrêt** au même pas, tout
  de suite ou à la fin du passage (un deuxième toucher : tout de suite).
- **Fichiers** : WAV (ceux de l'app et la plupart des autres), coupés à 30 s, remis à 44,1 kHz stéréo au chargement
  (5,3 Mo par boucle de 30 s) ; les cases et leurs réglages sont gardés et rechargés au démarrage. Appui sur le nom ou
  la forme d'onde : réglages (fichier, lecture, départ, arrêt, volume, vider).
- **Mix** : les boucles sortent au niveau du master, dans l'enregistrement « Le mix » (pas dans « Deux pistes »).
- **Moteur** : `native/src/loops.rs` (lecture à l'échantillon près, fondus de 1,5 ms à l'entrée et 5 ms à l'arrêt,
  rééchantillonnage sinc fenêtré au chargement), tests `cargo test loops` ; il sait aussi partir sur un évènement
  (note MIDI, Start, fin d'une autre case, seuil), non proposé dans l'app pour garder simple.

## v0.22 : envoi d'échantillons vers LOOM-8 gelé
- **Découpeur** : l'envoi des morceaux et des boucles dans la banque de LOOM-8 est gelé (pas encore testé sur un vrai
  FM-1) : rien n'est envoyé au FM-1, pas même la détection ; la partie « Vers le FM-1 » le dit et renvoie à l'export
  WAV (puis à la page Samples du site de LOOM-8). Le code reste : `SampleTargets.SEND_ENABLED` à remettre à `true`
  pour le tester. PANEL MIDI n'est pas concerné.
- **Publication** : les versions peuvent aller dans un dépôt public à part (FM1-Mix), avec leur code source joint,
  pendant que ce dépôt reste privé.

## v0.21 : les 26 effets du brief, avec la convention de jeu live
- **La convention** (`native/src/fx.rs`, profils dans `assets/fx_profiles.json`) : X = quoi (à gauche sombre, lent,
  long ou grave ; à droite brillant, rapide, court ou aigu), Y = combien. Y est une macro : plusieurs paramètres à la
  fois, chacun avec sa plage, sa courbe (lin, exp, pow, steps, scale) et la hauteur d'où il entre en jeu (`from`).
  Mode « coins » pour Cosmos, Reverb rétro et Texture : quatre réglages aux coins, le doigt entre eux (bilinéaire, les
  valeurs discrètes prises au coin le plus proche). Plages bornées, jamais muet en bas. Les divisions de tempo, les
  notes et les formes sont des cases aimantées (hystérésis de 3 %), dessinées et nommées sur le pad ; le sens des axes
  est écrit sur ses bords. Un deuxième doigt sur le pad allume ou éteint HOLD.
- **Les 26 effets**, par type : Filtre (Filtre Moog, Isolateur, Filtre analogique), Delay (Écho, Delay ping-pong,
  Boucle granulaire), Reverb (Hall, Nuage diffus, Cosmos, Reverb rétro, Ressort), Modulation (Phaser, Flanger,
  Ensemble), Distorsion (Overdrive, Waveshaper), Lo-fi, Pitch (Pitch, Tape stop), Granulaire (Texture), Glitch (Beat
  repeat), Résonateur (Résonateur modal, Modulation croisée, Résonateur), Dynamique (Pompe, Compression).
- **Moteurs** : Faust et C++ (`fxengine.cpp`), Airwindows (`airwin.cpp`), Surge (`surge.cpp` : sst-effects,
  sst-filters, sst-waveshapers, et le ressort et l'ensemble de Surge XT), Eurorack (`mi.cpp` : Clouds à 32 kHz, Rings
  à 48 kHz, rééchantillonnés par `resample.h` ; Warps au rythme du téléphone). Sources tierces dans
  `native/fx/third_party` (liste, commits et licences : son `README.md`).
- **Ce qui reste de 0.17-0.20** : le pad dans la zone du milieu sur les deux tiers, type puis nom, send TOUT / ÉMULÉ /
  FM-1, HOLD, tempo du FM-1 ou TAP, traînes qui continuent après le doigt et au changement d'effet.
- **Nouveau type d'effet « listen »** (Boucle granulaire, Texture, Beat repeat) : il entend le son en permanence, pour
  que son tampon soit plein quand le doigt arrive ; doigt posé = gel (Clouds), la sortie s'efface au relâcher.
- **Tests** (`cargo test`) : les 26 se chargent ; chacun, touché au milieu et aux quatre coins, change le son, reste
  fini et borné ; courbes, cases avec hystérésis, coins. `examples/fx_bench.rs` : de 0,1 % (Tape stop) à 5,2 %
  (Ressort) du temps réel sur un cœur de serveur, 8,8 % au pire (un effet joué pendant la traîne du Ressort) ; sur un
  téléphone, compter 2 à 4 fois plus. Tout le C++ compilé aussi pour ARM64 (NEON, via simde).
- Écarts au brief : chemin audio existant gardé (pas d'Oboe) ; le Résonateur « Surge » est fait de trois passe-bande
  sst-filters à la manière du Resonator de Surge, et l'Ensemble reprend le chemin BBD de Surge XT sans ses classes de
  stockage ; la pression du doigt (option du brief, désactivée par défaut) n'est pas faite.

## v0.20.1 : retour en arrière sur le processeur
- 0.20 craquait et saturait plus vite. Retiré : la priorité audio demandée pour le fil du moteur (occupé presque tout
  un cœur, il pouvait passer devant le fil qui donne le son à Android). La session ADPF devient un essai, **coupé par
  défaut** : Réglages > Machine > Aide du processeur (ADPF). La vitesse des potards reste.

## v0.20 : vitesse des potards, session ADPF
- **Réglages > Potards > Vitesse des potards** : ¼×, ½×, 1× ou 2× pour KNOB 1 à 4, ceux de l'écran comme ceux du FM-1
  en PANEL MIDI ; en option SELECT, ALGO et PRESET aussi. Les fractions sont gardées (deux demi-crans font un cran) ;
  gardé pour chaque firmware. Dans le moteur (`engine.rs` `KNOB_SPEED_Q`, test `knob_speed_keeps_what_is_left`).
- **Session ADPF** (`native/src/hint.rs`, sur le modèle de celle de Harbor) : toutes les 10 ms, le fil du moteur dit
  à Android combien de temps a pris le son de ces 10 ms, pour une cible de 6 ms ; Android peut monter l'horloge ou
  le passer sur un gros cœur avant que le son manque. Android 13 et plus (cherché à l'exécution : rien ne change
  avant) ; le fil demande aussi la priorité audio. « | ADPF » dans l'état du moteur (Réglages > Aide > Diagnostic)
  quand la session tourne.

## v0.19 : français ou anglais
- L'app parle **français si le téléphone est en français, anglais sinon**. Sur Android 13 et plus, la langue de FM1 Mix
  se choisit aussi seule dans les réglages du téléphone (Applications > FM1 Mix > Langue), grâce à
  `res/xml/locales_config.xml`.
- Chaque texte est écrit en place dans les deux langues : `tr("Enregistrer", "Record")` (`Lang.kt`). Environ 460 textes :
  menus, aides, messages, enregistreur, mixeur, découpeur, diagnostic ; les noms des effets et de leurs axes aussi.
- Restent tels quels : les noms des boutons du FM-1 (sérigraphiés), les noms de firmwares, le dossier `Découpes`, les
  messages techniques déjà en anglais.

## v0.18 : nouveau bandeau, coupe/allumé au mixeur, noms des notes qui suivent l'octave
- **Bandeau** (`TopBar.kt`) : un bouton ⑂ (le catalogue de firmwares) remplace le nom écrit ; le nom du firmware passe
  en tête de la ligne d'état. Puis le découpeur, REC (le chrono s'affiche dans le bouton), marche/arrêt, et deux
  interrupteurs gauche/droite :
  - **qui joue l'émulé** : le téléphone (icône téléphone) ou le panneau du FM-1 (icône machine, PANEL MIDI) ; grisé
    sans LOOM-8 avec PANEL MIDI branché ;
  - **où sort le son** : le téléphone ou le FM-1, même principe d'icônes.
  Ensuite le voyant MIDI (cerclé d'ambre en PANEL MIDI ; appui long : bascule PANEL), la charge, les réglages. Tout se
  réduit ensemble sur un écran étroit.
- **Mixeur** : le nom de chaque tranche (ÉMULÉ, FM-1, MASTER) est son bouton allumé/coupé ; coupée, la tranche est
  muette et voilée, son volume et son EQ gardés. Retenu (l'émulé par firmware).
- **Clavier** : les noms des notes suivent l'octave. L'app compte OCT - et OCT + (touchés à l'écran, ou sur le FM-1 en
  PANEL MIDI), de -4 à +4, gardé par firmware. Réglages > Clavier : notes **aucune**, **les do** (C4, C5…, par défaut)
  ou **toutes** ; et l'octave à recaler si le firmware en change autrement.
- `EN-SUSPENS.md` : la liste des sujets en attente.

## v0.17.3 : correctif de compilation
- 0.17.2 ne se compilait pas (`pthread_atfork` introuvable) : le chemin ajouté en 0.17.1 vers le dossier C++ du NDK
  contient aussi `libc.a`, qui passait devant la `libc.so` du téléphone. Chemin retiré : clang trouve `libc++abi`
  tout seul. (Ce même chemin explique sans doute aussi la fermeture de 0.17.1.)

## v0.17.2 : correctif de démarrage
- 0.17 se fermait à l'ouverture : la bibliothèque native était liée avec le runtime C++ dans le mauvais ordre
  (`c++abi` avant le code qui en a besoin), il lui manquait des symboles et Android refusait de la charger. Ordre
  corrigé dans `native/build.rs`, et `-Wl,--no-undefined` : un symbole manquant fait maintenant échouer la
  compilation sur GitHub au lieu de l'app sur le téléphone.
- Si la bibliothèque ne se charge pas, l'app l'écrit à l'écran (avec un bouton pour copier l'erreur) au lieu de se
  fermer. Toute autre fermeture inattendue est gardée et montrée à l'ouverture suivante, à copier.

## v0.17 : moteur d'effets XY (étape 1 du brief : 11 effets)
- **Le pad n'est plus que dans la zone du milieu** (la plus grande) ; le bas garde le clavier et le mixeur.
- **Mise en page** : les deux tiers du haut sont le pad ; le tiers du bas a trois rangées : le **type** (Filtre,
  Delay, Reverb, Modulation, Distorsion, Pitch, Glitch, Dynamique), puis le **nom** de l'effet dans ce type (une
  deuxième touche sur l'effet choisi le retire), puis le **send** : TOUT, ÉMULÉ ou FM-1, HOLD, le tempo et TAP.
- **Effets** (`native/fx`) : Filtre Moog, Écho (temps calés sur le tempo : 1/16 à 1/2), Hall, Nuage diffus, Phaser,
  Flanger, Overdrive, Pitch (demi-tons), Tape stop, Beat repeat (1/4 à 1/64, calé sur la grille), Pompe. Les neuf
  premiers et la Pompe sont écrits en Faust (`native/fx/faust/*.dsp`, code C++ généré dans `native/fx/generated`,
  `tools/fx_regen.sh` le refait) ; Tape stop et Beat repeat en C++ (`fxengine.cpp`).
- **Couche XY** (`native/src/fx.rs`, profils dans `assets/fx_profiles.json`) : chaque axe a sa courbe (linéaire,
  exponentielle, crans, gamme), son lissage, son inversion ; geste au relâché (retour, maintien, action) ; montée
  et descente de l'effet (pas de clic) ; HOLD ; les effets « send » (écho, reverbs) laissent sonner leur traîne
  quand on change d'effet ou qu'on lève le doigt, jusqu'au silence.
- **Tempo** : l'horloge MIDI du FM-1 (LOOM-8) quand elle arrive, sinon un tempo interne réglé avec TAP.
- **Sûreté audio** : traitement par blocs de 64 échantillons, valeurs non finies remises à zéro (l'effet est
  réinitialisé), dénormaux coupés (bit FZ sur ARM64).
- Tests : `cargo test` (chaque effet seul et au pad, sorties finies, traînes, tap tempo) ; `examples/fx_bench.rs`
  (chaque effet coûte moins de 2 % du temps réel sur ordinateur).
- Écarts au brief : le chemin audio existant (Rust, AudioTrack, capture USB) est gardé au lieu d'Oboe ; les effets
  C++ sont liés au Rust par une petite interface C (`fxengine.h`).
- Licences : `NOTICE.md`, `native/fx/generated/README.md`, et l'écran « À propos et licences ».

## v0.16 : démarrage à vide, écran en trois parties, enregistreur, mixeur, pad d'effets
- **Pas de firmware imposé** : au premier lancement, aucun firmware ; le catalogue s'ouvre pour choisir. Ensuite,
  chaque ouverture reprend le dernier firmware lancé. Le catalogue n'a plus de firmware en tête de liste.
- **Bouton marche/arrêt** dessiné (le symbole d'alimentation, `PowerButton`), vert allumé, rouge éteint.
- **Trois parties** (`Zones.kt`) : le bandeau (inchangé), la zone du milieu, la zone du bas. Chacune des deux
  dernières montre une page à la fois ; un glissé qui part du bord gauche ou droit de la zone change de page (le
  milieu reste à l'instrument : touches qui glissent, potards), ou une touche sur les points sous la zone. La page
  choisie est retenue.
  - milieu : l'écran, les encodeurs et les boutons du FM-1 / l'enregistreur / le pad d'effets ;
  - bas : le clavier / le mixeur et EQ / le pad d'effets.
- **Enregistreur** (`RecorderView`) : un chrono, l'onde du mix enregistré qui se dessine pendant l'enregistrement
  (rouge là où ça sature), un gros bouton ; après une prise, « Découper › » l'ouvre dans le découpeur.
- **Mixeur** (`MixerView`) : trois tranches (émulé, FM-1, master), un curseur à glissière chacune (0 à 150 %, double
  tape : 100 %), un vumètre avec un voyant de saturation (rouge 1,5 s), trois potards d'EQ (grave, médium, aigu,
  ±12 dB, double tape : 0) ; sur le master, le travail du limiteur.
- **Pad d'effets** (remplacé en 0.17), à la Kaoss Pad : l'effet joue tant que le doigt est posé (HOLD le
  garde), sa place le règle. Filtre (gauche passe-bas, droite passe-haut, résonance vers le haut), écho (temps de
  40 à 740 ms, répétitions ; sa traîne continue après le doigt), crush (échantillonnage, bits). Sur tout, sur
  l'émulé ou sur le FM-1 (avec la sortie sur le FM-1, l'effet sur le FM-1 ne s'entend que dans l'enregistrement).
  Testés dans `dsp.rs` (fx_tests). Enregistré dans « Le mix ».
- `tools/layout_check` vérifie aussi les deux zones (tout tient, chaque touche et chaque bouton répond).

## v0.15 : chaque firmware revient tel qu'on l'a laissé
- **Sa mémoire** : ce que le firmware enregistre lui-même (sons, presets, réglages, projets : sa flash émulée, comme
  sur un vrai FM-1) a son propre fichier par firmware (`state/<firmware>.fm1s`, seulement les secteurs qu'il a écrits :
  `native/src/state.rs`). Il est écrit une seconde après chaque enregistrement du firmware (`Native.savedIfChanged`),
  pas seulement à l'arrêt : un plantage ou une appli tuée ne perd qu'une seconde au plus. Une nouvelle version du
  même firmware démarre avec, comme un vrai FM-1 garde ses données à une mise à jour (avant, une mise à jour repartait
  de zéro). Catalogue > le firmware > Mémoire : « Effacer sa mémoire ».
- **Ses réglages dans l'appli** : horloge, mode MIDI et profil, volume, balance et EQ de l'émulé, MASTER de l'écran,
  par firmware (`nom@firmware`). Le FM-1 branché, le master et la sortie restent communs.
- Limite : une modification que le firmware n'a pas encore enregistrée (l'astérisque de Chordism) vit dans la RAM
  émulée, qui n'est pas gardée, comme sur un vrai FM-1 éteint. Vérifié : un volume de Chordism enregistré revient
  au redémarrage (`examples/save_check.rs`), `tests/boot.rs` saved_state_comes_back.

## v0.14 : encodeurs corrigés pour de bon, EQ
- **Encodeurs** : le rythme fixe de 0.13 (20 ms par cran, au-delà de 40 en attente jetés) faisait traîner et perdre
  les tours rapides. Maintenant chaque cran est joué dès que possible, un à la fois, 9 ms au moins entre deux (le temps
  qu'un cran met dans l'émulateur : avant, deux crans arrivés ensemble se marchaient dessus) ; aucun n'est jeté.
  `tests/enc_bursts.rs` : 30 crans = 30, lents, réguliers, groupés par USB, plusieurs par message, et un tour rapide
  (30 en 150 ms).
- **EQ** (Réglages > Son > Mixeur et EQ) : trois bandes (grave 150 Hz en plateau, médium 1 kHz en cloche, aigu 6 kHz
  en plateau, ±12 dB) sur chaque FM-1 et sur le master. Ordre : EQ de la source, volume, balance, master, EQ master,
  limiteur. Vaut pour la sortie et « Le mix » enregistré (`dsp.rs` Eq3, `Native.setEq`).

## v0.13 : mixeur (et un rythme d'encodeurs remplacé en 0.14)
- **Encodeurs** : les crans reçus en MIDI (PANEL MIDI en envoie 2 ou 3 par message) étaient rejoués par l'émulateur en
  8 ms chacun ; Chordism, qui accélère les tours rapides, comptait alors 36 à 38 crans pour 30. Ils sont maintenant
  rejoués un par un, 20 ms au moins entre deux (`engine.rs` ENC_PACE_MS ; Chordism les compte juste dès 16 ms) :
  30 crans = 30, vérifié par `native/tests/enc_bursts.rs`. Les encodeurs de l'écran passent par le même chemin.
- **Mixeur** (Réglages > Son > Mixeur) : volume et balance de chaque source (FM-1 émulé, FM-1 branché), master.
  Vaut pour la sortie et l'enregistrement « Le mix » ; « Deux pistes » reste brut (`dsp.rs` `lr`, `Native.setMix`).
- `native/tests/multi.rs` : plusieurs émulateurs en même temps (mesure de charge), `screen_traffic.rs` : débit d'un
  écran recopié.

## v0.12 : les contrôles du FM-1 branché pilotent l'émulé (LOOM-8 PANEL MIDI)
- Avec un FM-1 sous **LOOM-8 forké avec PANEL MIDI** (`loom8-panelmidi-v2.patch`), l'appli le trouve toute seule
  (nom « LOOM-8 » puis réponse à SB_PANEL) : réglages > MIDI > « Contrôles du FM-1 branché » > **Piloter l'émulé**.
  Raccourcis : **appui long sur MIDI** dans la barre du haut, ou **GLO + FX une seconde** sur le FM-1 ; Setup > USB and
  MIDI > PANEL MIDI dans LOOM-8 aussi. La pastille devient « PANEL ».
- Pendant ce temps, le profil `remotefm1-panel` est choisi tout seul (l'ancien réglage MIDI revient après) et les
  **voyants de l'émulé sont renvoyés au FM-1** (SysEx SB_LEDS, 41 niveaux, à chaque changement, 20 fois par seconde au
  plus, et toutes les 500 ms) : ses boutons et touches montrent ce que joue Chordism.
- L'appli rend le panneau à LOOM-8 quand elle passe en arrière-plan ou se ferme. Sans les voyants (appli fermée,
  câble débranché), LOOM-8 n'allume que GLO et FX : le chemin du retour. LOOM-8 continue de jouer et d'envoyer
  son horloge et son audio USB pendant tout ce temps.
- Code : `PanelRemote.kt` (fil à part : demande l'état chaque seconde, envoie les voyants), `LoomLink.post`.

## v0.11 : son plus propre, latence réglable, casque USB
- **Plus de clics sur le son du FM-1 branché** (`native/src/dsp.rs`, `DriftReader`) : l'horloge du FM-1 et celle
  du téléphone ne tournent jamais exactement à la même vitesse. Au lieu de jeter ou de répéter des morceaux, l'appli
  lit le son capté à une vitesse ajustée en continu (interpolation cubique, ±0,3 % au plus, la dérive réelle étant
  de l'ordre de 0,01 %), réglée sur le remplissage de sa réserve ; une réserve vide s'éteint en fondu et repart en
  fondu. Testé : un FM-1 300 ppm trop rapide ou trop lent, son arrivant par paquets USB de 8 ms, pendant une minute :
  aucun manque, aucun saut, vitesse moyenne exacte ; et de bout en bout avec Chordism émulé.
- **Latence basse** (Réglages > Son) : réserves de 20 ms (émulé) et 25 ms (FM-1 branché) au lieu de 50 et 60.
  **Alignement** : retarde l'un des deux FM-1 de 0 à 100 ms pour les remettre ensemble.
- **Limiteur** (-1 dBFS, attaque immédiate, relâchement 100 ms, puis écrêtage doux) sur l'écoute et sur le mix
  enregistré en 16/24 bits (le 32 bits flottant garde tout). La ligne d'état indique quand il travaille.
- **Casque USB-C** : l'appli reconnaît le FM-1 (`Fm1Id.kt` : l'identité 1209:0001 des firmwares de la famille
  Felucca, un nom de FM-1, ou une interface MIDI) et ne le confond plus avec un casque ou un adaptateur USB-C : avec un
  hub USB-C, FM-1 et casque branchés ensemble, le son « sur le téléphone » va au casque (le moins de retard), puis à
  un casque filaire, au Bluetooth LE Audio, au Bluetooth classique, au haut-parleur. Le micro d'un casque USB n'est
  jamais pris pour le son du FM-1.
- La ligne d'état montre la réserve du FM-1 branché (ms) et l'écart des horloges (ppm).

## v0.10 : catalogue de firmwares et nouveaux menus
- **Plus aucun firmware dans l'appli.** Le nom du firmware en haut à gauche (▾) ouvre le catalogue : la liste de
  fm1-emu (tout ce que l'émulateur fait tourner, d'après The Firmware Hub de zvenson, avec la licence de chacun et
  l'horloge minimale mesurée), complétée par le catalogue du Hub quand il est lisible (versions plus récentes,
  SHA-256, genres) et par nos ajouts (Chordism, depuis sa page de publication GitHub). Une copie de la liste est
  livrée pour le premier lancement et hors réseau ; elle est relue au plus une fois par jour (ou sur demande).
- Un firmware se télécharge quand on le choisit, vérifié par SHA-256 quand le Hub en donne un, et reste sur le
  téléphone (« Sur le téléphone », suppression dans sa fiche). Au premier lancement, Chordism se télécharge seul et
  la machine démarre.
- Recherche (nom, auteur, style, licence), filtres, une fiche par firmware (description, licence, horloge, liens :
  code source, page du Hub, installateur sur le FM-1), import d'un .fwsc.
- **Menus refaits** en feuilles qui montent du bas (`Sheet.kt`), comme les réglages d'Android et d'iOS : groupes de
  lignes, chaque ligne montre sa valeur actuelle et ouvre une page ; les choix sont des listes cochées avec ce que fait
  chaque option ; retour par la flèche, le bouton retour du téléphone ou un glissement vers le bas.
- Horloge « Auto » : le minimum mesuré par fm1-emu pour le firmware, avec une marge (moins de travail pour le téléphone).
- Vérifié sur ordinateur (`tools/catalog_check`, 25 points sur la vraie liste de 202 firmwares) : lecture, fusion avec
  le Hub, comparaison de versions, recherche, rafraîchissement d'un jour, liste invalide refusée, téléchargement vérifié,
  mauvaise empreinte refusée, mise à jour et suppression ; tout le Kotlin compile contre Android 35.

## v0.9 : découpe (CHOP de SLOOP) et envoi vers LOOM-8
Bouton ✂ dans la barre du haut (ou « Enregistrer et découper » à l'arrêt d'un enregistrement) :
- ouvre un enregistrement de Musique/FM1 Mix (ou n'importe quel WAV, 5 minutes au plus) ;
- repères par les attaques (sensibilité 1-10), par TAP pendant l'écoute (calés sur l'attaque la plus proche), en
  grille au tempo (1 mesure à 1/16) ou en parts égales ; toucher la forme d'onde écoute un morceau, appui long le
  garde ou l'écarte ;
- export des morceaux gardés ou de la boucle en WAV dans Musique/FM1 Mix/Découpes/<nom>, au format de la source,
  avec en option le niveau commun de SLOOP (le plus fort à 30000, les écarts gardés) ;
- envoi vers le firmware du FM-1 branché quand il prend des échantillons : aujourd'hui **LOOM-8** (une boucle de
  N mesures que son moteur slice joue au tempo, ou les morceaux comme sons), sans effacer sa banque (relue d'abord,
  sauvegardée dans l'appli, « Annuler le dernier envoi » la remet).

Le découpeur travaille sur le son de l'appli (ses WAV) : le langage ou le moteur d'un firmware n'y change rien.
Seul l'envoi d'échantillons dépend du firmware : `SampleTargets.kt` le reconnaît à son nom USB (« LOOM-8 FM-1 »...),
le confirme par une question sans risque (LOOM-8 : SB_PING) avant tout envoi, et propose ce qu'il sait faire ; un
firmware inconnu ou sans protocole ici a l'export WAV. Ajouter un firmware = ajouter une cible dans `SampleTargets.ALL`.

Vérifications (sur ordinateur) :
- `tools/chop_check` : le portage Kotlin contre les fonctions CHOP extraites de l'éditeur de SLOOP, sur son propre
  signal de test : nouveauté, attaques (sensibilités 1-10), calage des TAP, grilles, parts, options, fondus :
  identiques ; et les affirmations des tests de SLOOP ; plus l'app : WAV 24 bits stéréo 44,1 kHz découpé et exporté.
- `tools/loom_check` : `LoomSamples.kt` contre `loom_samples.js` de LOOM-8 : banque, rééchantillonnage, choix de la
  fréquence, trames SysEx (134 479 octets) identiques octet pour octet ; puis ces trames jouées dans le simulateur de
  LOOM-8 (chaque trame acceptée, contrôle final bon, banque relue identique).
- `tools/android_check` : tout le Kotlin de l'appli compile contre Android 35 (doublures AndroidX).

## v0.8 : enregistrement en WAV
Bouton ● dans la barre du haut (le temps s'affiche pendant l'enregistrement). À l'arrêt, une fenêtre propose un nom
(prérempli avec la date) : Enregistrer ou Supprimer. Fichiers dans **Musique/FM1 Mix** (MediaStore : aucune
autorisation de stockage, visibles des applis de musique, de fichiers et des DAW).
Réglages > Enregistrement :
- le mix tel qu'on l'entend (curseurs compris, même quand le son sort sur le FM-1), ou deux pistes séparées
  (« nom - émulé », « nom - FM-1 », chacune telle qu'elle arrive, alignées) ;
- 16 bits, 24 bits ou 32 bits flottant ; 44,1 kHz (natif) ou 48 kHz (rééchantillonné) ; stéréo ou mono.
L'écriture est native (`native/src/recorder.rs`), hors du fil audio. Testé sur ordinateur : en-têtes WAV (PCM et
flottant), mix et pistes, 24 bits et flottant sans saturation, rééchantillonnage 48 kHz (niveau et hauteur), relus
par le module `wave` de Python et par scipy.

## v0.7 : pilote USB intégré pour le son du FM-1
Sur un Pixel 8a (Android 16), le FM-1 apparaît comme sortie audio mais jamais comme entrée : toutes les entrées
du téléphone tournent à 48 kHz, le FM-1 n'envoie que du 44,1 kHz. Quand Android ne propose pas d'entrée USB,
l'appli lit elle-même le FM-1 :
- `UsbAudioIn.kt` trouve l'interface audio qui envoie vers le téléphone, demande l'accès au FM-1 (fenêtre
  d'Android), la prend au pilote d'Android (le MIDI et la sortie restent à Android) et choisit son réglage actif ;
- `native/src/usbin.rs` envoie des requêtes isochrones (usbdevfs, comme libusb), garde les canaux 1-2 (le master,
  aussi sur les 10 canaux de LOOM-8) et les passe au mixeur.
Testé sur ordinateur : lecture des descriptions (Chordism 2 canaux, LOOM-8 10 canaux), conversion des
échantillons, disposition mémoire et numéros d'ioctl du noyau, échec propre sur un descripteur non USB.
Non testé : sur un vrai FM-1 et un vrai téléphone.

## v0.6 : capter le son de LOOM-8
LOOM-8 envoie 10 canaux en USB (master sur 1-2, pistes sur 3-10). L'appli demande d'abord à Android les canaux 1 et 2
par leur numéro (aucun pilote ne les mélange), puis la stéréo, puis tous les canaux de l'appareil, et garde 1-2
(`CaptureFormat.kt`, vérifié par `tools/capture_check`). Un format qu'Android détourne vers le micro du téléphone est
écarté. La capture se rouvre quand le FM-1 est branché après le lancement. La ligne d'état indique le format retenu
et le débit reçu (environ 44 100 Hz attendus) ; « capture impossible (...) » donne la raison de chaque échec.
Le choix de sortie (v0.5) reste en option : il dépend du firmware (LOOM-8 n'a pas de sortie USB, `usb_audio_out = false`).

## v0.5 : choisir où sort le son
Dès qu'un appareil audio USB est branché, Android lui envoie le son des applis ; LOOM-8 (fm1-base) joue ce que le
téléphone lui envoie sur son haut-parleur et son casque, mélangé à son propre son. D'où le son sur le FM-1.
Réglages > « Sortie du son » :
- **Sur le FM-1 (USB)** : le téléphone n'envoie que l'émulé (renvoyer au FM-1 son propre son le ferait jouer deux fois).
- **Sur le téléphone** : casque Bluetooth s'il est connecté, sinon haut-parleur, avec le mix complet. Baisser MASTER
  sur le FM-1 et régler LOOM-8 Setup > USB audio sur « stereo » pour qu'il envoie quand même son son à plein niveau.
Dans les deux cas le téléphone a les deux sons (l'émulé et celui capté en USB) : c'est là que se branchera
l'enregistrement en WAV.

## v0.4 : démonstration prête à l'emploi
- Chordism 0.9 (Charles Vestal, MPL-2.0) est intégré : la machine s'allume toute seule à l'ouverture de l'appli et
  démarre dessus. Le bouton ⏻ de la barre du haut l'éteint ou la rallume ; l'appli s'en souvient.
- Panneau en portrait, qui s'adapte à la taille de l'écran (`PanelLayout.kt`, vérifié sur 8 tailles par
  `tools/layout_check`) : MASTER, SELECT, PRST, ALGO, OCT -/+, écran, KNOB1-4, deux rangées de boutons, clavier
  sur deux étages (F4-G5 en haut, F3-E4 en bas).
- Barre du haut : nom du firmware (toucher pour en changer ou importer un .fwsc), ⏻, voyant MIDI, charge CPU, ⋮ réglages.
- Corrige l'interface cachée sous la barre d'état d'Android 15.
- Clé de signature fixe (`app/debug.keystore`) : les prochaines versions s'installent par-dessus.

## v0.3 : un vrai panneau tactile
L'appli s'affiche en paysage avec le panneau du FM-1 dessiné à l'échelle (écran, 7 encodeurs, potentiomètre MASTER,
14 boutons avec leurs LEDs, 27 touches). On tourne un bouton en le faisant glisser ; la hauteur du doigt sur une
touche donne la vélocité ; un doigt qui glisse sur plusieurs touches les joue à la suite ; plusieurs doigts à la
fois. Le menu (firmware, MIDI, mixage) est derrière le bouton « Menu ». La disposition reprend fm1-emu
(`panel.rs`, MIT, Joakim Eriksson) ; `PanelGeometry.kt` en est le portage, vérifié point par point contre l'original
(`tools/panel_check`, 117 025 points identiques). Le dessin lui-même (PanelView) n'est pas testé sur un appareil.

## v0.2 : le MIDI du téléphone pilote l'émulé
L'appli ne filtre plus le MIDI. Trois modes (choisis dans l'appli, retenus entre deux lancements) :

| Mode | Effet | Pour quoi faire |
| --- | --- | --- |
| Horloge seulement | seuls clock / start / continue / stop atteignent le firmware émulé | FM-1 sous LOOM-8 (ou autre firmware) maître d'horloge |
| Tout vers MIDI IN | chaque message va à l'entrée MIDI de l'émulé tel quel | clavier MIDI classique jouant Chordism |
| Piloter le panneau | un profil (format fm1-map) transforme notes et CC en touches, boutons, encodeurs et MASTER de l'émulé | FM-1 sous RemoteFM1, ou n'importe quel contrôleur |

Profils fournis (`app/src/main/assets/profiles/`, générés par `tools/gen_profiles.py` d'après
RemoteFM1 `docs/PROTOCOL.md`) :
- `remotefm1-panel.json` : le format « événements de panneau » de RemoteFM1 (touches canal 16, boutons canal 15,
  encodeurs CC 16-22 relatifs, MASTER CC 7/39). RemoteFM1 ne l'émet qu'en session hôte, que cette appli n'ouvre pas ;
  en revanche PANEL MIDI (LOOM-8 forké, `loom8-panelmidi-v2.patch`) émet exactement ce format : **c'est le profil
  d'un FM-1 sous LOOM-8 avec PANEL MIDI**, choisi tout seul quand le panneau arrive (voir v0.12).
- `remotefm1-midi-remote-*.json` : le mode autonome « MIDI REMOTE » de RemoteFM1 (GLO + FX sur le FM-1) :
  touches en notes, boutons CC 20-31/85/86, encodeurs CC 102-108, MASTER CC 109. Trois variantes selon le
  réglage KNOBS de la page de réglages (absolu, REL 1/127, REL 65/63). **C'est celui à utiliser avec ce téléphone.**
- `generic-keyboard.json` et `akai-mpk-mini-3.json` (repris de fm1-emu), et « Charger un profil JSON… » pour les tiens.
Le moniteur affiche le nombre de messages reçus et le dernier (hexadécimal), utile pour écrire un profil.

**Un FM-1 polyvalent ?** Oui avec LOOM-8 + PANEL MIDI : le même FM-1 joue LOOM-8 ou pilote l'émulé, au choix
(GLO + FX, ou l'appli), sans réinstaller de firmware.

## Ce qui est vérifié, ce qui ne l'est pas
Vérifié (sur ordinateur, avec Chordism 0.9 : `native/tests/`) : le crate Rust compile ; le firmware démarre,
l'écran se remplit, le son sort, un faux signal « externe » est mixé ; en mode « horloge seulement » une
note ne joue rien ; en mode panneau, une touche joue (profil RemoteFM1 session et profil MIDI REMOTE), un
bouton et un encodeur changent l'écran ; le découpeur de flux MIDI (running status, temps réel, SysEx) est
testé. Les profils suivent la documentation de RemoteFM1, **pas un FM-1 réel** : à confirmer avec le tien.
**Non vérifié** (je n'avais ni SDK ni NDK Android) : la compilation Kotlin/Gradle, les écrans de choix de mode, le JIT et les
performances sur ton téléphone, la capture USB audio, la lecture MIDI, et surtout que LOOM-8 émette
une horloge et que Chordism la suive.

## Construire
1. `git submodule update --init` (ou `git clone https://github.com/joakimeriksson/fm1-emu third_party/fm1-emu`)
2. Rust stable récent (>= 1.88 ; testé en 1.97), NDK Android, `cargo install cargo-ndk`
3. `./build-native.sh` puis ouvrir le dossier dans Android Studio et lancer `app`
   (versions AGP/Kotlin de `build.gradle.kts` à ajuster si besoin).
4. Test moteur seul : `cd native && FM1_TEST_FWSC=chordism-0.9.fwsc cargo test --release -- --nocapture`

## Obtenir un APK sans installer Android Studio
Le dépôt contient `.github/workflows/build-apk.yml` : crée un dépôt GitHub (gratuit), envoie-y ce dossier, puis
onglet Actions > « Build APK » > Run workflow ; télécharge `fm1mix-debug-apk` (app-debug.apk) et installe-le sur le
téléphone (autoriser les sources inconnues). Non testé : si le build échoue, le journal de l'étape en rouge dit pourquoi.

## Essai sur le téléphone
1. Brancher le FM-1 (LOOM-8) en USB-C, accorder l'accès micro (nécessaire à la capture USB).
2. « Firmware… » : choisir `chordism-0.9.fwsc`. Le statut doit afficher le facteur temps réel, des
   sous-alimentations à 0, et « capture from … » si le FM-1 est vu comme entrée audio.
3. Lancer le séquenceur de LOOM-8 : le moniteur doit passer à « horloge reçue ». Sinon, LOOM-8 n'émet
   pas d'horloge (ou Android ne l'expose pas) : voir son manuel, section MIDI.
4. Régler les deux curseurs de mixage ; les niveaux s'affichent sous l'écran.

## Limites connues
- Un seul firmware émulé ; le moteur s'arrête quand l'appli quitte l'écran (pas de service d'arrière-plan).
- Dérive entre horloges : l'entrée USB est ramenée à 30-120 ms en jetant des échantillons (grossier,
  des clics sont possibles). Un rééchantillonnage adaptatif serait la suite.
- Le mixage reste dans l'appli : rien n'est enregistré (l'enregistrement WAV de la sortie mixée est à ajouter).
- Panneau minimal (boutons Android), pas de reproduction du visuel de la façade.
- Le mode MIDI s'applique à tous les appareils MIDI branchés (pas de réglage par appareil), et il n'y a pas
  encore d'apprentissage MIDI (fm1-map le permet : à exposer). Si ton FM-1 envoie ses boutons sur d'autres
  canaux ou CC que ceux de la doc, édite le profil JSON.
- RemoteFM1 évolue vite (les versions 0.2.x et 0.3.x diffèrent) : relis `docs/PROTOCOL.md` de ta version.
