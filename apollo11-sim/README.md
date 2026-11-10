# APOLLO 11 · Houston — v0.1.0

Simulateur **pédagogique**, autonome et hors ligne, inspiré de la chronologie réelle de la mission Apollo 11. L'horloge MET et les événements sont référencés sur la documentation historique NASA, mais la représentation du mouvement, les vitesses entre jalons et le budget de carburant sont **des modèles illustratifs**, pas des données de navigation ni de télémétrie authentifiées.

## Utilisation
- Ouvrir `app/src/main/assets/index.html` dans un navigateur sur mobile/ordinateur : fonctionnel sans installation.
- Sur Android, ouvrir le projet dans Android Studio, synchroniser Gradle, `Build > Build APK(s)`, puis installer `app-debug.apk`.
- Pour la version Android, **aucune autorisation Internet** n'est demandée.

## Commandes
- **Démarrer / Pause** : temps de simulation.
- **×1 / ×60 / ×3 600 / ×21 600** : vitesse du temps simulé.
- **Étape suivante** : aller à la prochaine manœuvre ou à l'événement historique suivant sans attendre les longues traversées.
- **Rejouer** : remettre MET à zéro.
- Appuyer sur une ligne de chronologie pour y aller directement.

## Limites de v0.1
La trajectoire est une construction 2D schématique corrélée aux événements. Les indicateurs de vitesse et de carburant sont interpolés et simplifiés. Le calcul de gravitation est issu de GM/r² et la vitesse orbitale circulaire de sqrt(GM/r), mais ce prototype **ne résout pas** les équations de mouvement d'Apollo 11 avec ses véritables conditions initiales et ne pourrait pas guider un vaisseau réel sans Houston. À ne pas utiliser pour la navigation réelle.

Sources : https://www.nasa.gov/wp-content/uploads/static/history/SP-4029/Apollo_11a_Summary.htm ; https://www.nasa.gov/history/apollo-11-mission-overview/ ; https://nssdc.gsfc.nasa.gov/planetary/lunar/apollo11info.html