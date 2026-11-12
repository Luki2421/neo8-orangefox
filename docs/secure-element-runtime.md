# Secure Element i StrongBox z obrazu wzorcowego (jak w AERA)

Zgłoszenie z 12.11.2026: w AERA odszyfrowanie danych działa, w OrangeFox z tej
samej daty nie. Logu z telefonu dla OrangeFox nie mamy; przyczyna poniżej wynika
z porównania źródeł obu gałęzi.

Obie gałęzie używają tego samego drzewa urządzenia (`d4b65c0` i tag 1.1 mają
identyczną zawartość `device/realme/RE6402L1`). AERA dodatkowo bierze pliki z
opublikowanego obrazu TWRP 1.1, których w drzewie nie ma. OrangeFox ich nie brał,
więc w ramdisku brakowało 60 z 61 plików pakietu Secure Element: skryptu
`neo8-secure-element-launcher.sh`, 11 prywatnych bibliotek, 7 bibliotek
transportu eSE/NFC, konfiguracji TA i 40 fragmentów TA. W AERA ten sam brak
(kompilacja 8) dawał błąd Weaver `getConfig` i oczekiwanie OMAPI na `eSE1`;
kompilacja 9 z pakietem została potwierdzona na telefonie.

`scripts/neo8_reference_runtime.py` po konfiguracji usług OrangeFox:

- przywraca 61 plików pakietu Secure Element (`secure_element_bundle.py` bez zmian
  względem AERA),
- zastępuje 11 plików krypto/Weaver/eSE/NFC wersjami z obrazu wzorcowego,
- dodaje program StrongBox z obrazu wzorcowego w `/system/bin` z prywatnymi
  bibliotekami w `/system/lib64/neo8_strongbox` i launcherem zapisującym
  `/tmp/neo8-strongbox.log`.

Nie są brane z obrazu wzorcowego: ELF recovery, ogólne biblioteki systemowe,
init, fstab, konfiguracja dotyku ani pliki, które OrangeFox ustawia sam
(`qseecomd.rc`, `ta_config.json`, `prepdecrypt.sh`, wybór fragmentów VINTF).
To zakres węższy niż w AERA, która nadpisuje 107 plików drzewa.

Obraz wzorcowy jest pobierany w CI i sprawdzany po SHA-256 przed i po poprawce
nullguard. Test hosta (21 przypadków) działa na prawdziwym drzewie i prawdziwym
obrazie. Po kompilacji gotowy ramdisk jest porównywany z obrazem wzorcowym;
brak biblioteki systemowej dla StrongBox jest raportowany w
`reference-runtime.json`, ale nie odrzuca obrazu.

Nie sprawdzono na telefonie: startu usług, komunikacji z eSE ani odszyfrowania.
Po próbie przydatne są `/tmp/recovery.log`,
`/tmp/neo8-secure-element-launcher.log` i `/tmp/neo8-strongbox.log`.
