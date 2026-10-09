# Jak to działa

Notatki etap po etapie: co powstało, dlaczego tak i o co mogą zapytać na rozmowie.
Pisane dla kogoś, kto zna TypeScript i Pythona, a Javy uczy się na tym projekcie.

---

## Etap 1 — szkielet i narzędzia

**Gradle** to w Javie odpowiednik `npm` + `package.json` w jednym: zależności i zadania
(`./gradlew check` ≈ `npm test`). `gradlew` to skrypt, który sam pobiera właściwą wersję
Gradle'a, więc nikt nie musi go instalować.

**Spring Boot** to framework webowy (jak NestJS albo FastAPI). Na razie prawie go nie
używamy — domena (etap 2) celowo jest bez niego.

Narzędzia testowe:

| Narzędzie | Odpowiednik z JS/Python | Po co |
|---|---|---|
| JUnit 6 + AssertJ | Vitest/pytest + `expect` | testy |
| JaCoCo | `c8` / `coverage.py` | pokrycie kodu |
| PIT | Stryker / `mutmut` | testy mutacyjne — „testy na testy" |
| ArchUnit | `eslint-plugin-boundaries` | reguły architektury jako test |

**Haczyk, który wyszedł w praniu:** jqwik (testy property-based) i silnik ArchUnit są
zbudowane pod JUnit 5, a Spring Boot 4 przeszedł na JUnit 6. Testy w ogóle się nie
uruchamiały (`NoSuchMethodError`). Rozwiązanie: ArchUnit jako sama biblioteka wywoływana
ze zwykłego testu, a zamiast jqwik — własne testy losowe ze stałym seedem.

---

## Etap 2 — arkusz zleceń

### Co to jest arkusz (order book)

Dwie posortowane listy: kto chce kupić i za ile (**bids**) oraz kto chce sprzedać
i za ile (**asks**). Gdy nowe zlecenie „sięga" drugiej strony — kupujący daje tyle,
ile ktoś chce za sprzedaż, albo więcej — powstaje transakcja.

### Struktury danych — i dlaczego takie

```
OrderBook
 ├─ bids: TreeMap<cena, Level>   (malejąco — najlepsza = najwyższa)
 ├─ asks: TreeMap<cena, Level>   (rosnąco — najlepsza = najniższa)
 └─ index: HashMap<id, RestingOrder>   (do anulowania w O(1))

Level (jedna cena)
 └─ queue: LinkedHashMap<id, RestingOrder>   (kolejność przyjścia = priorytet czasu)
```

- **`TreeMap`** to drzewo czerwono-czarne — mapa trzymana zawsze w kolejności kluczy.
  Najlepsza cena to `firstEntry()`, w O(log n). W JS najbliżej byłoby posortowanej tablicy,
  ale wstawienie do niej kosztuje O(n).
- **`LinkedHashMap`** pamięta kolejność wstawiania (jak `Map` w JS!), a do tego usuwa po
  kluczu w O(1). Zwykła lista też trzymałaby kolejność, ale anulowanie zlecenia ze środka
  kolejki kosztowałoby O(n).
- **`index`** pozwala anulować zlecenie po samym id, bez przeszukiwania arkusza.

### Najważniejsze decyzje

- **Ceny w groszach jako `long`.** `0.1 + 0.2 !== 0.3` — w JS też to znasz. Na giełdzie
  grosz różnicy to błąd rozliczenia.
- **Transakcja po cenie zlecenia czekającego (makera).** To ono było pierwsze i ono
  wyznaczyło cenę. Kupujący z limitem 105 zł płaci 100 zł, jeśli ktoś czekał ze sprzedażą po 100.
- **Id zleceń muszą rosnąć.** Id niesie informację o czasie przyjścia; arkusz odrzuca
  id mniejsze lub równe poprzedniemu, więc dwa zlecenia nie mogą mieć tego samego.
- **Klasa nie jest bezpieczna wątkowo — celowo.** Silnik (etap 3) obsłuży każdy arkusz
  z jednego wątku. Bez współdzielenia nie ma wyścigów i nie trzeba blokad.
- **`sealed interface BookEvent`** — jak unia typów w TS (`type Event = A | B | C`).
  `switch` po zdarzeniu, który zapomni o którymś wariancie, się nie skompiluje.
- **`record`** — niemutowalna klasa z gotowymi `equals`/`hashCode`/`toString`. Dzięki
  temu testy porównują całe zdarzenia: `containsExactly(new Trade(1, 2, BUY, 100, 10))`.

### Testy — dlaczego jest im można wierzyć

