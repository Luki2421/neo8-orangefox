# Diagnoza usług z telefonu — 1 października 2026

Przesłany dmesg z obrazu zbudowanego o 04:27 UTC potwierdza:

- `logd` nie może odczytać `/etc/task_profiles.json`, nie znajduje profili
  `SCHED_SP_BACKGROUND` i `BlkIOBackground`, po czym otrzymuje SIGABRT (6).
  Wyjaśnia to komunikat `logcat` o braku `logd.ready`.
- `servicemanager` odrzuca fragment
  `/system/etc/vintf/manifest/android.hardware.health-service.qti.xml`:
  `Cannot add a device manifest to a framework manifest`.
- Wpis rejestracji KeyMint występuje w logu; sam dmesg nie dowodzi trwałej
  awarii wszystkich usług kryptograficznych. Wcześniejszy recovery.log pokazuje
  ich restarty w chwili odczytu właściwości i timeout przygotowania.

Logów telefonu ani identyfikatorów urządzenia nie dodano do repozytorium.

## Zmiana obrazu

Integracja jawnie dołącza plik profili z faktycznie zsynchronizowanego
`system/core/libprocessgroup/profiles/task_profiles.json` do ramdisku, do
`system/etc`. Recovery tworzy `/etc -> /system/etc` w swoim init.
Sprawdzona rewizja system/core: `1efa79514b2f520c20a837c9216ff6b6e7e0dda3`.
Suma źródłowego pliku jest zapisywana w raporcie integracji.

Ta baza OrangeFox nazywa profil blkio/background `LowIoPriority`, podczas gdy
logd z obrazu żąda także `BlkIOBackground`. Dodano alias agregujący istniejący
profil. Nie zastąpiono go pustą akcją. Device init tworzy podgrupy
`/dev/cpuctl/background` i `/dev/blkio/background` w early-init, przed startem
logd w init. Kontrolery montuje istniejący mechanizm init/cgroups.

Usunięto z kopii katalogu system trzy fragmenty typu device: health, boot i
fastboot. Istniejące deklaracje vendor pozostają bez zmian, także jeśli mają
inną wersję HAL; brakujący fragment fastboot trafia do vendor. Nie zmieniono
manifestu framework ani dotychczasowego wykluczenia duplikatu touch.
To operacje na kopii drzewa urządzenia podczas budowania, nie na partycjach
telefonu. Konfiguracja szyfrowania i klucze pozostają poza zakresem tej zmiany.

## Walidacja

Sześć testów hosta sprawdza składanie konfiguracji, zachowanie istniejących
manifestów vendor/framework, alias profilu oraz odrzucanie obrazu z brakującym
plikiem/profilami albo fragmentem device w system, system_ext lub product.
Przeszły też istniejące testy czytnika ramdisku.

Kontrola nowego obrazu po kompilacji wymaga pliku profili i właściwego typu
manifestów; błąd zatrzymuje publikację artefaktu obrazu. Są to testy struktury.
Start logd, gotowość KeyMint/Weaver i odszyfrowanie PIN-em trzeba zweryfikować
na telefonie po zbudowaniu tej wersji.
