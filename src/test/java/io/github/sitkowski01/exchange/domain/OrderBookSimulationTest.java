package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tysiace losowych zlecen, po kazdym sprawdzenie:
 * <ol>
 *   <li>arkusz daje dokladnie te same zdarzenia co {@link NaiveOrderBook},</li>
 *   <li>niezmienniki rynku, ktore musza byc prawdziwe zawsze.</li>
 * </ol>
 * Seed jest staly i widoczny w nazwie przypadku, wiec kazda porazke da sie odtworzyc.
 * Waski zakres cen (95-105) jest celowy: zlecenia czesto sie krzyzuja i kolejki rosna.
 */
class OrderBookSimulationTest {

    private static final int STEPS = 3_000;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 7, 42, 1337, 2026, 31337, 271828, 314159})
    void behavesLikeNaiveBookAndKeepsInvariants(long seed) {
        Random random = new Random(seed);
        OrderBook book = new OrderBook();
        NaiveOrderBook oracle = new NaiveOrderBook();
        Ledger ledger = new Ledger();

        for (long id = 1; id <= STEPS; id++) {
            if (id > 1 && random.nextInt(10) < 2) {
                long target = 1 + random.nextLong(id - 1);
                Optional<OrderCancelled> actual = book.cancel(target);
                assertThat(actual).as("cancel %d, step %d", target, id).isEqualTo(oracle.cancel(target));
                actual.ifPresent(ledger::record);
            } else {
                OrderRequest order = randomOrder(random, id);
                List<BookEvent> events = book.submit(order);
                assertThat(events).as("step %d: %s", id, order).isEqualTo(oracle.submit(order));
                ledger.submitted(order);
                events.forEach(e -> ledger.check(order, e));
                events.forEach(ledger::record);
            }
            assertInvariants(book, ledger);
        }
        assertThat(ledger.traded).as("simulation should actually trade").isPositive();
    }

    private static OrderRequest randomOrder(Random random, long id) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = 1 + random.nextInt(20);
        return random.nextInt(10) == 0
                ? OrderRequest.market(id, side, quantity)
                : OrderRequest.limit(id, side, 95 + random.nextInt(11), quantity);
    }

    private static void assertInvariants(OrderBook book, Ledger ledger) {
        if (book.bestBid().isPresent() && book.bestAsk().isPresent()) {
            assertThat(book.bestBid().getAsLong()).as("book crossed").isLessThan(book.bestAsk().getAsLong());
        }
        List<PriceLevel> bids = book.depth(Side.BUY, Integer.MAX_VALUE);
        List<PriceLevel> asks = book.depth(Side.SELL, Integer.MAX_VALUE);
        assertThat(bids).extracting(PriceLevel::price).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(asks).extracting(PriceLevel::price).isSorted();

        long resting = 0;
        for (PriceLevel level : bids) {
            assertThat(level.quantity()).isPositive();
            resting += level.quantity();
        }
        for (PriceLevel level : asks) {
            assertThat(level.quantity()).isPositive();
            resting += level.quantity();
        }
        // Kazda akcja ze zlecenia albo przeszla w transakcji (liczona po obu stronach),
        // albo czeka w arkuszu, albo zostala anulowana. Nic nie znika i nic sie nie pojawia.
        assertThat(2 * ledger.traded + resting + ledger.cancelled).isEqualTo(ledger.submitted);
    }

    private static final class Ledger {
        long submitted;
        long traded;
        long cancelled;
        final Map<Long, Long> restedPrices = new HashMap<>();

        void submitted(OrderRequest order) {
            submitted += order.quantity();
        }

        void check(OrderRequest taker, BookEvent event) {
            if (event instanceof Trade trade) {
                assertThat(trade.quantity()).isPositive();
                assertThat(trade.price()).as("trade at price of resting order")
                        .isEqualTo(restedPrices.get(trade.makerOrderId()));
                if (taker.type() == OrderType.LIMIT) {
                    boolean withinLimit = taker.side() == Side.BUY
                            ? trade.price() <= taker.price()
                            : trade.price() >= taker.price();
                    assertThat(withinLimit).as("%s respects taker limit %d", trade, taker.price()).isTrue();
                }
            }
        }

        void record(BookEvent event) {
            switch (event) {
                case Trade t -> traded += t.quantity();
                case OrderCancelled c -> cancelled += c.quantity();
                case OrderRested r -> restedPrices.put(r.orderId(), r.price());
            }
        }
    }
}