1. **Jednostkowe** — każda reguła osobno, nazwane zdaniem (`betterPriceWinsRegardlessOfArrival`).
2. **Różnicowe.** `NaiveOrderBook` w testach to druga implementacja arkusza — zwykła lista,
   sortowana przy każdym zleceniu. Wolna, ale jej poprawność widać gołym okiem.
   10 seedów × 3000 losowych zleceń i anulowań: oba arkusze muszą dać **identyczne zdarzenia**.
3. **Niezmienniki** po każdym kroku: arkusz się nie krzyżuje (najlepsze kupno < najlepsza
   sprzedaż) i zachowana jest ilość akcji: `2 × przehandlowane + w arkuszu + anulowane = złożone`.
4. **Mutacyjne (PIT).** Wynik: **64/64 mutantów zabitych, 100% pokrycia linii.**

### Co znalazł code review (i co poprawiono)

| Problem | Poprawka |
|---|---|
| Suma ilości na poziomie mogła się przepełnić (`Long.MAX_VALUE` + 1 → liczba ujemna) | limit `MAX_QUANTITY` w zleceniu + test |
| `Trade.orderId()` zwracało tylko id zlecenia przychodzącego (takera) — konsument zdarzeń przegapiłby realizację zlecenia czekającego | metoda usunięta z interfejsu; transakcja ma jawnie `makerOrderId` i `takerOrderId` |
| Id ≤ 0 odrzucane z mylącym komunikatem | walidacja w `OrderRequest` |
| `Level` modyfikował prywatną mapę `OrderBook` | `Level` dostaje callback (`index::remove`), nie mapę |
| Dwa przejścia drzewa na iterację pętli kojarzenia | jedno `firstEntry()` |

### Pytania z rozmowy

**Dlaczego `TreeMap`, a nie kolejka priorytetowa (`PriorityQueue`)?**
Kolejka daje tylko najlepszy element. Potrzebujemy też: zebrać wszystkie zlecenia po
jednej cenie, pokazać głębokość rynku (kilka najlepszych poziomów) i usunąć dowolny
poziom przy anulowaniu. `TreeMap` daje to wszystko w O(log n).

**Jaka jest złożoność złożenia zlecenia?**
O(log P) na wstawienie, gdzie P to liczba różnych cen, plus O(1) na każde skojarzone
zlecenie. Anulowanie: O(1) w indeksie + O(log P) na znalezienie poziomu.

**Skąd wiesz, że kojarzenie jest poprawne?**
Testy różnicowe: 30 000 losowych operacji porównanych z implementacją, której poprawność
widać od razu. Do tego PIT: każda zmiana typu `<=` na `<` w kodzie domeny wywraca jakiś test.

**Co z wielowątkowością?**
Arkusz jest celowo jednowątkowy. Etap 3: jeden wątek na instrument i kolejka poleceń
przed nim — ten sam model, którego używa LMAX. Zero blokad, deterministyczna kolejność.

---

## Etap 3 — silnik: wątek na instrument

### Problem

Arkusz z etapu 2 nie jest bezpieczny wątkowo. Serwer HTTP obsługuje wiele żądań naraz,
każde na innym wątku. Gdyby dwa wątki jednocześnie zmieniały `TreeMap`, drzewo może się
uszkodzić — i to nie teoretycznie: w ramach testu celowo wyłączyłem kolejkę, a zepsuty
silnik **zawiesił się w nieskończonej pętli** wewnątrz `TreeMap`.

### Rozwiązanie: jeden pisarz

```
wiele wątków ──► ArrayBlockingQueue ──► jeden wątek engine-CDR ──► OrderBook
```

To jak **event loop w Node.js**: wiele źródeł zdarzeń, jeden wątek, który je obsługuje
po kolei. Różnica: tu każda spółka ma *własną* pętlę, więc CDR i PKO idą równolegle
na różnych rdzeniach.

- **Zamiast blokady przy każdym zleceniu** — wątek, który jako jedyny dotyka arkusza.
  Blokady (`synchronized`) też by zadziałały, ale przy dużym ruchu wątki czekałyby na
  siebie, a kolejność obsługi zależałaby od planisty systemu.
- **`CompletableFuture`** to `Promise` z JS. Wołający dostaje go od razu, a silnik
  rozwiązuje go, gdy dojdzie do polecenia. `join()` ≈ `await`.
- **Ograniczona kolejka** (`ArrayBlockingQueue` o stałej pojemności). Gdy jest pełna,
  `offer()` zwraca `false` i odpowiadamy błędem od razu. To jest **backpressure**:
  lepiej odrzucić żądanie, niż zbierać je w pamięci, aż serwer padnie.

