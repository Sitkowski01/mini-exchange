package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Wzorzec do testow roznicowych: arkusz napisany tak prosto, jak sie da,
 * bez zadnej optymalizacji. Przy kazdym zleceniu sortuje cala liste.
 * Wolny, ale poprawnosc widac golym okiem -- i wlasnie dlatego sluzy za wyrocznie.
 */
final class NaiveOrderBook {

    private static final class Resting {
        final long id;
        final Side side;
        final long price;
        long remaining;

        Resting(long id, Side side, long price, long remaining) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }
    }

    private final List<Resting> orders = new ArrayList<>();

    List<BookEvent> submit(OrderRequest taker) {
        List<BookEvent> events = new ArrayList<>();
        Comparator<Resting> byPrice = Comparator.comparingLong(o -> o.price);
        Comparator<Resting> priority = (taker.side() == Side.BUY ? byPrice : byPrice.reversed())
                .thenComparingLong(o -> o.id);
        List<Resting> candidates = orders.stream()
                .filter(o -> o.side != taker.side())
                .filter(o -> taker.type() == OrderType.MARKET
                        || (taker.side() == Side.BUY ? o.price <= taker.price() : o.price >= taker.price()))
                .sorted(priority)
                .toList();

        long left = taker.quantity();
        for (Resting maker : candidates) {
            if (left == 0) {
                break;
            }
            long qty = Math.min(left, maker.remaining);
            maker.remaining -= qty;
            left -= qty;
            events.add(new Trade(maker.id, taker.id(), taker.side(), maker.price, qty));
        }
        orders.removeIf(o -> o.remaining == 0);

        if (left > 0 && taker.type() == OrderType.MARKET) {
            events.add(new OrderCancelled(taker.id(), left, OrderCancelled.Reason.NO_LIQUIDITY));
        } else if (left > 0) {
            orders.add(new Resting(taker.id(), taker.side(), taker.price(), left));
            events.add(new OrderRested(taker.id(), taker.side(), taker.price(), left));
        }
        return events;
    }

    Optional<OrderCancelled> cancel(long id) {
        for (Resting o : orders) {
            if (o.id == id) {
                orders.remove(o);
                return Optional.of(new OrderCancelled(id, o.remaining, OrderCancelled.Reason.REQUESTED));
            }
        }
        return Optional.empty();
    }
}
