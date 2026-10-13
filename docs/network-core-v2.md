# Shizzi V2 — noyau réseau (étape 1)

Base immuable de départ : commit 4f8f049a (fork historique 0.4.0-rc.3).
Branche expérimentale : rebuild/shizzi-v2.
Ce document décrit **du code ajouté**, pas une APK prête à installer.

## Invariants non négociables

1. Une station Wi-Fi est une connexion **éphémère** : le compte n'est jamais attaché définitivement à son IP ou son adresse MAC.
2. Le sommeil, l'absence de trafic ou une erreur dumpsys **ne ferment pas** la session.
3. Un **départ Wi-Fi confirmé** invalide immédiatement la session; une reconnexion Wi-Fi ultérieure nécessite un nouvel identifiant/mot de passe.
4. La validation du compte est une opération **explicite, après vérification du mot de passe**. La connaissance d'une IP/MAC n'authentifie jamais un compte.
5. Si le même compte est validé sur une autre station, l'ancienne session du compte est invalidée sans coupure du Wi-Fi de l'ancien téléphone.
6. Une adresse DHCP réattribuée à une autre station n'hérite jamais d'un droit Internet.
7. Le portail **local** est indépendant des droits à Internet et à la médiathèque. Internet et Média ont des droits distincts; des vouchers épuisés ne déconnectent pas du Wi-Fi.
8. Les comptes et soldes de vouchers sont stockés **hors de ce noyau**, pas dans les baux DHCP ni les tuples NAT.
9. Chaque flux Internet NATé doit être rattaché à une station par correspondance **exacte et récente** (protocole, IP et port traduits, IP et port destination). Aucun raccourci « client unique » ou « port seul » n'accorde de droits.
10. Aucune ancienne autorisation NAT/HTTPS n'est réutilisée après un changement de propriétaire de l'IP, un départ Wi-Fi confirmé ou un changement de session.

## Modules ajoutés

- `datapath/core/registry.go` : stations Wi-Fi, adresses IP liées à une preuve Android, sessions de compte et droits Internet/Média, sans interface Android.
- `datapath/core/attribution.go` : analyse isolée de snapshots NAT IPv4, rejet des conflits, péremption et invalidation de snapshots, puis rapprochement avec la station active.
- `datapath/core/core_test.go` : non-régression du cycle de vie et du cloisonnement multi-clients.

Le code se compile comme sous-package de `dev.shizzi/datapath`. Les tests n'utilisent que la bibliothèque standard Go :

```bash
cd datapath/core
GO111MODULE=off go test -race -count=1 ./...
GO111MODULE=off go vet ./...
```

## Étapes d'intégration Android encore nécessaires

**Ne pas publier d'APK ni prétendre que l'identification est corrigée tant que les points suivants ne sont pas validés.**

1. Récupérer les événements d'association/désassociation de stations Wi-Fi *autoritaires* auprès du service SoftAP Android/UID shell. Un simple compteur de clients ou un bail DHCP manquant ne suffit pas pour déconnecter une session.
2. Corréler une station MAC associée à son IP DHCP à partir de données authentifiées du point d'accès, sans supposer d'adresses IP fixes. Les API et permissions changent selon les appareils/OEM : tester sur Reno9, Reno11, Samsung A07 et tablette.
3. Servir le portail local depuis le côté **physique** du hotspot pour voir l'IP source avant NAT, avec filtrage des interfaces. L'adresse publique de portail `192.0.2.1` devra rester accessible via une passerelle contrôlée; ne pas promettre qu'elle fournit une source pré-NAT.
4. Créer un pont explicite vers le validateur de mots de passe et le registre de comptes. `GrantVerifiedLogin` est une **API de confiance** : aucune requête utilisateur ne doit l'appeler directement.
5. Publier en arrière-plan des snapshots NAT de l'Android réel vers `NATIndex.Replace`. Toute erreur de capture doit appeler `Invalidate`. Les flux non résolus restent refusés sans déclencher `dumpsys` dans les handlers réseau.
6. Raccorder `Gate.CheckTranslated` à chaque nouveau flux du TUN, et vérifier la **révocation des flux déjà établis** dès perte Wi-Fi, changement de compte ou expiration voucher. Le fait de refuser seulement les nouveaux flux ne suffit pas.
7. Restaurer les vouchers Data/Illimité/Média et les fonctionnalités Média de 0.4.3.5.3 via des adaptateurs séparés, sans recopier les identifications défaillantes.
8. Valider un scénario à 1, 2, 3, 6/8 puis 10 appareils, plusieurs navigateurs, Wi-Fi dormant, déconnexion/reconnexion et collision d'IP, d'abord en tests virtuels puis sur Android réel.

## Signature APK future

Conserver le package Android `dev.shizzi`, utiliser la **même clé privée de signature** que 0.4.3.5.3, augmenter `versionCode` au-delà de 31 et comparer le SHA-256 du certificat de l'APK release à :

`FA:DF:C5:E8:7F:A6:03:51:F7:DC:B7:5D:34:F1:AB:91:11:08:24:33:3C:D1:09:7A:38:B3:C7:03:F7:AF:3D:B7`.

Ne jamais inclure les secrets de signature dans le dépôt ni publier une APK avec une clé de debug.