### Haczyki, które wyszły w review i testach

| Problem | Co by się stało | Poprawka |
|---|---|---|
| Wyjątek typu `Error` (np. `AssertionError` z odbiorcy zdarzeń) | wątek silnika umiera po cichu, wszystkie `join()` wiszą na zawsze | łapiemy `Throwable` w zadaniu i przy publikacji |
| `close()` wywołane z wątku silnika | `thread.join()` czeka na samego siebie — zakleszczenie | wyraźny `IllegalStateException` |
| Drugie równoległe `close()` | wracało od razu, choć silnik jeszcze pracował | każde wywołanie czeka na koniec wątku |
| Przerwanie (`interrupt`) wątku wołającego `close()` | STOP nie trafiał do kolejki, wątek silnika żył wiecznie | ponawiamy `put`, flagę przerwania przywracamy na koniec |
| Zegar rzucał wyjątek po zmianie arkusza | zlecenie w arkuszu, a klient dostaje błąd | czas odczytywany przed zmianą arkusza |
| Test współbieżności bez limitu czasu | zepsuty silnik zawiesiłby CI na godziny | `@Timeout(30)` — sprawdzone sabotażem |

### Jak testujemy współbieżność

Testy wielowątkowe są trudne, bo wynik zależy od przeplotu wątków. Sztuczka:

1. 8 wątków naraz wysyła po 2000 losowych zleceń i anulowań.
2. Silnik zapisuje dziennik: każde polecenie to jedna paczka zdarzeń z numerami kolejnymi.
3. Po wszystkim **odtwarzamy dziennik na świeżym arkuszu, w jednym wątku**.
4. Wynik musi być identyczny co do zdarzenia.

Jeśli silnik kiedykolwiek przeplótłby dwa polecenia, odtworzenie się rozjedzie. A to,
że z dziennika da się odtworzyć stan — to dokładnie **event sourcing**, który dojdzie w etapie 8.

Do tego testy cyklu życia **deterministycznie** zapychają kolejkę: odbiorca zdarzeń
zatrzymuje wątek silnika na `CountDownLatch`, dopóki test go nie zwolni. Bez `sleep()`
i bez liczenia na szczęście.

### Pytania z rozmowy

**Dlaczego nie `synchronized` na metodach arkusza?**
Zadziałałoby, ale: wątki czekałyby na siebie przy każdym zleceniu, kolejność zależałaby
od planisty systemu, a każda nowa metoda musiałaby pamiętać o blokadzie. Jeden wątek
na instrument daje jednoznaczną kolejność i zero blokad w samym kojarzeniu.

**Czy to w ogóle jest „bez blokad”?**
Kojarzenie — tak. Przekazanie polecenia do kolejki ma synchronizację
(`ArrayBlockingQueue` używa zamka w środku, a my krótkiego `synchronized` przy sprawdzeniu
„czy silnik przyjmuje”). LMAX Disruptor idzie krok dalej: bufor pierścieniowy
z operacjami atomowymi zamiast zamka. Uczciwa odpowiedź: *wąskie gardło nie ma blokad,
przekazanie ma — i przy tej skali to nie jest problem*.

**Co jeśli jedna spółka dostaje 100× więcej zleceń niż reszta?**
Jej wątek jest wąskim gardłem, a reszta działa normalnie — instrumenty są niezależne.
Pełna kolejka tej spółki zaczyna odrzucać zlecenia (backpressure), zamiast zabić cały serwer.

**Dlaczego id nadaje silnik, a nie np. `AtomicLong` w kontrolerze?**
Wątek A dostaje id 5, wątek B id 6, ale B pierwszy wstawia do kolejki. Arkusz zobaczy 6
przed 5 i odrzuci 5, bo id muszą rosnąć. Numeracja musi powstawać tam, gdzie powstaje kolejność.

**Co się dzieje z poleceniami w kolejce przy zamykaniu aplikacji?**
`close()` przestaje przyjmować nowe, wstawia znacznik STOP *za* czekającymi i czeka,
aż wątek je wszystkie wykona. Żadne przyjęte polecenie nie zostaje bez odpowiedzi.

## Etap 4a — REST API

### Co powstało

```
HTTP ──► OrderController ──► MatchingEngine.instrument("CDR").place(...) ──► CompletableFuture
           │  JSON ↔ rekordy                                                      │
           │  złotówki ↔ grosze (Prices)                         EngineCalls.await(future, 5s)
           └─ błędy → ApiExceptionHandler → ProblemDetail (RFC 9457)
```

