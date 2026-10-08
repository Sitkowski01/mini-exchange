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
