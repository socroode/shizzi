# Apollo 11 Houston v0.2.0 — alunissage manuel

Jeu Android autonome, sans Internet, avec pilotage réel (simplifié) d'Eagle.

## Piloter
- Entraînement : départ à 350 m ; Défi Apollo : 1 800 m.
- Maintenir **MOTEUR** pour ralentir la descente (puissance réglable de 10 à 100 %).
- Maintenir **GAUCHE** / **DROITE** pour corriger le déplacement horizontal.
- Atterrir au-dessus de la zone turquoise (±42 m), à une vitesse verticale ≤ 2,3 m/s et horizontale ≤ 1,4 m/s.
- Accélérer le temps ×1/×2/×4, pause, recommencer.
- Retour vers la chronologie Houston par **Historique Houston**.

## Précision
La gravité lunaire vaut 1,62 m/s² (NASA). L'accélération moteur et la consommation sont calibrées pour être jouables ; ce n'est pas un modèle certifié du moteur DPS ni une reproduction des données de télémétrie historiques. Le trajet Terre–Lune est ignoré et l'on commence directement en descente manuelle.

## Développer
Ouvrir `apollo11-sim` dans Android Studio ou lancer GitHub Actions `Build Apollo 11 Houston APK`. Le package `pf.shizzi.apollo11` reprend l'identifiant v0.1.0 ; `versionCode=2`, `versionName=0.2.0`. APK debug à tester avant toute diffusion.

Sources NASA : https://nssdc.gsfc.nasa.gov/planetary/factsheet/moonfact.html ; https://www.nasa.gov/history/apollo-11-mission-overview/ ; https://www.nasa.gov/wp-content/uploads/static/apollo50th/pdf/A11_MissionReport.pdf
