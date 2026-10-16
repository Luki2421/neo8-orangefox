# AERA dla realme Neo8 — eksperyment kompilacji

Bazą sprzętową jest **TWRP 1.1**, commit
`2b2981897893068276cef8040dca9171f53e9c9a`, oraz działający obraz
`TWRP_Neo8_1.1_nullguard_TEST.img` dostarczony przez użytkownika.
Rdzeń recovery i interfejs pochodzą z AERA Android 16.
Nazwa repozytorium nie oznacza użycia rdzenia OrangeFox w tej kompilacji.

**Nie jest to jeszcze recovery potwierdzone na telefonie.** Udana kompilacja
nie potwierdza uruchamiania, dotyku ani odszyfrowania PIN-em.

## Baza i poprawka uruchamiania

Workflow pobiera publiczne wydanie TWRP 1.1, sprawdza SHA-256 i odtwarza
poprawkę skryptem dostarczonym z działającym obrazem. Wynik musi mieć SHA-256:

`80b0cbb97a969b4df1ec4c9e51056c4a6433e3536364e987666186f3c5fd7573`

Lokalnie odtworzono ten obraz z identycznym hashem. Poprawka zmienia tylko
`system/bin/recovery`. Ten plik służy do identyfikacji wzorca — AERA kompiluje
własny plik wykonywalny. Nie stosujemy offsetów instrukcji TWRP do AERA.
W przypiętej wersji AERA nie ma podatnej funkcji `TWFunc::Init_Recovery`.
Nie stanowi to gwarancji braku innych błędów startu.

## Integracja

- Zachowane parametry partycji, obraz v4 bez kernela i limit 100 MiB z Neo8.
- Konfiguracja AERA dla realme GT8 Pro (`lafa`) pomaga w doborze flag AERA;
  nie kopiujemy z niej sterowników, rozmiarów partycji ani konfiguracji ekranu.
- `prepare.py` porównuje rzeczywiste miejsca docelowe plików urządzenia z
  działającym ramdiskiem i odtwarza różniące się pliki obecne w obu bazach.
  Lokalnie odtworzył 107 plików, a także pobrał fstab z wzorca.
  Pliki drzewa nieobecne we wzorcu są raportowane i pozostają w drzewie,
  z wyjątkiem aktywnych manifestów VINTF opisanych poniżej;
  wynik nie jest wierną kopią całego ramdisku TWRP.
- Decrypt.cpp, KeyStorage.cpp i Weaver1.cpp pochodzą z bazy Neo8.
  Dodano brakujące API kopii SQLite do AERA oraz kontrolę zatrzymania usług.
- Kopia bazy keystore jest tworzona w `/tmp`, z uwzględnieniem WAL.
  Wyłączono `ro.crypto.metadata_init_delete_all_keys.enabled` w konfiguracji.
- Zachowano krótki czas oczekiwania na DRM przed startem recovery.

Główne rewizje są zapisane w `sources.lock.json`. Pozostałe zależności AERA
są pobierane zgodnie z manifestem; pełny zestaw rzeczywistych rewizji jest
zapisywany przez workflow w `resolved-manifest.xml`.

## Weryfikacja i kompilacja

Lokalnie sprawdzono odtworzenie obrazu wzorcowego, nakładanie patchy na
przypięte źródła AERA, zatrzymanie i ponowny start usług przy błędach oraz
kopiowanie SQLite z WAL bez zmiany wejściowej bazy i jej plików pomocniczych.
Test usług używa atrapy Androida i nie testuje odszyfrowania telefonu.

Workflow `.github/workflows/aera-build.yml` uruchamia pełną kompilację na
gałęzi `aera-neo8-experimental`. Logi i manifest pozostają jako artefakty.
Jeśli kompilacja oraz inspekcja obrazu się powiodą, osobny artefakt zawiera
`AERA-Neo8-experimental.img`. Nie ma automatycznego flashowania ani publikacji
wydania oznaczonego jako działające.

Do sprawdzenia na dalszych etapach: zgodność API całego Androida podczas
kompilacji, kolejność inicjalizacji AERA i KeyMint, dostępność właściwości
zainstalowanego systemu, wszystkie zależności HAL oraz dotyk i PIN na Neo8.

