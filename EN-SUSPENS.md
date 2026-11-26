# Sujets en suspens

Ce qui reste à faire ou à décider, tenu à jour au fil des versions (dernière mise à jour : 0.28).

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

## Lecteur de boucles (0.23)
- Fait en 0.24 : la tranche BOUCLES fine au mixeur, l'envoi BOUCLES aux effets (l'option « aussi dans TOUT » a
  disparu en 0.28 : chaque boucle a sa piste d'effet, et TOUT prend tout le mix).
- Fait en 0.25 : l'écran du passage (fichiers longs : quelle partie, aimant aux attaques, écoute).
- Fait en 0.27 : les noms des boucles (option), ↻ visible, les projets (firmware + mémoire + mixeur + boucles).
- Fait en 0.28 : les projets gardent aussi l'effet de chaque piste et les motions ; bandeau des projets ; export.
- Projets, plus tard si besoin : le profil MIDI importé ; « Nouveau projet » (repartir de zéro) ; le point orange ne
  voit pas les changements de la mémoire du firmware (ses patchs), seulement ceux de l'app.
- Plus tard, si besoin : suivre le tempo (vitesse ou étirement), d'autres formats que le WAV (MP3, M4A), plusieurs
  groupes de 4, le départ sur évènement (déjà dans le moteur), les boucles dans « Deux pistes ».
- À vérifier en jeu : le calage sur l'horloge du FM-1 (s'il faut un réglage de décalage).
- **Transposer les boucles** (demandé le 25 novembre) : changer la hauteur d'une boucle, en demi-tons (et en
  centièmes pour accorder finement), par boucle. À choisir ensemble :
  - comme une platine (vitesse et hauteur liées : simple, léger pour le processeur, mais la boucle se raccourcit ou
    s'allonge et ne tombe plus juste sur l'horloge) ;
  - ou en gardant la durée (changement de hauteur sans changer le tempo : reste calée sur l'horloge, plus gourmand,
    un peu de couleur sur le son) ;
  - où le régler (réglages de la boucle, ou une puce sur sa rangée), et s'il se pilote depuis le FM-1 (une note du
    clavier en PANEL MIDI pour transposer en jouant) ou s'enregistre dans le motion.

