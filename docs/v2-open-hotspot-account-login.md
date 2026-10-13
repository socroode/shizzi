# Shizzi V2 — correction de la connexion sur un hotspot Wi-Fi ouvert

## Principe fonctionnel non négociable

Wi-Fi ouvert → portail libre `http://192.0.2.1/` → numéro de compte +
PIN → accès Internet uniquement si un voucher Internet est valide.

**Le compte identifie l'utilisateur**, pas sa MAC ni son téléphone. Un seul
point de connexion peut utiliser ce compte à la fois. Le solde Data, la
durée Illimité et le pass Média restent attachés au compte et sont portables.

## Correctif

La version précédente refusait la connexion numéro/PIN tant que la
correspondance `MAC SoftAP + bail DHCP` n'avait pas été constatée dans
`dumpsys`. Sur ColorOS, cette information peut rester indisponible
alors que le téléphone client a une adresse IP réelle, est connecté au
hotspot et peut accéder directement au serveur privé `:8093`.

Désormais :
1. Le serveur de PIN reste lié uniquement à l'IPv4 privée de la passerelle
   hotspot ; la requête TCP est vérifiée comme provenant du **sous-réseau
   réel** exposé par cette interface. On ne déduit jamais une IP à partir
   d'un en-tête HTTP, d'un compteur d'appareils ou d'une adresse MAC.
2. Shizzi vérifie le **numéro de compte + PIN** sur l'origine TCP physique.
   Aucune MAC ni entrée NAT n'est nécessaire pour créer la session.
3. En absence de données MAC fiables, la session temporaire est associée à
   l'IP originale du client. Elle n'utilise aucune fausse MAC.
4. Si Android fournit plus tard une liaison MAC↔IP vérifiée, la session
   conserve son compte et son numéro de génération pendant la mise à jour des
   métadonnées. Une vraie déconnexion Wi-Fi confirmée sur cette MAC révoque
   la session et oblige à ressaisir le PIN.
5. Le transfert d'un compte vers une nouvelle connexion révoque
   l'ancienne, y compris ses TCP/UDP existants. Les vouchers demeurent au
   même compte, sans rattachement permanent au matériel.
6. L'interface Appareils et sessions lit désormais les sessions du registre
   V2 plutôt que le cache du portail 0.4.x. Une connexion sans MAC connue
   montre son compte et son IP, sans inventer de matériel.
7. Les messages distinguent PIN incorrect et connexion dont le lien réseau
   a changé pendant la validation.

## Limite de sécurité à résoudre avant exploitation commerciale

Un client d'un hotspot **sans chiffrement Wi-Fi** peut être exposé à
l'écoute et au détournement de trafic local : un formulaire HTTP ne chiffre
pas son PIN. Le code est un correctif de connectivité destiné aux **tests**
et non un certificat de sûreté du réseau ouvert.

Lorsque le constructeur Android ne fournit AUCUN événement fiable de
déconnexion, **l'adresse IP temporaire ne prouve pas à elle seule que le
même appareil est encore connecté**. Un départ/réassociation silencieux
avec réutilisation de la même IP peut laisser une session active. Ne
jamais prétendre que cette garantie est acquise. Pour résoudre ce point,
le routeur devra obtenir des événements SoftAP/départ fiables ou un
mécanisme de preuve de présence adapté au hotspot ouvert, en respectant
l'absence de délai de déconnexion pendant une simple veille.

## Tests de cette étape

- Accueil sans compte et Internet interdit avant PIN.
- Connexion avec compte + PIN et IPv4 réelle sans MAC/lease connue.
- PIN incorrect, IP partagée 192.0.2.2, client hors sous-réseau.
- Portabilité sur deux appareils et une session active par compte.
- Association MAC facultative ultérieure sans interrompre le compte.
- Départ Wi-Fi observé, réassociation, nouvelle authentification requise.
- Tableau administrateur montrant la session V2 réelle, IP et droits.
- Tests Go avec détecteur de races, puis compilation Android.
- Test réel nécessaire sur l'OPPO Reno11 (`192.168.81.199`) avant de
  conclure que le blocage OEM a disparu.

Branche : `rebuild/shizzi-v2`.
Ne pas publier de version de production ni remplacer la v0.4.3.5.3 sans
validation physique ; préserver certificat privé et données existantes.
