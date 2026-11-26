# Kajak 3D

3D prikaz ture za [OpenTracks](https://github.com/OpenTracksApp/OpenTracks): satelitska karta s reljefom,
kamera iza kajaka uživo dok veslate i reprodukcija „iz zraka” nakon ture.

## Kako radi
Aplikacija se prijavljuje za isti poziv koji OpenTracks šalje kad dodirnete **ikonu karte**
(`Intent.OpenTracks-Dashboard`, isti protokol kao OSM Dashboard). OpenTracks se ne mijenja.

- **Tijekom snimanja** – kamera prati zadnju točku, trag raste uživo (oznaka UŽIVO).
- **Nakon ture** – gumb ▶ Kreni pokreće let duž rute; klizač, brzina reprodukcije, „Cijela ruta”.
- Iz izbornika aplikacije može se otvoriti i KMZ/KML/GPX datoteka.

Karta (Esri World Imagery) i teren (AWS Terrain Tiles) učitavaju se s interneta.

## Izgradnja
Svaki `push` na `main` pokreće GitHub Actions koji izgradi APK i objavi ga
u **Releases → najnovija → Kajak3D.apk**.

Napomena: `app/kajak3d.keystore` je ključ samo za potpisivanje ove privatne aplikacije,
da bi se nove verzije mogle instalirati preko stare.
