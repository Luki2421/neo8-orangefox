# Poprawki kodu OrangeFox 16 dla Neo8

To przygotowanie źródeł, nie obraz do flashowania. Workflow
`Neo8 - validate source patches` sprawdza zastosowanie poprawek i wykonuje
testy na komputerze ze sztucznymi danymi. Nie kompiluje recovery dla Androida.

## Co zostało przygotowane

- Obsługa `TW_NO_AUTO_DECRYPT`, `TW_SKIP_POST_GUI_FSTAB_SETUP` i
  `TW_FORCE_STOCK_THEME_ON_BOOT` w kodzie OrangeFox i konfiguracji bibliotek GUI.
- Pominięcie startowej próby odszyfrowania i start głównego menu.
- Przygotowanie mapowania metadata podczas ręcznej akcji odszyfrowania.
- Zachowanie ścieżki FBE, gdy zaszyfrowana Data nie daje się jeszcze zamontować.
- Import trzech przypiętych plików Neo8: Decrypt.cpp, KeyStorage.cpp, Weaver1.cpp.
- Brakująca funkcja `KeystoreInfo::backupDatabase`, potrzebna przez Decrypt.cpp.
  Kopiuje bazę i WAL do katalogu tymczasowego przed użyciem API SQLite;
  SQLite nie otwiera oryginalnej bazy keystore. Wywołujący zatrzymuje keystore2.
- Odczyt locksettings.db w trybie tylko do odczytu, bez tworzenia brakującej bazy.

Plik `port-sources.json` przypina wersje recovery, vold i poprawek Neo8 oraz
SHA256 trzech importowanych plików. Skrypt odmawia pracy na innych wersjach
lub zmodyfikowanym drzewie. Najpierw sprawdza wszystkie wejścia i dopasowanie
obu patchy, dopiero potem stosuje zmiany. Nie zastępuje całego recovery plikami TWRP.

## Testy

`test_startup_and_key_guard.py` kompiluje rzeczywiste, wydzielone funkcje z kodu
ze stubami usług Androida. Sprawdza osiem kombinacji ustawień startu/GUI oraz
cztery wyniki operacji KeyMint, w tym zwrócenie zmienionego blobu bez zapisu go
do pliku. Nie dostarcza funkcji trwałego zapisu ani kasowania kluczy do testowanej
funkcji BeginKeystoreOp.

`test_keystore_snapshot.py` kompiluje rzeczywistą implementację KeystoreInfo.
Sprawdza wpis obecny tylko w WAL, integralność kopii oraz niezmienione SHA256
oryginalnej bazy, WAL i SHM. Sprawdza odmowę dla aliasu oryginalnego pliku,
symlinka, brakującej i uszkodzonej bazy. Dane testowe są fikcyjne.

Testy hosta nie sprawdzają Binder/HAL, linkowania dla Androida, sterowników,
startu obrazu ani odszyfrowania na telefonie. Wynik zielony nie potwierdza działania v4.

## Co pozostaje przed kompilacją obrazu

1. Integracja drzewa RE6402L1 z build systemem OrangeFox 16, w tym aktualnych nazw
   modułów GUI i ustawień Soong. Flagi w BoardConfig trzeba powiązać z poprawionym kodem.
2. Dopasowanie init, usług TMS/OMAPI/ssgtzd i komponentów firmware do konkretnego
   systemu; zgodność wszystkich komponentów z paczką 500 nie jest potwierdzona.
3. Przeniesienie zestawu dotyku potwierdzonego na telefonie w v3 oraz weryfikacja
   jego zależności bibliotek w nowym ramdisku.
4. Sprawdzenie przejścia z menu do ręcznej akcji przygotowania metadata i ekranu
   właściwego typu hasła po odczycie informacji o użytkowniku.
5. Wyłączenie `ro.crypto.metadata_init_delete_all_keys.enabled` w konfiguracji
   testowej. W przypiętym system.prop upstream wartość jest true; nie wolno jej
   bez przeglądu przenosić do testowej paczki. Poprawki w tym katalogu nie obejmują
   jeszcze konfiguracji urządzenia.
6. Pełna kompilacja recovery w drzewie Androida, kontrola header v4, ramdisku,
   bibliotek i AVB, a następnie test na telefonie.

Serwer GitHub zmierzony wcześniej ma 106,4 GiB wolnego miejsca po czyszczeniu
i 15,6 GiB RAM. [Instrukcja źródeł Neo8](https://github.com/MissMyTime/twrp_device_sm8850/blob/d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e/docs/BUILD.md)
zaleca 200 GB wolnego miejsca i 64 GB RAM albo odpowiedni swap. Rzeczywistego
zużycia dla tego portu jeszcze nie zmierzono; nie stwierdzono kompilacją, że serwer
nie może zbudować obrazu.

## Pochodzenie i licencje

- OrangeFox recovery/vold: repozytoria i commity wskazane w port-sources.json.
- Fragment Decrypt_Data w patchu core pochodzi z
  [poprawek wspólnych MissMyTime](https://github.com/MissMyTime/twrp_device_sm8850/blob/d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e/patches/common/files/bootable/recovery/partitionmanager.cpp),
  zaadaptowany bez automatycznej próby domyślnego hasła w trybie TW_NO_AUTO_DECRYPT.
- Importowane pliki zachowują nagłówki upstream (Apache-2.0 dla kodu vold,
  GPL dla odpowiednich plików recovery/KeystoreInfo). Nie są binariami firmware.
- Własne skrypty, testy i dokumentacja: GPL-3.0-or-later.
