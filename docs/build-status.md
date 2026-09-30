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
Próg orientacyjny 100 GiB został osiągnięty. Pełna synchronizacja źródeł i kompilacja
nie zostały wykonane, więc raport nie potwierdza, że obraz zmieści się na serwerze.
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
