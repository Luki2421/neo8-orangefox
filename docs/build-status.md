# Stan przygotowania Neo8 — 30 września 2026

## Test GitHub Actions

Konfiguracja jest przesłana do `Luki2421/neo8-orangefox` i uruchomiona.
Oba uruchomienia zakończyły się powodzeniem:

| Pomiar | CPU | RAM | Wolne miejsce w workspace |
|---|---|---|---|
| Bez czyszczenia | 4, x86_64 | 15,6 GiB | 86,0 GiB |
| Po usunięciu zbędnych SDK | 4, x86_64 | 15,6 GiB | 106,4 GiB |

- [Pierwszy test](https://github.com/Luki2421/neo8-orangefox/actions/runs/36691986086)
- [Test po czyszczeniu](https://github.com/Luki2421/neo8-orangefox/actions/runs/36692264169)
- [Raport JSON i Markdown](https://github.com/Luki2421/neo8-orangefox/actions/runs/36692264169/artifacts/11085733473), retencja 3 dni.

Wyniki pochodzą z logów zakończonych zadań. Nie sumowano miejsca z `/mnt`.
Próg orientacyjny 100 GiB został osiągnięty. Powyższe pomiary nie wykonują synchronizacji ani kompilacji i nie potwierdzają,
że pełny obraz zmieści się na serwerze. Osobną próbę pełnej budowy uruchomiono poniżej.
[Instrukcja źródeł Neo8](https://github.com/MissMyTime/twrp_device_sm8850/blob/d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e/docs/BUILD.md)
zaleca co najmniej 200 GB dysku i 64 GB RAM lub odpowiedni swap.

## Potwierdzona baza Androida 16

Odczytano i sprawdzono publiczną gałąź `fox_16.0`:

- OrangeFox recovery: `c239fc5a54ecb482e4a49117bf45b7e76de68371`.
- [Kod przypięty do tej wersji](https://gitlab.com/OrangeFox/bootable/Recovery/-/tree/c239fc5a54ecb482e4a49117bf45b7e76de68371).
- Źródła urządzenia Neo8: `d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e`.
- [Oficjalny skrypt synchronizacji](https://gitlab.com/OrangeFox/sync/-/blob/master/orangefox_sync.sh)
  odczytany 30 września wskazuje dla gałęzi 16.0 manifest
  `https://gitlab.com/OrangeFox/Manifest.git`, gałąź `fox_16.0`.

## Różnica istotna dla startu recovery

Sprawdzona konfiguracja Neo8 ustawia `TW_NO_AUTO_DECRYPT := true` oraz
`TW_SKIP_POST_GUI_FSTAB_SETUP := true`. Jej poprawki TWRP wykorzystują te ustawienia
do pominięcia odszyfrowania przy starcie i ponownego ustawiania fstab po starcie GUI.

W sprawdzonym OrangeFox 16 nie znaleziono obsługi tych dwóch ustawień.
`Setup_Fstab_Partitions()` nadal wywołuje `Decrypt_Data()` przy włączonym
`TW_INCLUDE_CRYPTO`, a `twrp.cpp` uruchamia tę konfigurację po `gui_init()`.
To potwierdzona różnica w kodzie, nie rozpoznanie przyczyny zawieszenia wcześniejszego
v2 na telefonie. Nie ma logów pozwalających potwierdzić tamtą przyczynę.

Przed testem Data trzeba przenieść odpowiednie zachowanie do OrangeFox,
zintegrować implementację Neo8 vold/Weaver/KeyMint i ochronę KeyStorage,
a następnie zbudować i zweryfikować obraz. Nie należy zastępować całego OrangeFox
plikami wspólnymi TWRP, bo zmieniłoby to jego własną implementację.

## Stan obrazów

- Użytkownik potwierdził działający start i bardzo płynny dotyk w v3-DIAG.
- v3-DIAG ma wyłączony dostęp do Data.
- W tym repozytorium nie utworzono obrazu v4 ani działającego odszyfrowania.
- Nie wykonano flashowania ani zmian na telefonie przez GitHub Actions.

Dokumentacja: GPL-3.0-or-later.

## Pełna próba kompilacji — 30 września

Dodano workflow `neo8-build.yml`: oficjalny manifest Androida 16, przypięte
recovery/vold, konfiguracja Neo8, integracja sprawdzonych poprawek i cel
`recoveryimage` z dwoma zadaniami kompilatora. Runner jest zwykłym GitHub-hosted
Ubuntu 24.04; nie kupowano większego serwera.

- [Pierwsza próba](https://github.com/Luki2421/neo8-orangefox/actions/runs/36699386590):
  przygotowanie runnera i zależności udane; synchronizacja nie rozpoczęła się przez
  brak atrybutów `name` w dwóch `extend-project`. Kompilator nie został uruchomiony.
- Poprawka: commit `b0448b5c049d9b3b498afd96f49cc67115d5b7e9`.
- [Druga próba](https://github.com/Luki2421/neo8-orangefox/actions/runs/36699778777):
  uruchomiona; ostatnio sprawdzony etap: pobieranie pełnych źródeł.

Skrypt przygotowania urządzenia zastępuje nazwę pluginu GUI przez `libfoxui`,
wyłącza `ro.crypto.metadata_init_delete_all_keys.enabled` i ogranicza czekanie
w `init.recovery.qcom.rc`. Lokalnie wykonano ten skrypt na przypiętej konfiguracji,
sprawdzono wynik i składnię kroków shell workflow.

Workflow celowo nie publikuje obrazu do wgrania: pozostają integracja partycji
w czasie pracy, porównanie dotyku z v3 oraz przegląd gotowego ramdisku.
Osobna poprawka ręcznego menu przechodzi test handlera z atrapami usług, ale nie
jest jeszcze w tej pełnej próbie budowy. Nie potwierdzono odszyfrowania na telefonie.

## Ręczne przygotowanie metadanych — sprawdzone na hoście

[Test GitHub Actions](https://github.com/Luki2421/neo8-orangefox/actions/runs/36700977806)
zakończył się sukcesem: poprawki zastosowano na przypiętym kodzie, testowano
rzeczywisty handler C++ dla 3 typów blokady, 6 sytuacji błędu oraz symulacji.
Przeszły także wcześniejsze testy startu, ochrony operacji KeyMint i kopii SQLite
z plikiem WAL. Dodano wpis językowy; lokalny linter GUI zgłasza 0 problemów
w 25 plikach stron. To nadal nie jest test Androida ani telefonu.

Odczytano publiczne komponenty dotyku Neo8 i porównano je z publiczną bazą v3.
`libFT3683Gtsa.so`, `libthpalgo.so` oraz `vendor.oplus.hardware.touch-V2-ndk.so`
są identyczne. Program `vendor-oplus-hardware-touch-V2-service` jest inny:
Neo8 `e0f95763dacbd84b7ea722be208828a14734334dd1e8924162e57110326ad115`.
Ma także inną zależność biblioteki C++ (`libc++.so`, zamiast `libc++_v36.so`).
Nie wyciągano z samej zgodności nazw bibliotek wniosku o zgodności ABI lub
płynności dotyku. Przed wydaniem obrazu nadal potrzebna jest kontrola tej konfiguracji.
