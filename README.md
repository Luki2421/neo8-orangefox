# Neo8 OrangeFox — przygotowanie GitHub Actions

Cel projektu: kolejna wersja OrangeFox dla RMX8899 / RE6402L1, Android 16,
RMX8899_16.0.10.500(CN01), z zachowaniem płynnego dotyku potwierdzonego w v3.

[Wyniki testów Actions i sprawdzenia źródeł OrangeFox 16](docs/build-status.md).
[Przygotowane poprawki kodu i zakres testów](docs/source-port.md).

**Repozytorium zawiera poprawki portu i workflow pełnej próby kompilacji Androida 16.
Nie ma jeszcze obrazu v4 gotowego do wgrania.** Testy hosta i raporty zasobów nie
potwierdzają odszyfrowania na telefonie. Osobny workflow `neo8-build.yml` próbuje
pobrać pełne źródła i zbudować `recoveryimage`; udostępnia tylko logi i manifest.

## Co jest już potwierdzone

- Użytkownik potwierdził start menu i bardzo płynny dotyk w v3-DIAG.
- v3 wyłącza dostęp do Data i usługi szyfrowania. Nie służy do decryption.
- Baza u9 Test1 używa NXP, a wcześniej sprawdzona paczka 500 zawiera TMS.
- Poprawki Neo8 KeyMint, Weaver i KeyStorage są przygotowane i przechodzą testy
  hosta; pełna kompilacja Androida oraz działanie na telefonie pozostają do sprawdzenia.
- Obraz TWRP przesłany wcześniej ma część poprawek Neo8, lecz jego zgodność
  z nowszą ochroną istniejących kluczy nie została potwierdzona.

## Pierwsze uruchomienie

Repozytorium projektu: [Luki2421/neo8-orangefox](https://github.com/Luki2421/neo8-orangefox).
Konfiguracja ma następujący układ:

- `.github/workflows/neo8-preflight.yml`
- `scripts/neo8_preflight.py`
- `README.md`

Workflow uruchomi się po zmianie pliku workflow lub skryptu. Można go też
uruchomić z Actions → Neo8 - check build resources → Run workflow.

Uruchomienie po zmianie kodu mierzy zasoby przed i po usunięciu zbędnych SDK.
Przy ręcznym uruchomieniu można wyłączyć opcję `cleanup` (domyślnie włączona).
Czyszczenie usuwa cztery zbędne SDK z tymczasowego serwera GitHub. Skrypt odmawia tej
operacji na lokalnym komputerze i na serwerach self-hosted. Nie usuwa plików
projektu, danych telefonu ani narzędzi uruchamiających Actions.

Raport będzie w podsumowaniu zadania oraz artefakcie `neo8-build-resources`.
Sprawdzamy rzeczywiste wolne miejsce, RAM, architekturę i położenie `/mnt`.
Próg 100 GiB w raporcie jest orientacyjnym zapasem dla recovery, nie gwarantuje budowy.
[Instrukcja źródeł Neo8 dla Androida 16](https://github.com/MissMyTime/twrp_device_sm8850/blob/d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e/docs/BUILD.md)
zaleca co najmniej 200 GB wolnego miejsca oraz 64 GB RAM albo odpowiedni swap.
Jeśli miejsca zabraknie, raport pozwoli zdecydować o dalszej konfiguracji
lub większym serwerze. Ten workflow nie kupuje serwera ani nie zmienia planu.

## Pełna próba kompilacji — uruchomiona

[Pełna próba kompilacji](https://github.com/Luki2421/neo8-orangefox/actions/runs/36699778777)
wykorzystuje przypięty manifest OrangeFox `fox_16.0`, sprawdzone poprawki kodu i
konfigurację urządzenia Neo8. Pierwsza próba zakończyła się na brakujących nazwach
projektów w lokalnym manifeście; poprawiono je w `b0448b5`.

To próba zgodności kompilacji. Konfiguracja ma wyłączone usuwanie wszystkich
kluczy metadanych oraz ograniczone czasowo oczekiwania w głównym init urządzenia.
Przed wydaniem obrazu potrzebne są integracja zachowania partycji w czasie pracy,
sprawdzenie dotyku względem v3 i kontrola struktury, bibliotek oraz AVB.

Osobna poprawka `neo8-manual-menu.patch` dodaje jawne przygotowanie metadanych
przed polem PIN-u/hasła. Test hosta sprawdza prawdziwy handler C++ z atrapami usług.
Poprawka przeszła [test źródeł na GitHubie](https://github.com/Luki2421/neo8-orangefox/actions/runs/36700977806).
Nie jest jeszcze częścią powyższej próby pełnej kompilacji. Nie obsługuje jeszcze użytkownika bez hasła. Działanie Data
pozostaje niezweryfikowane na telefonie.

Nie uruchamiamy automatycznego flashowania ani formatowania Data/Metadata.
Nie przesyłamy do repozytorium Twoich kluczy, PIN-u, danych ani podpisanego
adresu pobrania aktualizacji. Ten pakiet zawiera tylko własny kod i dokumentację.

## Źródła do dalszej pracy

- [Konfiguracja OrangeFox u9](https://github.com/koaaN/android_device_realme_u9-orangefox/tree/6a17319b7787a279ffccc86ce5bcb330f8b7535c)
- [Poprawki Neo8](https://github.com/MissMyTime/twrp_device_sm8850/tree/d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e/patches/neo8)
- [Opis rozdzielenia poprawek](https://github.com/MissMyTime/twrp_device_sm8850/blob/d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e/docs/PATCHES.md)
- [Budowanie OrangeFox](https://wiki.orangefox.tech/dev/building)
- [Parametry serwerów GitHub](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

W workflow przypięto odczytane SHA oficjalnych actions/checkout v4 i
actions/upload-artifact v4. Dostęp do repozytorium ograniczono do odczytu.
Kod skryptu i dokumentacja: GPL-3.0-or-later.
