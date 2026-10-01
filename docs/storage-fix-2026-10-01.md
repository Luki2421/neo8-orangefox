# Pamięć po uruchomieniu recovery — 1 października 2026

Użytkownik potwierdził uruchomienie recovery i działający dotyk, ale zgłosił brak
dostępu do pamięci. Nie otrzymano jeszcze logu z telefonu; nie potwierdzono
przyczyny ewentualnego błędu KeyMint ani odszyfrowania PIN-em.

## Znaleziony błąd

Profil Neo8 ustawia `TW_SKIP_POST_GUI_FSTAB_SETUP=true` oraz
`TW_NO_AUTO_DECRYPT=true`. `Process_Fstab` parsuje wpisy, lecz nie wykonuje
`Partition_Post_Processing`. Pominięta funkcja `Setup_Fstab_Partitions` była
jedyną ścieżką ustawiającą stan szyfrowania i `neo8_manual_decrypt`.
Warunki widoczności przycisku ręcznego odszyfrowania nie były więc spełnione.
Również samo `Decrypt_Data()` wymaga ustawionego stanu szyfrowania.
Nie rejestrowano też `/data/media` jako pamięci wewnętrznej z identyfikatorem MTP.

## Poprawka

`neo8-storage-init.patch`, stosowany po `neo8-runtime.patch`, dodaje lekką
inicjalizację do rzeczywistej ścieżki startowej, przed wczytaniem zasobów GUI.
Rejestruje pamięć wewnętrzną i unikalne identyfikatory MTP oraz ustawia stan
zablokowanego FBE i flagę menu. Wymaga wpisu `/data` z katalogiem klucza metadanych.
Tryb fastbootd pomija tę inicjalizację.

`Setup_Data_Media(false)` ustawia ścieżki i wykluczenia backupu bez montowania
ani sprawdzania zawartości userdata. Istniejące wywołania zachowują domyślne
sprawdzanie `/media/0`. Operacje kryptograficzne nadal zaczynają się dopiero po
wybraniu ręcznej akcji w GUI. Nie zmieniono obsługi dotyku, właściwości kasowania
kluczy ani ochrony istniejącego klucza metadanych.

## Sprawdzenie

Nowy test hosta kompiluje rzeczywisty fragment startu, nową funkcję zarządzania
partycjami i rzeczywistą funkcję konfiguracji data/media z atrapami usług.
Sprawdza 16 kombinacji flag, stan wymagany przez przycisk w XML, identyfikatory
MTP, brak montowania i kryptografii podczas inicjalizacji, fastbootd, brak wpisu
Data/klucza oraz zachowanie dotychczasowych wywołań konfiguracji pamięci.
Przechodzą też istniejące testy ręcznego przygotowania, środowiska metadanych,
oczekiwania na dotyk i zachowania montowań. Oba workflow uruchamiają nowy test.

Testy hosta nie zastępują kompilacji Androida ani próby odszyfrowania na telefonie.

## Próba na telefonie po udanej budowie poprawionej wersji

W menu **Other → Decrypt Data** wybierz ręczne przygotowanie metadanych.
Po jego powodzeniu recovery powinno wyświetlić pole PIN-u, hasła lub wzoru.
Ten profil nie obsługuje jeszcze użytkownika bez blokady ekranu.

Jeśli nadal nie ma pamięci albo pojawi się błąd, przy uruchomionym recovery
pobierz na komputer logi:

```sh
adb pull /tmp/recovery.log recovery.log
adb logcat -d > recovery-logcat.txt
```

Zapisz też treść komunikatu i informację, czy pole PIN-u/hasła w ogóle się
pojawiło. Nie dołączaj PIN-u, hasła ani plików kluczy. Nie używaj Format Data
ani formatowania Metadata do diagnozowania tej usterki.
