# Shizzi V2 — étape 4 : chemin physique hotspot → numéro/PIN

Branche : rebuild/shizzi-v2.
Phase expérimentale, **pas d'APK de production**.

## Problème initial

Dans Android tethering, plusieurs clients peuvent partager la source 192.0.2.2
à l'entrée du TUN. Le portail public 192.0.2.1 doit s'ouvrir sans attribution
NAT. Mais une requête de connexion numéro + PIN venant de 192.0.2.2 ne peut
pas accorder les droits à un client précis.

## Solution développée

1. Le TUN gVisor continue de servir GET 192.0.2.1:80 sans identification.
2. Un observateur côté service Shizuku/shell recherche le véritable
   **tethered Wi-Fi interface** en état TetheredState, puis sa propre IPv4
   privée (par exemple 192.168.247.1). Il ne choisit ni l'IP amont Starlink
   ni une adresse inventée ou fixe.
3. Le nouveau serveur PIN Go écoute UNIQUEMENT sur cette IPv4 locale, port
   8093, et refuse 0.0.0.0, les adresses WAN, les en-têtes Host incorrects,
   les demandes de login depuis une IP NAT fictive et les mauvais PIN.
4. L'accueil 192.0.2.1 adapte l'attribut action du formulaire vers
   http://<passerelle-hotspot>:8093/login lorsque le serveur est actif.
   La politique CSP autorise explicitement cet unique destinataire.
   Lorsque la passerelle n'est pas détectée, l'accueil reste accessible
   mais le PIN NE PEUT PAS accorder Internet.
5. Le serveur vérifie les PIN avec les mêmes hashes et comptes que
   v0.4.3.5.3, via v2AccountBridge. L'IP du peer TCP est l'IPv4 physique,
   et les entitlements Data/Illimité/Média sont lus depuis le registre V2.
6. L'observateur lit la **liste courante SoftAP** et les baux du service
   tethering. Il n'accorde une association qu'aux MAC présents dans la
   liste Wi-Fi actuelle ET assortis d'une IP pour cette même MAC dans
   les informations DHCP. L'ancienne table NAT ne suffit jamais.
7. Une absence dans une nouvelle liste SoftAP complète retire la session,
   tout en conservant le compte/voucher. Écran éteint, trafic inactif ou
   erreur de dumpsys ne déclenchent aucune déconnexion.
8. Tous les composants sont rattachés au cycle de vie du routeur ;
   le serveur d'authentification s'arrête quand le routeur s'arrête.

## Compatibilité : limites explicites

Les chaînes dumpsys varient selon les fabricants et versions Android ;
seules des formes présentes et contrôlables sont acceptées, par exemple :
- Wi-Fi : getConnectedClientList(): [WifiClient{mMacAddress=..., ...}]
- Tethering : TetheredClient {hwAddr ..., addresses
  [AddressInfo {/192.168.x.y/24}], tetheringType 0}
- Interface : ap0 - TetheredState - ...

**Important** : getConnectedClientList().size(): 2 signifie seulement
« deux clients ». Cette donnée ne permet pas de savoir lesquels. Un bail
d'un client récemment déconnecté peut rester dans dumpsys. Ni ce compteur
ni un bail seul ne valident l'appareil. Si l'OEM ne fournit pas ces preuves
par dumpsys, Shizzi V2 reste en mode identité non disponible et il faudra
brancher les callbacks système privilégiés Android
(WifiManager.SoftApCallback + TetheringEventCallback) sur cet appareil.
Android documente ces deux sources complémentaires.

La présence est vérifiée par interrogation environ toutes les 2 secondes ;
le délai effectif peut être plus long si dumpsys prend du temps.
La séparation du HTTP captif entre deux adresses locales doit être testée
sur les navigateurs Samsung/Oppo et le navigateur captif Windows. Cette
étape n'a pas encore été validée sur matériel physique.

## Sécurité et mise en service

Le formulaire utilise HTTP parce que les hotspots n'ont pas de certificat
TLS public pour l'IP locale. Le Wi-Fi doit être protégé par une clé
WPA2/WPA3, et une protection contre l'interception et les tentatives de PIN
doit être revue avant exploitation commerciale.

Le **verrou v2UIRecoveryOnly=true reste activé** : aucun accès Internet
n'est autorisé par le moteur legacy pendant la transition. L'ouverture du
portail et un PIN correctement vérifié ne réactivent donc pas encore le
forwarding Internet. Le futur moteur V2 devra :
- appliquer l'identité vérifiée à chaque nouveau flux,
- refuser les flux inconnus,
- fermer les flux déjà ouverts au départ Wi-Fi, au transfert du compte et à
  l'expiration des vouchers,
- limiter et compter correctement les quotas de chaque compte,
- préserver les services locaux Media et chat indépendamment d'Internet.

**Aucune APK signée ou mise à jour automatique n'est publiée.**
La signature finale doit être la même que pour 0.4.3.5.3.
