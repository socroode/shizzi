# Shizzi V2 — restauration de l'interface 0.4.3.5.3

## Provenance

- Branche cible : `rebuild/shizzi-v2`.
- Source contrôlée : `test/0.4.3.5.3` au commit `3cc01d2b40b8094ca0e617db3f8ec1f7ba5f77e7`.
- Base du fork initial : `4f8f049a2090db25fa7346c1581d9d4b59351d2a`.
- Noyau V2 : `datapath/core/` conservé ; règles d'identification/validation à compléter.
- Identifiant : `dev.shizzi` ; version provisoire `0.5.0-alpha.1`, `versionCode=32`.

## Écrans Android restaurés

**Routeur** : accueil/connexion, navigation, paramètres, édition des comptes,
vouchers Data et Illimité, vouchers Média, appareils et sessions, diagnostics,
configuration du portail, serveur Média, dossiers, autorisations, miniatures,
séries/saisons, messagerie et appels.

**Shizzi+** : interface client, consultation des comptes, accès à la médiathèque
et messagerie du routeur, composants WebView.

**Shizzi Admin** : gestion distante des comptes, vouchers, terminaux, médiathèque
et diagnostics selon l'interface 0.4.3.5.3.

**Portail HTTP** : habillage sombre turquoise proche du portail 0.4.3.5.3,
formulaire numéro de compte + PIN accessible sans NAT depuis `192.0.2.1` ;
cartes Média/Messagerie/vouchers. Les routes locales de téléchargement Shizzi+,
Admin, Média et Chat restent À RACCORDER à ce nouveau portail V2. La présence
des écrans Kotlin ne prouve pas l'existence des services accessibles côté client.

## Important : réseau en restauration

Les composants Kotlin et leur API de compatibilité Go viennent de 0.4.3.5.3
pour retrouver les écrans et compiler sans une réécriture immédiate de chaque
module. Leurs anciennes règles NAT ne sont PAS le nouveau cœur approuvé.

Le **garde-fou temporaire** `v2UIRecoveryOnly=true` refuse tous les flux TCP
externes et UDP ; seul le portail local `192.0.2.1:80` est intercepté.
La page d'accueil apparaît sans compte ni NAT. Le POST /login sur l'adresse
TUN partagée reste bloqué pour éviter de connecter un mauvais appareil.
AUCUN utilisateur ne reçoit Internet à partir de cette couche de compatibilité.

Il faudra ensuite brancher le validateur de PIN/compte sur l'IP originale du
client AVANT NAT et reconstruire un routage Internet fail-closed basé sur le
nouveau `datapath/core`, avec retrait des droits dès une vraie déconnexion
Wi-Fi et préservation des droits pendant la veille.

**Ne pas distribuer ni installer la branche comme mise à jour de production.**
La compilation debug n'utilise pas le certificat permanent. Pour une version
release il faudra la clé privée originale de 0.4.3.5.3, la même applicationId
et la vérification SHA-256 de certificat :
`FA:DF:C5:E8:7F:A6:03:51:F7:DC:B7:5D:34:F1:AB:91:11:08:24:33:3C:D1:09:7A:38:B3:C7:03:F7:AF:3D:B7`.

## Tests de cette étape

- Go : noyau V2, page publique et protections d'accès.
- Kotlin : tests unitaires routeur + Shizzi+ + Shizzi Admin.
- Compilation debug complète des trois applications, sans publication.

Le test sur véritables appareils Android et le test de 6 à 10 clients seront
requis AVANT toute annonce d'un cœur réseau stable.
