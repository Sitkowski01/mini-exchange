package io.github.sitkowski01.exchange.domain;

/**
 * Wszystko, co moze sie stac w arkuszu w odpowiedzi na zlecenie albo anulowanie.
 *
 * <p>Interfejs jest sealed: kompilator zna pelna liste wariantow, wiec {@code switch}
 * po zdarzeniu, ktory nie obsluguje ktoregos z nich, sie nie skompiluje.
 */
public sealed interface BookEvent {

    /** Reszta zlecenia LIMIT, ktorej nie dalo sie skojarzyc, czeka w arkuszu. */
    record OrderRested(long orderId, Side side, long price, long quantity) implements BookEvent {
    }

    /**
     * Transakcja. Cena zawsze pochodzi od zlecenia, ktore czekalo w arkuszu (maker):
     * kupujacy gotowy zaplacic 105 zl trafia na sprzedaz po 100 zl i placi 100 zl.
     */
    record Trade(long makerOrderId, long takerOrderId, Side takerSide, long price, long quantity)
            implements BookEvent {
    }

    record OrderCancelled(long orderId, long quantity, Reason reason) implements BookEvent {

        public enum Reason {
            /** Uzytkownik anulowal zlecenie czekajace w arkuszu. */
            REQUESTED,
            /** Zlecenie MARKET nie znalazlo wystarczajacej plynnosci; reszta przepada. */
            NO_LIQUIDITY
        }
    }
}
