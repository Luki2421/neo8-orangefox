# AERA dla realme Neo8 — eksperyment kompilacji

Bazą sprzętową jest **TWRP 1.1**, commit
`2b2981897893068276cef8040dca9171f53e9c9a`, oraz działający obraz
`TWRP_Neo8_1.1_nullguard_TEST.img` dostarczony przez użytkownika.
Rdzeń recovery i interfejs pochodzą z AERA Android 16.
Nazwa repozytorium nie oznacza użycia rdzenia OrangeFox w tej kompilacji.

**Kompilacja neo8-ci-9 została sprawdzona na telefonie:** uruchamianie, dotyk,
odszyfrowanie wzorem, dostęp do plików, restart do systemu i kopia DTBO B.
Przywracanie kopii nie zostało potwierdzone. Kolejna zmiana Wi-Fi jest
eksperymentalna i wymaga osobnego sprawdzenia na urządzeniu.

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


## Wi-Fi po sprawdzeniu neo8-ci-9

W gotowym obrazie brakowało skompilowanego backendu WLAN, usługi init oraz
programów wpa_supplicant/wpa_cli/dhcptool w ścieżkach AERA. Z telefonu otrzymano
listę montowań bez vendor/odm/vendor_dlkm/system_dlkm oraz listę modułów bez
cnss_plat_ipc_qmi_svc, icnss2 i qca_cld3_wcn7750. Sam komunikat „unavailable”
wynikał z wyłączonej flagi kompilacji; brak modułów nie dowodzi awarii sprzętu.

Zmiana włącza AERA_ENABLE_WLAN i backend nl80211. Usługa uruchamia się dopiero
po żądaniu z UI; korzysta z loadera i klienta HAL z obrazu referencyjnego.
Partycje zainstalowanego systemu montuje tylko do odczytu pod /tmp/aera-wifi.
Nie zasłania całych /vendor ani /odm. Wyłącznie podkatalogi etc/wifi otrzymują
powiązania z konfiguracją stock, a biblioteki stock są wskazywane przez
LD_LIBRARY_PATH tylko procesom Wi-Fi. Istniejące montowanie persist pozostaje;
przy jego braku skrypt próbuje montowania nazwanego persist tylko do odczytu.
Nie ma zapisu obrazu partycji, formatowania ani kopiowania bazy sieci Androida.

wpa_supplicant korzysta z konfiguracji i gniazd w /tmp/recovery. Cały loader
ma limit 100 s (+5 s do wymuszenia zakończenia), a UI oczekuje do 120 s na
odpowiedź gniazda. Przedwczesne zakończenie usługi kończy oczekiwanie błędem.
Init zarządza grupą procesów usługi, w tym potomnymi HAL i CNSS. Firmware jest
wyszukiwany dodatkowo w osobnych montowaniach; katalogi kryptograficzne i
pakiet Secure Element z kompilacji 9 pozostają bez zmian.

Testy hosta obejmują rzeczywiste funkcje powłoki z atrapą insmod: kolejność
zależności, wpisy modules.dep z i bez ścieżek, brak zależności, błąd insmod,
cykl, współdzielone zależności, zagnieżdżone pliki i nazwę modułu wlan przy ponownym włączeniu. Walidator gotowego obrazu sprawdza obecność
backendu WLAN, wykonywalnych programów, dokładną konfigurację, helper HAL,
ścieżki firmware oraz pojedynczą usługę supplicant. Te kontrole nie sprawdzają
ABI bibliotek zainstalowanego systemu ani działania radia. Skanowanie,
połączenie, DHCP i ponowne włączenie wymagają próby na Neo8.

Logi nowej próby: /tmp/neo8-wifi-service.log, /tmp/neo8-wifi-loader.log,
/tmp/dhcptool.log oraz /tmp/recovery.log (wyjście supplicanta jest w
/tmp/neo8-wifi-service.log).

## Wi-Fi: poprawka po teście kompilacji 11

Logcat potwierdza start HAL bez poprzedniej awarii Bindera, ale pierwsze
`IWifi.start()` kończy się błędem po około 20 sekundach. W osobnej sesji
`wifi11-kernel.txt` pokazuje `Driver Loading Timed-out!!` w 84,958 s,
rozłączenie CNSS w 84,965 s i dopiero `FW ready event received` w 90,621 s.
Inicjalizacja sterownika postępuje jeszcze po zakończeniu loadera. Wcześniej
widać oczekiwanie na DMS QMI, a następnie przejście na domyślny MAC.