- **`api/`** — kontroler, rekordy żądań i odpowiedzi, obsługa błędów. Zero logiki giełdowej.
- **`config/`** — `ExchangeProperties` (spółki, kolejka, limit czasu z `application.yml`)
  i `EngineConfig`, który składa silnik. Silnik dalej nie wie, że Spring istnieje.

Dla znających JS/TS: `@RestController` + `@PostMapping` to router z Expressa, rekord
`PlaceOrderRequest` z `@NotNull`/`@Positive` to schemat Zod, a `@RestControllerAdvice`
to middleware błędów `(err, req, res, next)`.

### Najważniejsze decyzje

| Decyzja | Dlaczego |
|---|---|
| Ceny w API jako `BigDecimal`, w JSON-ie jako tekst `"101.25"` | `double` nie umie zapisać 101.25 dokładnie; JS sparsowałby liczbę do `double` |
| Ułamek grosza → 400, a nie zaokrąglenie | giełda nie zgaduje, ile klient miał na myśli |
| Jackson w trybie ścisłym | domyślnie `"quantity": 10.9` → 10, a `"side": 1` → SELL (indeks enuma) |
| Walidacja **przed** kolejką silnika | złe zlecenie nie zajmuje miejsca poprawnym i nie zużywa id |
| 503 vs 504 | 503: pełna kolejka, zlecenie na pewno nie weszło. 504: czekało za długo, **może się jeszcze wykonać** |
| Wątki wirtualne (`spring.threads.virtual.enabled`) | wątek żądania czeka na silnik; wirtualny czeka prawie za darmo |
| Własny `InvalidOrderException` zamiast łapania `IllegalArgumentException` | IAE rzucone głęboko w domenie to błąd serwera — nie może wyjść jako 400 „twoja wina” |

### Co znalazł code review (i co poprawiono)

| Problem | Poprawka |
|---|---|
| `"quantity": 10.9` po cichu kupowało 10 akcji | `accept-float-as-int: false` + test (sprawdzony sabotażem) |
| `"side": 1` dawało SELL | `fail-on-numbers-for-enums: true` |
| każdy `IllegalArgumentException` → 400 | osobny wyjątek dla błędów klienta |
| `request-timeout: 0s` przechodził walidację, potem każde zlecenie → 504 | `@DurationMin(millis = 1)` + test startu |
| zła treść + nieznana spółka dawało 400 | najpierw zasób (404), potem treść (400) |
| 300 ms limitu w testach mogło migać na wolnym CI | 2 s |

Świadomie zostawione:
- **Odczyt arkusza idzie przez kolejkę silnika.** Daje obraz spójny co do zlecenia, ale
  zajmuje miejsce w kolejce. Przy dużym ruchu odczyty przejmie WebSocket z publikowanym
  obrazem (etap 6).
- **Po 504 klient nie wie, czy zlecenie weszło.** Prawdziwe rozwiązanie to id nadawane
  przez klienta (`clientOrderId`) i idempotencja — wróci przy broker-ledger.

### Pytania z rozmowy

**Dlaczego nie `double` na ceny, skoro to tylko API?**
`0.1 + 0.2 = 0.30000000000000004`. `BigDecimal` trzyma liczbę dziesiętnie, więc „101.25”
to dokładnie 101.25. W środku i tak liczymy na groszach w `long` — szybciej niż `BigDecimal`.

**Czym się różni 503 od 504 i czemu to ważne?**
Przy 503 wiemy, że zlecenie nie weszło — klient może ponowić. Przy 504 polecenie siedzi
w kolejce i może się wykonać za sekundę. Ponowienie w ciemno = podwójne zlecenie.
To przykład, że kod błędu to kontrakt, a nie ozdoba.

**Jak testujesz 504 i 503 bez `sleep()`?**
Odbiorca zdarzeń w teście zatrzymuje wątek silnika na `CountDownLatch`. Pierwsze zlecenie
czeka na niego (504), drugie zajmuje jedyne miejsce w kolejce o pojemności 1 (504),
trzecie nie ma gdzie wejść (503). Na koniec testu zatrzask się otwiera.

**`@WebMvcTest` czy `@SpringBootTest`?**
`@WebMvcTest` stawia tylko warstwę web — kontrolery, Jackson, walidację — bez serwera i bazy.
Silnik podajemy prawdziwy, bo jest szybki i czysty; mock sprawdzałby tylko, czy kontroler
woła metodę, a nie czy JSON zgadza się z tym, co naprawdę dzieje się w arkuszu.
