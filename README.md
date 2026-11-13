# Franken Flasher (Android)

Aplikacja na telefon, ktora flashuje **drugi telefon** przez kabel OTG (fastboot / fastbootd), wzorowana na FastbootFirmwareFlasher.

## Etapy
1. **Fastboot przez OTG** (ten etap): polaczenie, `getvar`, restart do fastbootd/bootloadera, zmiana slotu, flash pojedynczego obrazu (duze obrazy raw dzielone na kawalki sparse).
2. Flash calego firmware (BOOTLOADER/CRITICAL/SYSTEM/MODEM), vbmeta, slot.
3. Franken: baza + dawca, EROFS, poprawki (my_product, my_manifest, odm, app_v2, min_settings, AILightPerceptionConfig, flagi wibracji, dzwonki).

## Budowanie
GitHub Actions buduje APK przy kazdym pushu - pobierz z zakladki **Actions -> ostatni build -> Artifacts**.