## Stan prób z 14 października 2026

Pierwsza próba zakończyła się sygnałem wyłączenia runnera i kodem 143.
Druga przeszła przygotowanie źródeł i testy, ale etap kompilacji zakończył
się niepowodzeniem o 15:14 UTC. Zadanie utknęło w `Complete job`, a GitHub
nie udostępnił końcowego logu podczas sprawdzania o 15:36 UTC. Nie ustalono
przyczyny tej drugiej awarii; nie ma zweryfikowanego obrazu AERA.

Kolejna konfiguracja zapisuje diagnostykę źródeł przed kompilacją oraz
próbkuje RAM i miejsce na dysku w logu na żywo. Używa jednego zadania
kompilatora i ogranicza cel pamięci GC Go do 10 GiB (to nie twardy limit RAM).
Zmiany mają zmniejszyć obciążenie i zapewnić dane do diagnozy; nie dowodzą,
że wcześniejszą przyczyną był brak pamięci. Nowe uruchomienie zastępuje
poprzednią aktywną próbę na tej samej gałęzi.

Źródła:

- https://github.com/MissMyTime/twrp_device_sm8850/releases/tag/1.1
- https://github.com/AERA-Recovery/android_bootable_recovery
- https://github.com/AERA-Recovery/android_device_realme_lafa-AERA

## Poprawki po próbie na telefonie z 15 października 2026

Kompilacja 38605341457 zakończyła się powodzeniem, ale na telefonie recovery
pozostawało na ekranie startowym, a restart z menu nie kończył się restartem.
Dotyk i ADB działały. Logi i spakowany obraz potwierdziły następujące problemy:

- Konflikty aktywnych manifestów VINTF (m.in. Gatekeeper) i deklaracja urządzenia
  w przestrzeni framework. Przygotowanie przywraca manifesty z obrazu TWRP 1.1
  nullguard o sprawdzonym SHA, usuwa dodatkowe aktywne fragmenty i przenosi
  deklarację fastboot do vendor. AERA nadal generuje własny fragment keystore2.
- Brak plików task_profiles wymaganych przez logd. Konfiguracja cgroups i profili
  jest teraz odtwarzana z tego samego obrazu wzorcowego.
- Kod APEX odczytywał rozmiar po zamknięciu deskryptora, co skutkowało błędem
  przepełnienia. Patch pobiera rozmiar przez fstat przed zamknięciem, używa
  przydzielonego numeru loop i sprząta deskryptory oraz loop po błędach.

Dodano logi początku i końca konfiguracji partycji oraz wywołania Decrypt_Data.
Nie dowiedziono jeszcze, że te poprawki usuwają całe zatrzymanie inicjalizacji.
Odszyfrowanie, restart i pozostałe błędy usług wymagają ponownej próby na Neo8.

Lokalnie przeszły przygotowanie przypiętych źródeł, testy konfiguracji (także
celowo błędnych danych), 10 scenariuszy kodu APEX z atrapami wywołań systemowych
oraz test zatrzymywania usług. Ponowne lokalne uruchomienie testu kopii SQLite
było zablokowane brakiem nagłówka sqlite3.h; workflow instaluje libsqlite3-dev
i nadal wymaga przejścia tego testu. Testy hosta nie zastępują kompilacji Androida
ani próby na telefonie.

Przed udostępnieniem obrazu workflow sprawdza również konfigurację w gotowym
ramdisku i zapisuje runtime-validation.json. Walidator wykrywa brak profili,
konflikty instancji HAL i niewłaściwe przestrzenie manifestów; nie zastępuje
pełnej walidacji zgodności libvintf ani testu uruchomienia usług.

## Korekta po kompilacji 38777203358

