# Brak pytania o PIN — kolejność przygotowania metadanych

Użytkownik potwierdził, że TWRP Neo8 ze wskazanego repozytorium odszyfrowuje dane
na jego telefonie. W OrangeFox przycisk jest już widoczny, ale PIN nie jest
wyświetlany. Nie otrzymano jeszcze logu z tej konkretnej próby w najnowszym obrazie,
więc przyczyna błędu na telefonie nie jest ostatecznie potwierdzona.

## Znaleziona różnica i poprawka

Nasz `neo8-runtime.patch` dodawał bezwarunkowe wywołanie
`PrepareNeo8StockMetadataEnvironment()` przed pierwszą operacją metadanych.
To wywołanie restartowało KeyMint i keystore2 oraz wymagało ich pełnej gotowości.
Jego niepowodzenie kończyło przygotowanie bez choćby próby otwarcia metadanych.
Może to blokować wejście do PIN-u, szczególnie gdy usługi potrzebują dostępu do
jeszcze nieotwartej pamięci. Działający wariant TWRP nie ma tego warunku wstępnego.

Usunięto ten dodany warunek oraz wymuszanie środowiska `stock` przed pierwszą
próbą. Sekcja wyboru środowiska metadanych jest teraz identyczna z
`patches/common/files/bootable/recovery/partitionmanager.cpp` z repozytorium
MissMyTime, commit `d4b65c0e964942cf1b09ead3ee5f6d5e8649d17e`:

1. Próba odszyfrowania metadanych z aktualnym środowiskiem.
2. Dopiero po niepowodzeniu — dozwolona przez konfigurację próba z wartościami
   systemu producenta, jeśli odpowiednia funkcja jest dostępna.
3. Zapis wybranego środowiska po powodzeniu lub obsługa nieudanej próby zgodnie
   z kodem referencyjnym.

Przygotowanie nadal uruchamia wyłącznie ręczna akcja GUI. Pozostają sprawdzenie
istniejącego klucza metadanych, wyłączenie kasowania kluczy, osłony zapisu kluczy,
kontrola zatrzymania usług przy zmianie środowiska i walidacja właściwości stock.
Zachowano poprawki dotyku, rejestracji pamięci, logd i manifestów.

## Testy i ograniczenia

Test wykonuje rzeczywistą sekcję wyboru środowiska z atrapami usług w 64
kombinacjach: powodzenie pierwszej próby, dopuszczenie ponowienia, brak funkcji,
nieudana zmiana środowiska i nieudana druga próba. Pierwsza próba nie zależy od
obecności ani powodzenia funkcji restartującej usługi. Sprawdzana jest także
obecność ochrony istniejącego klucza przed wywołaniem fscrypt.

Przeszły testy ręcznego przygotowania, inicjalizacji pamięci, dotyku i zachowania
montowań. Pełna budowa również wykonuje test kolejności na zintegrowanych źródłach.
Porównanie z przypiętym plikiem referencyjnym potwierdziło identyczność sekcji
wyboru środowiska. Testy hosta nie dowodzą odszyfrowania danych na telefonie;
nowy obraz wymaga kompilacji, kontroli ramdisku i próby użytkownika.
