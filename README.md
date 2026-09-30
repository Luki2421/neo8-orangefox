# Neo8 OrangeFox — przygotowanie GitHub Actions

Cel projektu: kolejna wersja OrangeFox dla RMX8899 / RE6402L1, Android 16,
RMX8899_16.0.10.500(CN01), z zachowaniem płynnego dotyku potwierdzonego w v3.

[Wyniki testów Actions i sprawdzenia źródeł OrangeFox 16](docs/build-status.md).
[Przygotowane poprawki kodu i zakres testów](docs/source-port.md).

**Repozytorium zawiera test zasobów i poprawki kodu do portu Neo8. Nie zawiera
obrazu v4 i nie kompiluje recovery.** Zielony wynik Actions potwierdza tylko
wykonane testy hosta lub utworzenie raportu, nie odszyfrowanie na telefonie.

## Co jest już potwierdzone

- Użytkownik potwierdził start menu i bardzo płynny dotyk w v3-DIAG.
- v3 wyłącza dostęp do Data i usługi szyfrowania. Nie służy do decryption.
- Baza u9 Test1 używa NXP, a wcześniej sprawdzona paczka 500 zawiera TMS.
- Poprawki Neo8 KeyMint, Weaver i KeyStorage wymagają integracji w kodzie.
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

## Dalsza kompilacja — jeszcze nie jest skonfigurowana

Należy ustalić gałąź OrangeFox zgodną z wymaganymi interfejsami Androida 16,
przenieść poprawki Neo8 i ochronę przed trwałym zapisem zmienionych kluczy,
ustawić ręczne odszyfrowanie, dobrać spójne komponenty TMS do firmware 500
i przenieść działającą konfigurację dotyku. Obraz przeznaczony do testu wymaga
potem kontroli struktury, bibliotek i AVB. Działanie trzeba sprawdzić na telefonie.

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