Pełna kompilacja i wszystkie testy źródeł (w tym SQLite/WAL) przeszły.
Kontrola ramdisku zatrzymała publikację obrazu: moduł fastboot AERA dodawał
ponownie swój fragment VINTF do system/etc, mimo przeniesienia kopii urządzenia
do vendor/etc. Patch 0005 usuwa wyłącznie automatyczne dołączanie tego fragmentu;
deklaracja urządzenia pozostaje w vendor, a binarium usługi i init_rc pozostają
włączone. Przypięto rewizję hardware/interfaces, na której wystąpił błąd.
Walidator gotowego obrazu pozostaje obowiązkowy.

## Ponowny przegląd inicjalizacji i restartu

Kod gui_init uruchamia StartAeraUiEarly w osobnym wątku przed synchronicznym
Setup_Fstab_Partitions i Decrypt_Data. Konstruktor Keystore czeka bez limitu
na usługę keystore2. Wynik wyboru restartu z wczesnego UI jest odbierany później
przez główny wątek. Wyjaśnia to możliwość działającego dotyku i ekranu restartu
przy zablokowanej inicjalizacji. Bez stosu wątków z telefonu nie potwierdzono
dokładnego wywołania, na którym zatrzymał się stary obraz.

Log kernela zawiera także odmowy binder z hal_fastboot_default do recovery
przy permissive=0. Serwis fastboot z hardware/interfaces używał tej pierwszej
domeny, a servicemanager i pozostałe HAL recovery używają recovery. Patch 0006
dopasowuje seclabel wyłącznie tego serwisu recovery; nie zmienia globalnego
trybu SELinux, UID, grup ani pliku wykonywalnego. Poprawka manifestu sama nie
usuwała tej odmowy. Działanie fastbootd wymaga ponownego testu na telefonie.

Nie zmieniono nieograniczonych oczekiwań Binder ani ścieżki restartu w ciemno.
Nowe logi startu mają rozróżnić powrót z odszyfrowania od dalszej blokady.
Sukces kompilacji i kontroli ramdisku nadal nie potwierdza odszyfrowania PIN-em.

## Zawieszenie po podaniu blokady w neo8-ci-8

Nowe logi z telefonu potwierdzają powodzenie odszyfrowania metadanych i powrót
z początkowego Decrypt_Data. Recovery działa po pominięciu blokady. Przy próbie
odszyfrowania użytkownika Weaver zgłasza błąd getConfig, OMAPI czeka na
android.hardware.secure_element.ISecureElement/eSE1, a transport nie ma usługi
OMAPI. NFC dodatkowo kończy się błędem brakującego android.hardware.nfc-V1-ndk.so.

Przygotowanie kopiowało z obrazu wzorcowego tylko pliki o nazwach obecnych już
w drzewie dawcy. W efekcie odtwarzało init rc wskazujący na
neo8-secure-element-launcher.sh, lecz pomijało sam skrypt, prywatny zestaw ABI,
część bibliotek transportu i zestaw TA pod system/etc/neo8-firmware.
secure_element_bundle.py dodaje teraz jawnie wybrane 61 plików z obrazu
nullguard o sprawdzonym SHA: skrypt i rc, 11 prywatnych bibliotek, 7 bibliotek
transportu, konfigurację TA i 40 fragmentów firmware czterech TA.
Nie kopiuje ogólnych bibliotek systemowych ani binarium recovery z TWRP.

Test odrzuca brak każdego składnika, złą zawartość biblioteki oraz błędny typ
lub tryb pliku skryptu. Kontrola gotowego ramdisku porównuje ten sam zestaw
z obrazem wzorcowym przed zachowaniem artefaktu. Nie jest to test działania
linkera Androida, komunikacji z eSE ani poprawności odszyfrowania na telefonie.

Oczekiwanie getConfig w kodzie dawcy ma 80 prób; na telefonie pojedyncza próba
HAL wykonuje 12 ponowień po około sekundzie. Awaria transportu może więc
wyglądać jak zawieszenie trwające około 16 minut. Ta poprawka uzupełnia brakujące
pliki; nie zmienia algorytmu odszyfrowania ani liczby prób uwierzytelnienia.
Log klasyfikuje blokadę jako pattern (typ 1); typ blokady należy potwierdzić
przy kolejnej próbie, jeżeli użytkownik rzeczywiście korzysta z PIN-u liczbowego.
