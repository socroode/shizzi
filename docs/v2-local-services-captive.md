# Shizzi V2 — étape 6 : services locaux et détection captive HTTP

## Changements implémentés

- `http://192.0.2.1/` reste public **sans compte, voucher, identification
  MAC ni correspondance NAT**, avec formulaire numéro de compte + PIN.
- `http://192.0.2.1/shizzi-plus.apk` et
  `http://192.0.2.1/shizzi-admin.apk` transmettent les **véritables
  fichiers APK** depuis le serveur de distribution local sur
  `127.0.0.1:8091`. HTTP GET/HEAD et plages Range sont préservés ;
  pas de réponse HTML factice de quelques kilo-octets.
- La page d'accueil propose des liens vers les services Média et chat
  accessibles sur l'adresse **physique privée** de la passerelle hotspot,
  port 8093. Ce second port est nécessaire pour voir l'IP originale du
  navigateur, avant la traduction d'adresses NAT du TUN.
- Le routeur assure désormais le proxy **local** :
  - `/media/` → `127.0.0.1:8088` : une session de compte valide et un
    voucher Média actif sont nécessaires. GET/HEAD/Range permettent le
    streaming et les accès aux dossiers/miniatures/séries.
  - `/chat/` → `127.0.0.1:8090` : session de compte valide, aucun
    voucher Internet requis. Les GET/HEAD/POST sont soumis aux contrôles
    de session. Les POST hors origine et les corps trop volumineux sont
    rejetés. Les appels et la messagerie restent dépendants de leur
    serveur local et des permissions Android.
  - `/shizzi-plus.apk`, `/shizzi-admin.apk` : téléchargements locaux
    accessibles même sans compte.
- Les en-têtes `X-Shizzi-Media-Account` et `X-Shizzi-Chat-Account`
  sont *fabriqués exclusivement par le serveur V2*, après vérification
  de la session numéro de compte/PIN ; les en-têtes homonymes saisis par
  un navigateur sont ignorés.
- La session est réévaluée **pendant** les transferts, afin qu'une vraie
  déconnexion Wi-Fi, un changement d'appareil ou un voucher Média expiré
  interrompt également un streaming en cours.
- Pour les demandes HTTP captive de domaines connus (Android, Apple,
  Microsoft et Firefox), quand le client n'a pas d'autorisation Internet,
  le moteur renvoie une redirection `302` vers le portail public.
  Les requêtes ordinaires non autorisées ne sont **jamais** transférées
  sur Internet par ce raccourci.

## Tests automatisés de cette étape

- Invitation à se connecter sans voucher, accès Média fermé avant voucher.
- Navigation Média avec Range, 206 et copie vidéo par le backend local.
- Messagerie même sans Internet ; pas d'usurpation de numéro de compte
  par en-tête navigateur ou POST hors origine.
- Révocation des accès après déconnexion/takeover du compte.
- Vrai téléchargement APK de 128 KiB et de sa plage 100–199.
- Détection HTTP captive autorisée seulement pour les sondes
  de connectivité connues, sans ouvrir la navigation générale.

## Limites AVANT toute APK de production

- Les serveurs loopback Média et Messagerie doivent être démarrés sur le
  routeur Android. Un test unitaire de proxy avec backend simulé ne suffit
  pas pour prouver les films, messages ou appels réels.
- Les popup captifs automatiques dépendent du système client : certains
  emploient HTTPS ou un schéma spécifique. Il faut tester Oppo Reno,
  Samsung A07 et tablette. **Aucune interception TLS n'est tentée.**
- Un client ne peut être authentifié si Android ne prouve pas la connexion
  hotspot physique (liste SoftAP + bail DHCP concordant), ce qui est
  un point OEM à vérifier avec le matériel.
- Les PIN restent transmis en HTTP local. Activer WPA2/WPA3, renforcer
  l'anti-bruteforce et protéger le transport des identifiants avant vente.
- Les limites de débits/quotas V2, les renouvellements de compte,
  la coupure des sessions réellement déconnectées et le portail
  doivent faire l'objet de tests physiques simultanés.
- Signer la future APK release avec la clé **identique** à la 0.4.3.5.3.
  Rien n'est publié ni déployé automatiquement.

Branche expérimentale : `rebuild/shizzi-v2`.
