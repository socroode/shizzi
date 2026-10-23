# ShizziCam 0.4.3.5.18-test — validation matérielle

Base : feat/shizzicam-0.4.3.5.17-stream-audio-monitor, commit f4549ca970ce3e8cb827880e80b1ae223d113b99.

## Installation et distribution hors ligne

Installer le HOTSPOT signé avec le certificat permanent existant. Désactiver les données mobiles et tout accès Internet. Depuis un client Wi-Fi, ouvrir 192.0.2.1 et télécharger Shizzi+Cam, ShizziCam Visionneuse et ShizziCam Débit. Comparer leurs SHA-256 et leurs signatures avec les annexes livrées. Installer chacune sur Android 11 puis sur les appareils récents disponibles. Les téléchargements Shizzi Admin et Shizzi Comptes doivent rester présents.

## Comptes et fonctions héritées

Créer, modifier, désactiver et supprimer des comptes depuis l’administration. Modifier le PIN. Connecter un compte sans voucher Internet et vérifier la surveillance. Déplacer le compte sur un autre téléphone : l’ancienne session doit perdre l’accès média et la nouvelle doit fonctionner sans quitter le Wi-Fi. Vérifier séparément les offres, vouchers, Internet, messagerie et bibliothèque hérités de la branche.

## Caméras et mosaïque

Tester successivement 1, 2, 4, 6 et 8 caméras. Vérifier la capacité d’accueil du routeur : huit caméras et une Visionneuse représentent neuf clients Wi-Fi, dix avec Débit sur un appareil distinct. Tester chaque qualité, la caméra avant/arrière, la rotation automatique, le zoom et la torche. Les dimensions réelles peuvent être alignées par le codec ; les éventuelles bordures doivent préserver les proportions. Débrancher une caméra et vérifier que les autres directs continuent.

## Audio et commandes

Vérifier physiquement la présence de son dans les deux sens, les permissions et les volumes Android, le microphone coupé, l’écoute coupée et le retour après interruption. Vérifier les états gris/orange/vert/rouge et l’absence de confirmation de lecture provenant d’une conversation antérieure. Déclencher et arrêter la sirène ; vérifier la limite de dix secondes et la protection contre les répétitions.

## Latence et synchronisation

Filmer simultanément un stimulus physique visible par la caméra et l’écran de la Visionneuse avec une caméra externe à haute fréquence. Le nombre d’images entre le stimulus et son apparition mesure la latence sans dépendre des horloges des téléphones. Pour la synchronisation, utiliser un stimulus lumineux et sonore commun, enregistrer l’affichage et le son de la Visionneuse avec un enregistreur externe commun, puis comparer les deux instants. Rapporter l’incertitude de mesure. Cibles : au plus 300 ms de latence nominale et au plus 100 ms de décalage audio/vidéo. Les statistiques du logiciel sont des estimations et ne remplacent pas ces essais.

## Endurance

Réaliser des essais matériels de 10 minutes, 1 heure et 24 heures en 2,4 GHz et en 5 GHz selon le routeur. Consigner température, mémoire, batterie, FPS réellement rendus, rejets, interruptions et récupération après changements de réseau. Distinguer ces résultats de l’endurance virtuelle à charges synthétiques.

Aucune validation de fluidité, de son audible ou de fonctionnement H24 n’est acquise par la seule compilation.