Loader ponawia wyłącznie błąd `IWifi.start` (kod 3 przypiętego helpera),
maksymalnie do trzech prób z odstępem 8 sekund. Pozostaje przy życiu między
próbami, dzięki czemu init nie kończy przedwcześnie jego procesów HAL/CNSS.
Inne błędy nadal przerywają uruchamianie. Całość nadal ogranicza istniejący
timeout 100 sekund. Testy hosta obejmują opóźniony sukces, wyczerpanie prób
i błędy, których nie należy ponawiać. Skuteczność na telefonie wymaga testu;
sam komunikat gotowości firmware nie potwierdza jeszcze połączenia Wi-Fi.

## Wi-Fi: poprawka po teście kompilacji 10

Logcat z telefonu z 2026-10-17 pokazał, że HAL i klient otwierają
`/dev/vndbinder`, a HAL kończy się `SIGABRT` przy rejestracji
(`Failed to register wifi HAL`, `result=-129`). Loader nadawał obu procesom
taką samą kolejność bibliotek jak CNSS: stock vendor przed systemem recovery.

HAL AIDL i klient otrzymują teraz osobną ścieżkę z `/system/lib64` oraz
`/system_ext/lib64` przed bibliotekami stock vendor/odm, zgodnie z kolejnością
w skrypcie referencyjnym. CNSS zachowuje vendor jako pierwsze źródło bibliotek.
Ścieżki są ustawiane tylko dla tych procesów. Test hostowy uruchamia funkcje
wygenerowanego loadera z atrapami programów i sprawdza ich środowiska.
Naprawa rejestracji HAL oraz działanie radia wymagają ponownego testu na telefonie.

## USB i StrongBox: wspólna poprawka po logach kompilacji 11

W `wifi11-kernel.txt` akcję ADB uruchamiają kolejno ogólny init AERA
(16,657 s) oraz init urządzenia (16,680 s). Druga próba zgłasza istniejący
symlink i zajęty UDC. Konfiguracja urządzenia przejmuje wszystkie przełączenia
configfs i montowania FunctionFS. Ogólny init zachowuje tworzenie gadżetu,
usługi adbd/fastbootd oraz uruchamianie tych usług. Przed zmianą deskryptorów
i dowiązań UDC jest odłączany. Tryb `none` zatrzymuje obie usługi i czyści
sygnały gotowości. MTP i MTP+ADB czekają na `sys.usb.ffs.mtp.ready=1`, które
przypięty MtpDescriptors.cpp ustawia po zapisaniu deskryptorów i ciągów znaków.
Sam sygnał gotowości ADB nie wystarcza do podłączenia obu funkcji.

W tej sesji jądra StrongBox kończy się kodem 1 i jest ponownie uruchamiany.
Wpis entrypoint wskazuje program z `/odm` na dm-11. Osobna sesja logcat
pokazuje natomiast udane `sendRootOfTrust` oraz dostępność STRONGBOX;
nie jest to podstawa do wyłączania tej usługi ani uznania jej za zbędną.
Nie mamy komunikatu wyjaśniającego kod 1 z nieudanej sesji.

Nowy launcher uruchamia kopię programu z referencyjnego obrazu w
`/system/bin/neo8-strongbox-service`, z bibliotekami vendor w osobnym
`/system/lib64/neo8_strongbox`. Wybór tego programu i jego zależności
DT_NEEDED nie zależy już od zasłonięcia `/odm` partycją stock. Biblioteki
systemowe AERA zachowują pierwszeństwo, w tym Binder. Stderr jest zapisywany
w `/tmp/neo8-strongbox.log` z uprawnieniami 0600. Klasa, użytkownik, grupy,
capabilities, etykieta SELinux i moment startu usługi pozostają jak w referencji.
To korekta źródła plików i diagnostyki, a nie potwierdzenie usunięcia każdej
możliwej przyczyny kodu 1. Biblioteki ładowane dynamicznie, komunikacja z SE
oraz zgodność ABI wymagają próby na telefonie.

Testy hosta sprawdzają 12 przejść trybów USB, obie kolejności gotowości
MTP/ADB, przejście bezpośrednie, brak podwójnych montowań, zależności ELF,
brakujące pliki i środowisko launchera. Po kompilacji osobna bramka sprawdza
zależności StrongBox w rzeczywistym gotowym obrazie. Pakiet Secure Element
z działającej kompilacji 9 pozostaje bez zmian. Zmiany nie modyfikują danych,
kluczy użytkownika, manifestów kryptograficznych ani fstab. Nowy obraz wymaga
sprawdzenia odszyfrowania wzorem, ADB/MTP, fastbootd oraz Wi-Fi na Neo8.

