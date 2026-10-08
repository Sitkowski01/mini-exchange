package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import org.junit.jupiter.api.Test;

import static io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled.Reason.REQUESTED;
import static io.github.sitkowski01.exchange.domain.OrderRequest.limit;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static io.github.sitkowski01.exchange.domain.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;

class CancelTest {

    private final OrderBook book = new OrderBook();

    @Test
    void removesRestingOrder() {
        book.submit(limit(1, BUY, 100, 10));

        assertThat(book.cancel(1)).contains(new OrderCancelled(1, 10, REQUESTED));
        assertThat(book.bestBid()).isEmpty();
        assertThat(book.depth(BUY, 5)).isEmpty();
    }

    @Test
    void reportsOnlyUnfilledRemainder() {
        book.submit(limit(1, SELL, 100, 10));
        book.submit(limit(2, BUY, 100, 4));

        assertThat(book.cancel(1)).contains(new OrderCancelled(1, 6, REQUESTED));
    }

    @Test
    void unknownOrFinishedOrderGivesNothing() {
        book.submit(limit(1, SELL, 100, 5));
        book.submit(limit(2, BUY, 100, 5));

        assertThat(book.cancel(1)).isEmpty();
        assertThat(book.cancel(2)).isEmpty();
        assertThat(book.cancel(42)).isEmpty();
    }

    @Test
    void secondCancelGivesNothing() {
        book.submit(limit(1, BUY, 100, 5));
        book.cancel(1);

        assertThat(book.cancel(1)).isEmpty();
    }

    @Test
    void keepsOtherOrdersAtSameLevelInTimeOrder() {
        book.submit(limit(1, BUY, 100, 1));
        book.submit(limit(2, BUY, 100, 2));
        book.submit(limit(3, BUY, 100, 3));

        book.cancel(2);

        assertThat(book.depth(BUY, 1)).containsExactly(new PriceLevel(100, 4, 2));
        assertThat(book.submit(limit(4, SELL, 100, 4)))
                .containsExactly(new Trade(1, 4, SELL, 100, 1), new Trade(3, 4, SELL, 100, 3));
    }

    @Test
    void cancelledOrderIsNeverMatched() {
        book.submit(limit(1, SELL, 100, 5));
        book.cancel(1);

        assertThat(book.submit(limit(2, BUY, 100, 5)))
                .containsExactly(new OrderRested(2, BUY, 100, 5));
    }

    @Test
    void emptyLevelDisappearsAndNextPriceBecomesBest() {
        book.submit(limit(1, SELL, 100, 5));
        book.submit(limit(2, SELL, 101, 5));

        book.cancel(1);

        assertThat(book.bestAsk()).hasValue(101);
    }
}
