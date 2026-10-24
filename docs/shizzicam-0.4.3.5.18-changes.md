# ShizziCam 0.4.3.5.18.2-test — modifications

Base : `feat/shizzicam-0.4.3.5.17-stream-audio-monitor`, commit `f4549ca970ce3e8cb827880e80b1ae223d113b99`.

- Relais média H.264 et Opus intégralement local via le HOTSPOT en HTTPS avec vérification du certificat. Les annexes v3 ne créent aucune connexion média caméra/Visionneuse directe.
- Ajout 144p/240p aux trois qualités existantes et configuration des cinq débits vidéo demandés ; adaptation aux alignements des codecs sans étirer l’image.
- Capture et encodage découplés, cadence corrigée sur les caméras à 30 FPS, files bornées, attente d’une nouvelle image clé après perte et reprise des connexions.
- Audio Opus 128 kbps, 48 kHz mono, transport continu dans les deux sens et transmission des paramètres réels du codec. Lecture sur le haut-parleur Android avec contrôle explicite du volume nul.
- Horodatages communs estimés par échanges avec l’horloge du routeur ; latence du rendu et incertitude d’horloge distinguées des débits de transport.
- Rotation automatique conservée et rotation manuelle disponible ; caméras avant/arrière, torche et zoom conservés selon le matériel.
- Mosaïque 1/2/4/6/8, barre interphone fixe, états de transmission distincts de la confirmation matérielle de lecture. Un ancien accusé ne confirme pas une nouvelle conversation.
- Sirène MP3 locale 128 kbps, durée du fichier inférieure à dix secondes, arrêt manuel, confirmation et protection contre les répétitions.
- Statistiques séparées pour capture, encodage, relais et mises à jour de la vignette Visionneuse ; informations absentes ou périmées indiquées comme indisponibles. Les rejets applicatifs ne sont pas présentés comme une mesure des pertes radio.
- Mode Auto fondé sur le nombre de caméras, les retours de rendu/latence, la charge observée et les informations thermiques, avec temporisation des remontées de qualité. La capacité radio disponible n’est pas inventée à partir du trafic consommé.
- Trois annexes embarquées, contrôles ZIP/APK, versions, tailles, SHA-256 et signatures à la construction. Les téléchargements Shizzi Admin et Shizzi Comptes sont conservés.
- L’ancien composant WebRTC pair à pair inutilisé a été retiré des annexes ; les fonctionnalités héritées de compte, vouchers, Internet, messagerie et médias n’ont pas été supprimées.

Les essais sur appareils réels restent nécessaires : son audible, compatibilité des codecs par téléphone, neuf à dix clients Wi-Fi, tenue thermique, 2,4/5 GHz, latence nominale au plus 300 ms, synchronisation au plus 100 ms et surveillance de 1 heure/24 heures. Une construction réussie et une endurance virtuelle ne constituent pas ces validations.

Les APK de livraison doivent porter le certificat permanent attendu, dont l’empreinte SHA-256 est `FADFC5E87FA60351F7DCB75D34F1AB91110824333CD1097A38B3C703F7AF3DB7`. Le rapport JSON joint indique les versions et empreintes des fichiers réellement construits. Les variantes debug locales ne sont pas des mises à jour signées avec ce certificat.

## Correctif interphone 0.4.3.5.18.1-test

- Visionneuse 0.3.4-relay18-test : un appui sur « Parler » maintient la capture jusqu’à « Couper mon micro ». La permission Android accordée reprend la demande en cours, sauf si elle a été annulée.
- Bouton et texte d’état gris/orange/vert/rouge. L’état rouge persiste après un échec ; l’état vert exige une confirmation récente du relais et ne signifie pas à lui seul que le son est audible.
- Arrêt, changement de caméra et nouvelle conversation protégés contre les retours tardifs d’un ancien traitement. Les commandes du micro caméra sont explicites et distinctes de celles du micro Visionneuse.
- Les deux boutons de chaque rangée de la barre interphone partagent la largeur disponible.
- Le routeur publie la date du dernier paquet interphone montant accepté. La Visionneuse conserve un mode de confirmation compatible avec les routeurs v3 précédents, sans réutiliser le trafic d’une conversation antérieure.
- HOTSPOT : code Android 49 ; Visionneuse : code Android 9. Les autres annexes conservent leurs versions. La Visionneuse corrigée est intégrée au téléchargement local du HOTSPOT.
- Des tests logiciels couvrent les transitions, permissions, confirmations, temporisations et changements de conversation ; le son et les couleurs sur appareils réels restent à valider.

## Correctif de session et diagnostic interphone 0.4.3.5.18.2-test

- Visionneuse 0.3.5-relay18-test : les commandes et l’interphone partagent une seule création de session par caméra, même lors de demandes simultanées. Deux requêtes « watch » ne peuvent plus s’invalider au démarrage.
- Un délai réseau ou une erreur serveur transitoire lors du suivi des commandes conserve la session utilisée par le flux interphone. Un refus HTTP 403/404 invalide uniquement la session concernée ; un retour tardif ne supprime pas sa remplaçante.
- L’échec de confirmation distingue l’absence de données du microphone, l’absence de paquets Opus, l’envoi bloqué et l’impossibilité d’interroger le relais. Le comptage de paquets envoyés ne remplace pas une confirmation de réception par le relais.
- HOTSPOT : code Android 50 ; Visionneuse : code Android 10. Les trois annexes restent embarquées, avec Shizzi Admin et Shizzi Comptes conservés. Pas de modification du codec ni de l’application caméra dans ce correctif.
- Ces défauts logiciels sont identifiés dans le chemin correspondant au bouton rouge photographié. Leur correction ne constitue pas encore la preuve que le silence observé sur les téléphones de l’utilisateur est résolu. Une confirmation matérielle reste nécessaire.