## busybox

Menedżer wtyczek AERA pobiera katalog przez `busybox wget`, a test sieci używa `busybox ping`; obraz nie miał busyboxa. `aera/wifi/busybox-arm64` to statyczny BusyBox v1.36.1 (osm0sis, NDK, GPL-2.0) z https://github.com/Magisk-Modules-Repo/busybox-ndk, commit ad6700620120e3c475df05b0f8bfa7a40e741ee0, sha256 c629fce4b0dd3ba9775f851d0941e74582115f423258d3a79800f2bd11d30f5c. Trafia do obrazu jako `/system/bin/busybox`.

## APEX i ponowne podłączenie MTP

Patch `0009` rozpoznaje ext4 lub EROFS po sygnaturze superbloku wypakowanego
APEX i montuje obraz tylko do odczytu. Nieznany format lub niepełny odczyt
kończy się jawnym błędem przed podłączeniem pliku do urządzenia loop.
To usuwa założenie, że każdy APEX jest ext4; rzeczywisty format `tzdata`
na telefonie musi jeszcze zostać potwierdzony w nowym logu.

Patch `0008` zeruje `sys.usb.ffs.mtp.ready` przed zamknięciem endpointów
lub uchwytu kontrolnego oraz przed zapisaniem nowych deskryptorów.
Gotowość wraca dopiero po udanym zapisie deskryptorów i ciągów znaków.
Usuwa to nieaktualny stan gotowości w 800-milisekundowej przerwie restartu
MTP, podczas której ADB może już być gotowe. Poprawia również `bcdVersion`
deskryptora Microsoft OS z 0x0001 na 0x0100.

Testy hosta wykonują zmienione funkcje C++: 28 scenariuszy APEX oraz
8 scenariuszy zapisu deskryptorów MTP, zamykanie endpointów i ponowne
połączenie. Nie emulują sterownika USB ani montowania na Neo8. Próba na
telefonie powinna objąć start bez kabla i z kablem, kilkukrotne odpięcie
i podpięcie przy włączonym MTP/ADB oraz przesłanie pliku w obie strony.

## Aktualizacja źródeł AERA — 19.10.2026

Rdzeń recovery został zaktualizowany z `f2ce8f70` do `a0d165f1`
(42 commity, najnowszy z 18.10.2026), a manifest z `250b38ee` do `4b0611ad`.
Nowy manifest pobiera również biblioteki `external/libsmb2` i `external/libssh2`
wymagane przez nową obsługę SMB/SFTP. Rewizje vold, hardware/interfaces
i vendor/recovery sprawdzono na gałęzi `aera-16.0`; nie uległy zmianie.

Zachowano dotychczasowe poprawki Neo8, w tym Wi-Fi, BusyBox, certyfikaty,
KGSL, USB/MTP, APEX i StrongBox. Patch `0004` dopasowano do nowego kontekstu
w `Setup_Fstab_Partitions`; dodaje te same komunikaty diagnostyczne i zachowuje
nową logikę upstream dla urządzeń bez szyfrowania. Pozostałe łatki recovery
stosują się bez zmian. Workflow i `sources.lock.json` wskazują te same nowe
rewizje. Pełna kompilacja i weryfikacja obrazu są wykonywane przez Actions;
działanie nowej bazy nadal wymaga testu na Neo8.

## Instalacja OTA i odmontowanie vendor — 21.10.2026

Patch `0010` usuwa blokadę `Failed to unmount Vendor` przed uruchomieniem
instalatora payload A/B. Typ ZIP jest wybierany raz, po otwarciu i opcjonalnej
weryfikacji podpisu. Zarówno warunek odmontowania, jak i właściwy instalator
korzystają z tego samego wyboru. ZIP zawierający `update-binary` zachowuje
pierwszeństwo przed payloadem i dotychczasowy wymóg odmontowania.
Nie zmieniamy ustawienia użytkownika `TW_UNMOUNT_VENDOR` globalnie.

Dla payload A/B pozostaje montowanie aktywnego vendor na potrzeby backuptool,
tak jak w działającym TWRP 1.1; zapis aktualizacji obsługuje istniejący updater.
Poprawka nie zmienia wyboru slotu, kontroli zgodności, weryfikacji podpisu,
mechanizmu Virtual A/B ani ochrony recovery/ABL.

