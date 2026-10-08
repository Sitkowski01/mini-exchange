package io.github.sitkowski01.exchange.domain;

/** Zlecenie czekajace w arkuszu. Mutowalne, bo kazda czesciowa realizacja zmniejsza reszte. */
final class RestingOrder {

    final long id;
    final Side side;
    final long price;
    long remaining;

    RestingOrder(long id, Side side, long price, long remaining) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = remaining;
    }
}