## Motion recording (fait en 0.28)
- Choisi : enregistrer les gestes (pad, faders, EQ, potard et encodeurs de l'écran), un jeu de 4 patterns par piste.
- **Pas enregistrés** : les encodeurs du vrai FM-1 en PANEL MIDI (ils vont directement à l'émulé, sans passer par
  l'écran). À faire si besoin.
- À vérifier en jeu : le calage sur l'horloge du FM-1, la reprise en main (doigt, fader), la longueur 8 mesures.
- Plus tard, si besoin : le téléphone comme manette (gyroscope, accéléromètre) ; quantifier les gestes.

## Envoi d'échantillons vers LOOM-8 (découpeur) : gelé en 0.22
- Jamais testé sur un vrai FM-1 : gelé (`SampleTargets.SEND_ENABLED = false`), l'export WAV seul. À tester un jour
  avec une banque sauvegardée d'abord (page Backup du site de LOOM-8), puis remettre à `true`.

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

## Bugs signalés le 25 novembre

### 1. La vitesse des potards n'agit pas (Coralflower, PANEL MIDI : un cran = ±4 lignes) — réglé par l'option « tous »
- **Vérifié le 25 novembre (Richard)** : avec l'option « tous » cochée (Réglages › Potards), la vitesse marche
  parfaitement. Reste à décider : cocher « tous » d'office (au moins pour les firmwares qui comptent les contacts),
  et la correction en quarts de cran ci-dessous, si un firmware en a encore besoin.
- Ce qui arrive : LOOM-8 envoie bien **un cran = +1** (`panelmidi.c` : « 64 + crans »). L'app le passe à l'émulateur,
  qui le joue comme **un cycle complet de 4 contacts** (`fm1-soc/src/periph.rs`, `Action::Enc` : `clicks * 4`). Un
  firmware qui avance d'une ligne **par contact** (Coralflower, apparemment) fait donc 4 lignes par cran.
- Pourquoi le réglage n'y peut rien : il compte en **crans entiers** (`engine.rs`, `scale_detents`). À ¼×, il faut
  4 crans pour en jouer 1, et ce cran fait toujours 4 contacts, donc 4 lignes. Il ne descend jamais sous un cran.
  De plus, il ne touche que KNOB 1-4 ; SELECT, ALGORITHM et PRESETS seulement avec l'option « tous ».
- Correction proposée : que l'émulateur accepte des **contacts** (quarts de cran) et que le réglage compte en quarts :
  à ¼×, un cran = 1 contact, donc 1 ligne dans Coralflower. Changement dans `periph.rs` (une action en contacts, à côté
  de `Action::Enc`) et dans le routeur (`enc_q` en quarts). Garder un réglage par firmware, et proposer ¼× d'office
  pour les firmwares qui comptent les contacts.
- À vérifier : le même saut sur les potards de l'écran ? Avec l'option « tous » cochée ? (Si l'écran saute aussi, la
  cause est bien l'émulation des contacts, pas PANEL MIDI.)

### 2. L'enregistrement saturé, métallique et étouffé quand le son sort sur le FM-1 — corrigé en 0.29
- **Fait en 0.29** : le pilote de l'app lit aussi le FM-1 quand le son sort sur le FM-1, avec le repli prévu (si
  Android perd sa sortie USB vers le FM-1, l'app lui rend l'entrée et le dit). À confirmer à l'oreille sur le Pixel.
- **Aussi en 0.29** : sortie sur le FM-1, la jauge du master montre le niveau (ce qui est entendu et enregistré, le
  FM-1 compris) ; avant, elle restait vide puisque le son du FM-1 ne passe pas par la sortie du téléphone.
- Ce qui arrive (piste la plus probable) : le son du FM-1 enregistré vient de la **capture d'Android**, celle qui
  abîme le son sur le Pixel 8a (le défaut corrigé en 0.28.1, mais seulement quand le son sort du téléphone : sortie
  sur le FM-1, l'app garde la capture d'Android). L'enregistreur mêle ce son au mix (`engine.rs` : `rec = mix + xf
  × (gxr − gx)`), sans limiteur : d'où aussi la saturation quand tout est fort.
- Écarté : un son en double. LOOM-8 ne renvoie jamais par l'USB ce que le téléphone lui joue (manuel de LOOM-8 :
  « the USB recording holds LOOM-8 alone »).
- Test pour trancher : enregistrer en « deux pistes » (Réglages › Enregistrement). Piste de l'émulé propre et piste
  du FM-1 abîmée : c'est la capture. Les deux abîmées : autre chose (niveaux), à creuser.
- Correction proposée : le pilote de l'app aussi quand le son sort sur le FM-1. Risque : en prenant l'entrée du FM-1
  à Android, Android peut perdre aussi sa sortie USB vers le FM-1. À essayer avec un repli automatique : si la sortie
  USB disparaît, l'app rend l'entrée à Android et le dit. Si le risque se confirme : écrire la sortie vers le FM-1
  dans le pilote de l'app (déjà prévu pour le multi-sources). Et un limiteur doux sur l'enregistrement.

### 3. Pas d'effet sur le FM-1 quand le son sort sur le FM-1 (connu, à réfléchir)
- Pourquoi : sortie sur le FM-1, le son du FM-1 va directement de lui à son haut-parleur ; il ne passe jamais par
  le téléphone, qui n'a donc rien à traiter. Ce n'est pas un réglage : c'est le trajet du son.
- Pistes :
  - **(a) Effets « d'envoi » seulement, retour mouillé** (sans changer LOOM-8) : le FM-1 garde son son sec, le
    téléphone lui renvoie seulement l'effet (le « mouillé »). Marche bien pour les échos, les réverbes, les nuages
    granulaires : le retard de l'aller-retour USB (quelques dizaines de ms) s'entend à peine sur un écho. Pas pour les
    filtres ou la distorsion, qui doivent remplacer le son sec. À faire dans l'app : sur la piste FM-1, sortie FM-1,
    ne proposer que ces effets, et n'envoyer que leur part mouillée.
  - **(b) Mode « insert » dans notre LOOM-8** : une option USB AUDIO IN « remplace » où LOOM-8 coupe son propre son et
    ne joue que ce que le téléphone lui renvoie (son son, traité). Tous les effets marchent, mais le son du FM-1 arrive
    avec le retard de l'aller-retour : gênant pour jouer au clavier en direct, acceptable pour le séquenceur. Seulement
    pour LOOM-8 (les autres firmwares n'auraient pas l'option).
  - **(c) Le son sur le téléphone** (déjà possible) : tous les effets, au prix de la latence Bluetooth si casque sans fil.
- Proposition : (a) d'abord (simple, sans toucher au firmware), (b) ensuite si le besoin reste.
- **À noter (25 novembre)** : avec un firmware à mode « insert » (piste b : le FM-1 envoie son son au téléphone et ne
  joue que ce que l'app lui renvoie), l'**EQ du master** et les **effets** s'appliquent aussi au son du FM-1 en sortie
  FM-1 : tout le son passe alors par l'app. Le gestionnaire de firmwares (0.29) permet de basculer de firmware pour ça.

### 4. MASTER par PANEL MIDI lent et qui ne remonte pas, horloge, LEDs (signalés le 25 novembre, corrigés en 0.29.1)
- MASTER : l'app le passe à l'émulé en 40 à 60 ms (mesuré, comme le potard de l'écran). Le reste vient de
  CoralFlower, qui lisse son MASTER d'un huitième par image (environ 0,4 s, `firmware/src/master.c`), et de la sortie
  vers le FM-1. « Ne remonte pas » : l'émulé redémarrait au niveau de l'écran, que le FM-1 ne corrigeait qu'en bougeant.
  Corrigé : l'écran suit et garde le MASTER du FM-1, en 14 bits. À voir sur le téléphone.
- Horloge : régularisée (`native/src/clocksync.rs`), boucles sur Start/Stop. À voir avec CoralFlower en clock in ; le
  diagnostic montre l'écart des tops à l'arrivée et joués.
- LEDs : ramenées au plein niveau, quatre niveaux avec marge. Le scintillement n'a pas pu être reproduit sur
  ordinateur (aucun aller-retour mesuré, avant comme après) : à confirmer sur le FM-1.
- Fait en 0.29.1 : le panneau de l'écran suit celui du FM-1 (boutons, touches, encodeurs, MASTER). Coût : une
  lecture de l'état par rafraîchissement (0,08 µs sur ordinateur) ; le panneau était déjà redessiné à chaque
  rafraîchissement (30 par seconde) pour l'écran du FM-1.

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

- **Demandé le 25 novembre** (précise ce qui précède) :
  - jusqu'à **4 FM-1** par un hub USB sur la prise du téléphone ; les reconnaître, les nommer ;
  - un **traitement par source** (EQ, volume, mastering) ; **choisir la source de l'horloge** ; **choisir où va le mix**
    (le téléphone ou l'un des FM-1) ;
  - l'interface s'adapte au nombre de sources : une source de plus ajoute sa tranche et ses réglages, et une page de
    plus quand il en faut pour rester agréable ;
  - à trancher : **le faire grandir dans l'app actuelle** (recommandé : un seul code) ou un **mode « multi-sources »**
    (activé dans les Réglages ou dès que plusieurs FM-1 sont reconnus) qui coupe ce qui coûte (l'émulé, les effets)
    pour se concentrer sur le mix de 4 FM-1.
- Faisabilité (25 novembre) : faisable par le pilote USB de l'app (celui qui lit déjà le FM-1 sur le Pixel 8a) ; pas
  par Android (un seul appareil audio USB à la fois). Points durs :
  - **la sortie vers un FM-1** : aujourd'hui elle passe par Android ; avec plusieurs FM-1, il faudra l'écrire dans le
    pilote de l'app (il ne sait que lire) ;
  - **reconnaître chaque FM-1** : ils ont tous le même identifiant USB (1209:0001), sans doute pas de numéro de série ;
    par la réponse de leur firmware (son nom), par le port du hub, ou en demandant d'appuyer sur une touche du FM-1 à
    nommer ;
  - **le courant** : un hub alimenté (4 FM-1 sur la batterie du téléphone, non) ; un hub « multi-TT » de préférence ;
  - le processeur : une source de plus coûte peu (sa correction de dérive, son EQ) ; l'émulé reste le gros poste.
- Ordre proposé : (1) le pilote lit 2 FM-1 en même temps, une tranche chacun ; (2) nommer et retenir ; (3) l'horloge
  maître relayée ; (4) la sortie vers un FM-1 choisi par le pilote de l'app ; (5) jusqu'à 4, la mise en page qui
  s'adapte ; (6) le mode multi-sources, si l'émulé et les effets ne tiennent pas avec 4.
- **Décidé (25 novembre) : le multi-sources est un état** de l'app, qu'on bascule à la main dans les Réglages (le
  plus sûr, parce qu'il faut une étape de reconnaissance) ; l'app peut le **proposer** quand elle voit plusieurs FM-1,
  sans basculer seule. Le set-up (la reconnaissance et les réglages des sources) est gardé dans l'app, dans les
  projets, et peut s'enregistrer, se charger et s'oublier. Cet état décide des sources proposées partout : flashage,
  enregistreur et découpeur, mixeur, effets, horloge, sortie, PANEL MIDI.
- Propositions d'intégration (25 novembre, à valider) :
  1. **Un seul modèle « sources »** : même hors multi-sources, l'app a une liste de sources, d'une seule entrée (le
     FM-1, reconnu tout seul). Le multi-sources n'est pas une autre app : c'est la même liste, avec jusqu'à 4 FM-1 et
     le set-up. Tout ce qui demande « quelle source ? » lit cette liste ; en mode simple la question ne se pose pas.
  2. **L'assistant de reconnaissance** (Réglages › Sources › Configurer), pas à pas :
     - l'app liste les FM-1 branchés (le hub, ses ports) ;
     - « Appuie sur une touche du FM-1 que tu veux appeler A » : l'app écoute tous les ports MIDI ; le port qui
       répond désigne l'appareil USB, son audio compris (le MIDI et l'audio d'un FM-1 sont sur le même appareil) ;
     - un nom et une couleur par FM-1 (A à D, « Lead », « Basse »…) ;
     - les rôles : qui donne l'horloge (un FM-1 ou l'app) et à qui elle est relayée ; où va le mix (le téléphone ou
       un FM-1) ; quel FM-1 joue l'émulé en PANEL MIDI ;
     - un test : un vumètre par FM-1, « joue une note sur chacun ».
  3. **Le set-up**, un objet à part (les sources, leurs noms, comment les retrouver : port du hub, identité du
     firmware, numéro de série s'il y en a ; leurs volumes, EQ, alignement ; les rôles) :
     - le set-up en cours est gardé dans l'app (retrouvé au lancement) ;
     - il est enregistré **dans chaque projet** ;
     - Réglages › Sources : **Enregistrer le set-up** (dans `FM1 Mix/Set-ups`), **Charger un set-up**, **Oublier le
       set-up** (vider le cache pour en refaire un) ;
     - à l'ouverture d'un projet ou d'un set-up dont les FM-1 ne sont pas ceux branchés : l'app propose de les
       rattacher (« ce FM-1 est-il A ? »), sinon l'assistant.
  4. **Ce que l'état change** :

     | Fonction | Mode simple | Multi-sources |
     | --- | --- | --- |
     | Mixeur | comme aujourd'hui | une tranche par FM-1 (+ émulé, boucles, master), une page de plus au-delà de 4 tranches |
     | Effets | piste FM-1 | une piste par FM-1 (FM-1 A à D) ; option : effets coupés pour soulager le processeur |
     | Horloge | celle du FM-1 | le maître choisi, relayée aux autres |
     | Sortie | téléphone ou FM-1 | téléphone ou le FM-1 choisi |
     | PANEL MIDI | le FM-1 | le FM-1 choisi |
     | Enregistreur, découpeur | tout, ou deux pistes | cases à cocher : tout, l'émulé, chaque FM-1, les boucles |
     | Flashage | le FM-1 branché | le FM-1 à flasher choisi (les autres mis en pause) |
     | Motion | faders et EQ des tranches | les tranches de chaque FM-1 aussi |
     | Émulé | toujours là | option : coupé (« multi sans émulé ») si le processeur ne suit pas |

  5. **Le bandeau** : en multi-sources, un petit indicateur (« 3 FM-1 ») ; le toucher ouvre Réglages › Sources.
- Ordre (révisé) : (0) le modèle « sources » à une seule entrée et le set-up gardé dans l'app et les projets, sans
  rien changer pour l'utilisateur ; (1) l'état multi-sources, l'assistant, 2 FM-1 lus en même temps ; (2) l'horloge
  maître et les rôles ; (3) la sortie vers un FM-1 choisi (pilote de l'app) ; (4) jusqu'à 4, les pages du mixeur ;
  (5) les sources dans l'enregistreur, le découpeur, les effets et le flashage.

## Enregistreur et découpeur : choisir les sources (demandé le 25 novembre)
- Choisir la ou les sources enregistrées : **tout** (le mix) par défaut, puis une sélection multiple qui suit les
  sources reconnues (l'émulé, chaque FM-1, les boucles).
- Faisabilité : simple. Le moteur enregistre déjà « le mix » ou « deux pistes » ; ce sera une liste à cocher, un WAV
  par source cochée (ou un seul WAV à plusieurs pistes). Une première version possible dès maintenant (émulé, FM-1,
  boucles, mix) ; elle grandit avec les sources multiples.

## Gestionnaire de firmwares : flasher, sauvegarder, exporter (demandé le 25 novembre)
- La demande :
  - **flasher** un firmware sur le FM-1, avec ou sans **m-bootloader** (au choix, comme l'installateur web de
    Charles) ;
  - **sauvegarder** avant : la version installée et tout ce qu'elle garde (projets, sons) ;
  - une **banque sur le téléphone** (firmwares, sauvegardes, historique), réutilisable ; l'**exporter** comme un
    projet (le même menu Export) ;
  - s'inspirer de M-FLASH, FM-1 Swap et de l'installateur web ; un déroulé par étapes, qu'on enrichit petit à petit ;
  - accessible depuis le choix du firmware (sous-menu) et surtout depuis les Réglages ;
  - avec plusieurs FM-1 branchés : les reconnaître et choisir lequel on flashe ;
  - des **sécurités** pour que rien ne casse pendant le flashage.
- Faisabilité (25 novembre) : **faisable**, car l'installation passe par le **MIDI USB** (des messages SysEx), que l'app
  sait déjà parler. C'est le protocole de `fm1-base` (`web/fm1ota.js`, `tools/fm1_install.py`, Charles Vestal et
  Leo Kuroshita) : le firmware en marche lance le chargeur, le chargeur demande le paquet `.fwsc` morceau par morceau ;
  l'installation rapide ne réécrit que les secteurs qui changent et **reprend** une installation coupée. m-bootloader
  (jvitkauskas, MIT) s'installe de la même façon, après le firmware.
- Les limites :
  - **la sauvegarde de la version installée** : le protocole écrit, il ne relit pas le firmware. On garde donc le
    paquet qu'on a installé (et l'identité que le FM-1 annonce) ; le firmware d'origine de M-VAVE se retélécharge chez
    M-VAVE (empreintes vérifiées, comme le script de secours) ;
  - **sauvegarder les données** (projets, sons) : seulement pour les firmwares qui le proposent (ceux de `fm1-base`
    construits avec la sauvegarde : LOOM-8, ses sons compris, par `tools/fm1_backup.py`) ; pas pour les autres ;
  - **le mode de mise à jour de la puce** (écran noir) : l'app ne peut pas l'atteindre (il faut le script de secours
    sur un ordinateur, ou m-bootloader déjà installé). D'où l'intérêt de proposer m-bootloader.
- Les sécurités prévues : l'empreinte (SHA-256) et le modèle vérifiés avant d'écrire ; batterie suffisante ou
  téléphone en charge ; écran allumé et l'app gardée au premier plan pendant l'écriture ; l'émulé et PANEL MIDI mis en
  pause (le port MIDI réservé au flashage) ; un seul FM-1 à la fois ; « ne débranche pas » affiché ; reprise si c'est
  coupé ; l'identité relue après le redémarrage ; l'historique (quoi, quand, réussi ou non).
- Licence à vérifier par un humain : `fm1ota.js` et `fm1_install.py` sont sous MPL-2.0. Les réécrire en Kotlin en
  gardant ces fichiers sous MPL-2.0 dans une app GPL-3.0 devrait aller (la MPL-2.0 le permet, sauf mention contraire),
  mais à confirmer avant de commencer.
- Ordre proposé : (1) installer un firmware du catalogue (vérifications, progression, reprise) ; (2) la banque et
  l'historique ; (3) la sauvegarde et la restauration des données (firmwares qui le permettent) ; (4) m-bootloader en
  option ; (5) l'export et l'import de la banque ; (6) le choix du FM-1 quand il y en a plusieurs.
- **Étude faite (25 novembre)** :
  - le protocole est porté dans l'app : `Fm1Ota.kt` (MPL-2.0, comme `LoomLink.kt` ; d'après `fm1ota.js` et
    `fm1pkg.js` de fm1-base, version du 22 novembre). Pas encore branché à l'écran ;
  - testé contre **le vrai chargeur de mise à jour du FM-1** compilé sur ordinateur (`bsp/tests/ldr_pipe.c` de
    felucca-bsp) : `tools/ota_check/run.sh BSP_DIR [paquets…]`. Passent : l'installation rapide (seuls les secteurs
    qui changent), le même paquet une 2ᵉ fois (rien d'envoyé), l'installation classique, un chargeur ancien, un paquet
    M-VAVE (sans chargeur Felucca), une coupure de courant puis la reprise (seul le reste est envoyé), un paquet abîmé
    refusé avant tout effacement ; les vrais paquets Chordism 0.9 et m-bootloader 2.4 lus et vérifiés ;
  - **ce qui reste** : (1) brancher le protocole sur le MIDI d'Android (comme `LoomLink.kt`), y compris le FM-1 qui
    disparaît et revient sous un autre nom pendant la mise à jour ; (2) mettre en pause l'émulé et PANEL MIDI pendant
    le flashage ; (3) les écrans (canevas « Flasheur de firmware ») ; (4) la sauvegarde et la restauration des
    données (`fm1_backup.py` et la page `web/backup`, à porter) et la région de ressources (`installRes`, LOOM-8) ;
    (5) m-bootloader (MIT, 18 Ko) fourni avec l'app, sa licence comprise ; (6) le retour au firmware M-VAVE (V16,
    empreinte connue) ; (7) **un essai sur un vrai FM-1**, avec un moyen de secours sous la main (m-bootloader ou le
    script de secours sur ordinateur) ;
  - licence (à confirmer par un humain) : même montage que `LoomLink.kt`, déjà dans l'app : le fichier garde sa
    MPL-2.0, le reste de l'app reste GPL-3.0 ; crédité dans `NOTICE.md`.
- **Décidé (25 novembre)** : on fait le maximum possible malgré les limites, avec m-bootloader et le firmware choisi.
  - **Deux dossiers de firmwares**, sous le dossier de FM1 Mix dans Documents :
    - **Initial** : rempli au premier téléchargement d'un firmware depuis The Firmware Hub (le paquet tel que publié,
      son empreinte, sa version) ;
    - **Sauvegardes** : rempli par le flashage : le paquet écrit sur le FM-1, et la sauvegarde de ses données quand
      le firmware le permet (projets, sons), datés, un dossier par firmware.
  - **Dans l'émulateur** : le choix du firmware demande quelle version charger : **la sauvegarde** (de préférence,
    quand il y en a une : on retrouve dans le téléphone ce qu'on avait sur le FM-1) ou **la version initiale** du
    Firmware Hub.
  - Faisabilité : la sauvegarde des données n'est pas une image de la mémoire de l'émulé (le `.fm1s` de l'app). Pour
    la charger, l'app démarre le firmware puis lui **rejoue la restauration par MIDI**, comme sur le vrai FM-1 (même
    protocole SysEx que `tools/fm1_backup.py`) ; l'émulé demande « RESTORE FROM USB ? » à l'écran, l'app répond SEL.
    Seulement pour les firmwares qui ont la sauvegarde ; pour les autres, la version initiale.
  - **Décidé : un seul dossier FM1 Mix** pour tout regrouper (voir « Le dossier FM1 Mix » plus bas).
  - Ces deux dossiers vont aussi dans l'export (même menu que les projets).
- **Fait en 0.29** (Réglages › FM-1 matériel › Gestionnaire de firmwares ; et la page d'un firmware › « Flasher sur
  le FM-1… ») :
  - le protocole sur le MIDI d'Android (`Fm1OtaAndroid.kt`) : les envois longs en morceaux de 1015 octets, le FM-1
    qui disparaît et revient pendant la mise à jour (cherché chaque seconde) ; le Bluetooth jamais ;
  - les pages du canevas : le choix (Hub, sauvegardes, un fichier ; « Sauvegarder d'abord », « m-bootloader après »),
    les vérifications avant le premier octet (empreinte du Hub, structure du paquet, modèle, firmware Open de M-VAVE,
    empreinte M-VAVE V15/V16, batterie ≥ 30 % ou en charge, MIDI réservé), la progression (pas d'Annuler pendant
    l'écriture), la fin (identité relue, rangé dans la banque, « Charger dans l'émulé », « Remettre sur le FM-1 »), la
    coupure et la reprise (le paquet gardé dans l'app jusqu'à la fin : reprise même après un redémarrage de l'app) ;
  - la sauvegarde et la restauration des données : `Fm1Backup.kt` (MPL-2.0, d'après `fm1_backup.py`) et les sons de
    LOOM-8 (section `LSMP`, comme la page Backup de LOOM-8) ; **testées de bout en bout** contre l'émulateur de l'app
    qui fait tourner le vrai LOOM-8 0.18.2-pm1, par son USB émulé (`tools/ota_check/run_emu.sh`) : sauvegarde,
    banque de sons écrite et relue, restauration refusée sur le FM-1 (HOME) puis acceptée (SEL), sauvegarde abîmée
    refusée, données remises à l'identique après des changements ;
  - la région de ressources après l'installation (`installRes`, d'après Melodee/fm1-base) : le fichier `-res.bin`
    à côté du paquet du Hub quand il existe ;
  - m-bootloader 2.4 livré dans l'app (sa licence MIT comprise), son empreinte vérifiée avant usage ;
  - la banque (`FirmwareBank.kt`) : `Documents/FM1 Mix/Firmwares/Initial`, `Sauvegardes/<firmware>/<date>/`
    (`firmware.fwsc`, `donnees.<ext>`, `fiche.json`), `Historique.json` ; l'export en `.zip` ;
  - l'émulé : choisir un firmware dont la banque a une sauvegarde demande laquelle charger (la sauvegarde, conseillée,
    ou la version initiale) ; avec la sauvegarde, l'émulé démarre avec son câble USB et l'app lui rejoue la
    restauration en répondant SEL ;
  - les projets déplacés une fois dans `Documents/FM1 Mix/Projets`.
- **Pas encore essayé sur un vrai FM-1**. Premier essai prudent : avec m-bootloader déjà là ou le script de secours
  sous la main, un firmware du Hub, « Sauvegarder d'abord » coché. Les sons de LOOM-8 sont sauvegardés (lus) mais
  pas encore renvoyés au vrai FM-1 : l'envoi de sons reste gelé (`SampleTargets.SEND_ENABLED`) tant qu'il n'a pas été
  essayé ; dans l'émulé, ils sont renvoyés.
- Reste : le choix du FM-1 quand il y en a plusieurs (avec l'état multi-sources) ; « Remettre le firmware M-VAVE »
  prend ton fichier V15/V16 (vérifié par son empreinte) mais ne le télécharge pas chez M-VAVE ; dans l'émulateur, une
  installation complète ne peut pas être rejouée (il ne redémarre pas dans le chargeur : l'étape 1 passe, testée).
- Licence, pour un humain : `Fm1Ota.kt` et `Fm1Backup.kt` restent sous MPL-2.0 dans l'app GPL-3.0, comme
  `LoomLink.kt`. Le paquet `fm1-uboot-2.4.fwsc` vient de la copie de fm1-base (dossier `web/m-bootloader`, avec la
  licence MIT de Julius Vitkauskas) : à confirmer qu'il ne contient que le code de m-bootloader.
- À regarder : FM-1 Hub (iPhone, Janos Debreczi) fait déjà installation vérifiée, reprise, retour au firmware
  M-VAVE, sauvegardes et historique. M-FLASH et FM-1 Swap : liens à retrouver.

## Le dossier FM1 Mix (décidé le 25 novembre)
- Un seul dossier pour tout, proposé :
  - `Documents/FM1 Mix/Projets` (avant 0.29 `Documents/Fm1mix projects` : déplacés une fois ; **fait en 0.29**) ;
  - `Documents/FM1 Mix/Firmwares/Initial` (téléchargés du Firmware Hub) et `…/Firmwares/Sauvegardes` (le flashage) ;
  - `Documents/FM1 Mix/Set-ups` (les set-ups multi-sources).
- Les **enregistrements et les découpes** restent dans `Music/FM1 Mix` (décidé : visibles des autres apps de musique).

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
- 0.22 : le dépôt étant privé, le lien ne marchait que pour toi. Le workflow peut publier dans un dépôt public à part
  (`RichAldana/FM1-Mix`, variable `PUBLIC_REPO` et secret `PUBLIC_REPO_TOKEN`), avec le code source joint à chaque
  version (GPL-3.0). Lien pour tous : `https://github.com/RichAldana/FM1-Mix/releases/latest/download/fm1mix.apk`.
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