Gdy odmontowanie `/vendor` lub `/odm` jest rzeczywiście wywołane, najpierw
usuwane są montowania pod nimi, od najgłębszych. Adaptacja rozwiązania TWRP
celowo używa tylko zwykłego `umount2`: zajęty punkt powoduje błąd, a nie
pozorny sukces przez lazy/force unmount. Log zawiera listę punktów i zachowany
kod errno. Nie zatrzymujemy automatycznie usług kryptograficznych ani Wi-Fi;
proces nadal używający partycji może więc zablokować instalację legacy.
Montowania Wi-Fi pod `/tmp/aera-wifi` nie są usuwane przez tę funkcję.

Test hosta `test_install_mounts.py` wykonuje funkcje C++ z nałożonej łatki:
56 kombinacji typów ZIP, ustawienia i stanu vendor oraz scenariusze montowań
zagnieżdżonych, EBUSY, znikającego punktu, błędu odczytu tabeli i zachowania
errno. Kontroluje też, że `/vendor_dlkm`, `/data` i chroniony `/system` nie
zostają objęte nowym sprzątaniem. Test jest bramką workflow przed kompilacją.
To nie zastępuje próby instalacji konkretnego ROM-u na Neo8; zgłoszony ZIP
nie został udostępniony do sprawdzenia jego zawartości.

## update_engine_sideload — 21.10.2026

Instalacja OTA z recovery kończyła się na `CANNOT LINK EXECUTABLE
"/system/bin/update_engine_sideload": cannot locate symbol
...MetadataBuilder26GrowBlockDevicesToLiveSize...`. Obraz zawiera `liblp.so` z
referencyjnego TWRP 1.1, a AERA budowało własny `update_engine_sideload` z nowszych
źródeł, który wymaga nowszego `liblp`. Teraz do drzewa urządzenia trafia
`update_engine_sideload` z referencji (ta sama para co `liblp.so`; obsługuje
`--payload`, `--offset`, `--headers`, `--status_fd`, czyli to, czego używa AERA).

`ota_sideload.py` sprawdza spakowany obraz: plik musi być bajt w bajt binarką
referencyjną (gdyby build AERA wygrał kopiowanie, build kończy się błędem), a
wszystkie biblioteki `DT_NEEDED` oraz wszystkie silne symbole niezdefiniowane muszą
rozwiązywać się w bibliotekach obrazu. `test_ota_sideload.py` sprawdza ten checker
na obrazie referencyjnym i na zepsutych wariantach. Zapis OTA na telefonie nadal
wymaga próby na Neo8.

## regulatory.db — 21.10.2026

Log jądra: `platform regulatory.0: Direct firmware load for regulatory.db failed` i
`cfg80211: failed to load regulatory.db`. Bez bazy sterownik WCN7750 pracuje w świecie
domeny regulacyjnej `00`. Obraz dostaje teraz `regulatory.db` i `regulatory.db.p7s`
z pakietu `wireless-regdb` runnera w `/etc/firmware/` (ten katalog jest już w
`firmware_directories` ueventd). `regdb.py` sprawdza nagłówek `RGDB` i podpis DER;
`verify_runtime_image.py` sprawdza oba pliki w spakowanym obrazie. Czy jądro przyjmie
podpis, widać dopiero w `dmesg` na telefonie (brak linii `failed to load regulatory.db`).

## Wygaszanie ekranu i blokada — 24.10.2026

Donorowy `BoardConfig.mk` z TWRP ustawia `TW_NO_SCREEN_TIMEOUT := true`. W AERA ta flaga
zamienia `AeraScreenTimer` (`Blank`, `Wake`, `CheckTimeout`) w puste funkcje i ukrywa
ustawienie czasu wygaszania. Krótkie naciśnięcie zasilania przełączało więc UI na ekran
blokady, ale podświetlenie nigdy nie gasło. `prepare.py` usuwa tę flagę
(`screen_config.py`). `TW_NO_SCREEN_BLANK := true` zostaje: ekran gaśnie przez jasność 0
na `TW_BRIGHTNESS_PATH`, bez blankowania framebuffera. Po zmianie obowiązuje domyślny
czas wygaszania AERA (60 s), a jego zachowanie na panelu Neo8 wymaga próby na telefonie.
