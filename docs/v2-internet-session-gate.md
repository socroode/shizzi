# Shizzi V2 — étape 5 : autorisation Internet par compte/PIN et session

## Cible

Internet doit dépendre du numéro de compte et du PIN vérifiés ; ni la MAC
ni l'IP ne constituent l'identifiant du compte. MAC et adresse physique sont
seulement des preuves de l'existence de la connexion réseau particulière à
laquelle ce compte authentifié a été associé.

## Modifications

- `core.Registry.InternetLease` produit une autorisation éphémère
  (IP physique, compte, génération de session). Aucun identifiant matériel
  n'est conservé dans le compte.
- `core.Registry.LeaseStillValid` vérifie que le même compte est toujours
  authentifié sur la même connexion et que le voucher est encore actif.
  Le numéro de génération change lors d'un nouveau login, logout, transfert
  vers un autre appareil, départ Wi-Fi ou changement d'adresse.
- `v2AccountBridge.openV2Flow` accepte exclusivement une **correspondance
  exacte et récente** du tuple TCP/UDP NAT vers l'IP physique issue du
  point d'accès. Un tuple absent, ambigu, IPv6 non pris en charge, ou
  ancien ne déclenche **aucun accès Internet**.
- Une tâche de fond indépendante prélève des instantanés bornés du NAT
  Android. Les erreurs suppriment les correspondances au lieu de deviner.
  Aucun `dumpsys` n'est fait par le handler de paquet.
- Tous les flux TCP/UDP Internet empruntent le relais V2, jamais
  `resolveFlowClient`, `flowAllowed` ou l'ancien portail comme source
  de droits. Aucune connexion Internet n'est ouverte sans session et voucher.
- À chaque bloc TCP ou datagramme UDP, vérification de la génération de
  session et du forfait. Le relais termine sur révocation/expiration.
- Débits plafonnés par compte et partagés entre ses connexions ;
  volume Data réservé avant envoi et imputé à l'historique de consommation
  Android (Data et Illimité restent distincts). Un abonnement Illimité
  actif ne consomme pas le volume Data en réserve.
- UDP refuse un datagramme si le dernier quota ne suffit pas pour un paquet
  complet ; l'excédent potentiellement réservé est compté prudemment.
- Les destinations locales privées ne sont jamais confondues avec le
  trafic Internet et ne peuvent pas être traversées par le relais WAN.

## Contraintes encore ouvertes

1. **Démonstration sur vrai téléphone** impérative : on ne sait pas encore
   si chaque constructeur publie les tuples NAT complets assez tôt.
   S'il ne les publie pas, les flux restent bloqués plutôt que d'être
   attribués au mauvais compte. Tester Reno9, Reno11, A07 et tablette.
2. Les chemins Média/Chat/recharge/download doivent être reliés au
   portail local dans une intervention dédiée.
3. La détection et l'affichage automatiques du portail captif via DNS/probes
   OEM restent à compléter. La page HTTP directe
   `http://192.0.2.1` fonctionne côté code, sans garantie de popup Android.
4. L'identité SoftAP reste soumise aux APIs/OEM observables ; un compteur
   clients Wi-Fi n'est pas un identifiant et ne suffit jamais.
5. Des écritures réseau échouées peuvent entraîner un décompte conservateur
   (débit réservé mais pas reçu). Revoir l'audit de comptabilité et la
   gestion du blocage sur un vrai réseau avant diffusion.
6. Réviser la sécurité des PIN sur HTTP local (WPA2/WPA3 requis au
   minimum), la limitation anti-bruteforce et la signature APK.
7. Pas de diffusion d'APK de production avant signature permanente,
   test virtuel multi-clients complet et tests Android réels.

## Vérifications automatisées

Tests de non-contournement de flux : exact tuple IP:port/protocole/destination,
conflit NAT, absence de session, transfert de compte, nouvel appareil,
reconnexion Wi-Fi, nouvelle session sur le même Wi-Fi, expiration/épuisement
Data, préservation Média, débits partagés et cumul up/down.

Branche `rebuild/shizzi-v2` uniquement. Package `dev.shizzi`,
version de développement `0.5.0-alpha.1`.

## Révision de sûreté

- Un identifiant de génération **global et monotone** empêche qu'une session
  TCP morte redevienne active après déconnexion Wi-Fi, reconnexion avec la
  même MAC/IP et nouvelle authentification sur le même compte.
- UDP ne décompte désormais aucun octet pour un datagramme refusé faute de
  volume restant.
- Les nouveaux flux peuvent attendre jusqu'à **2,5 secondes maximum**
  l'apparition d'un tuple NAT complet dans le snapshot asynchrone. Cette
  attente ne fait **aucun** appel dumpsys dans le handler et n'autorise pas
  les rapprochements approximatifs.
- Test virtuel de huit comptes et huit stations simultanés sur la même IP
  partagée de TUN, avec consommation distincte et suspension ciblée. Ce test
  ne remplace PAS une simulation Android radio + navigateur ni des appareils
  physiques.
