# Shizzi V2 — deuxième étape : comptes/PIN/vouchers reliés au noyau

## Objet

Les interfaces routeur, Shizzi+ et Admin sont reprises de v0.4.3.5.3.
Cette étape **relie les données de compte originales** au nouveau noyau V2,
sans réactiver le routage Internet hérité.

## Ce qui est désormais codé

- `datapath/v2_account_bridge.go` : adaptateur isolé entre
  `TrafficManager.portalAccounts` (compte durable poussé depuis Android) et
  `core.Registry` (sessions Wi-Fi V2).
- Le validateur `VerifyPIN` compare le PIN saisi au véritable hash salé
  provenant de 0.4.3.5.3 ; les comptes suspendus et les PIN incorrects échouent.
- L'adaptateur transmet les droits Data, Illimité et Média :
  durée restante, Data consommée et solde, prise en compte des marqueurs
  de consommation du ledger d'origine.
- En cas de suppression, suspension ou modification de PIN, la session est
  révoquée ; recharge ou expiration du voucher ne ferme PAS la session locale.
- Sur un deuxième appareil, une identification réussie retire l'ancien accès
  du compte, sans couper le Wi-Fi.
- Le registre ne conserve que des associations éphémères IP/MAC et doit recevoir
  les événements Wi-Fi **autoritaires**, sans adresses IP/MAC fixes.
- `Session.SetPortalConfig` synchronise désormais les droits dans le registre
  V2 après chaque mise à jour de configuration envoyée par Android.

## Ce qui n'est PAS terminé

1. Les événements SoftAP réels (MAC, IP DHCP, départ Wi-Fi) ne sont pas encore
   câblés depuis Android au registre ; `Associated` et `Departed` sont des
   interfaces internes, pas une détection automatique.
2. L'accueil `192.0.2.1` reste visible depuis le TUN sans identification,
   mais le navigateur y est vu à travers l'IP NAT partagée `192.0.2.2`.
   Les soumissions de PIN depuis cette origine sont donc refusées : il faut
   raccorder un récepteur de connexion **sur l'interface physique du hotspot**
   capable d'observer l'adresse IP originale, puis le relier au
   `core.PortalEndpoint` et au validateur PIN.
3. Les accès Internet sont **volontairement refusés dans le forwarder**
   (`v2UIRecoveryOnly=true`). Cela évite de réintroduire les blocages et
   les erreurs de compte de l'ancien chemin NAT. Le futur chemin par flux
   devra valider les droits de la station, gérer les connexions déjà
   ouvertes et leurs quotas, puis être éprouvé sur plusieurs appareils.
4. Les routes Média/Chat/vouchers côté navigateur V2 doivent encore être
   attachées aux services locaux, en conservant des droits séparés.
5. Aucun test physique Android ni test à 6/10 appareils n'a été validé.

## Vérifications de cette étape

Tests Go ajoutés : PIN réel et faux, prise en compte des balances de la
version 0.4.3.5.3, épuisement Data conservant Média et portail, bascule
entre deux appareils, Wi-Fi perdu puis retrouvé, changement de PIN,
suspension de compte et impossibilité d'authentifier l'IP NAT partagée.

La signature APK de production devra toujours correspondre à
`FA:DF:C5:E8:7F:A6:03:51:F7:DC:B7:5D:34:F1:AB:91:11:08:24:33:3C:D1:09:7A:38:B3:C7:03:F7:AF:3D:B7`.
Aucun APK, commit release ou tag de production n'est demandé pour cette étape.
