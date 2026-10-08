package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import org.junit.jupiter.api.Test;

import static io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled.Reason.NO_LIQUIDITY;
import static io.github.sitkowski01.exchange.domain.OrderRequest.limit;
import static io.github.sitkowski01.exchange.domain.OrderRequest.market;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static io.github.sitkowski01.exchange.domain.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;

class MarketOrderTest {

    private final OrderBook book = new OrderBook();

    @Test
    void takesBestPricesInOrder() {
        book.submit(limit(1, SELL, 101, 3));
        book.submit(limit(2, SELL, 100, 3));

        assertThat(book.submit(market(3, BUY, 5)))
                .containsExactly(new Trade(2, 3, BUY, 100, 3), new Trade(1, 3, BUY, 101, 2));
        assertThat(book.depth(SELL, 5)).containsExactly(new PriceLevel(101, 1, 1));
    }

    @Test
    void sellTakesHighestBids() {
        book.submit(limit(1, BUY, 99, 3));
        book.submit(limit(2, BUY, 100, 3));

        assertThat(book.submit(market(3, SELL, 4)))
                .containsExactly(new Trade(2, 3, SELL, 100, 3), new Trade(1, 3, SELL, 99, 1));
    }

    @Test
    void cancelsWhatCannotBeFilled() {
        book.submit(limit(1, SELL, 100, 3));

        assertThat(book.submit(market(2, BUY, 10)))
                .containsExactly(new Trade(1, 2, BUY, 100, 3), new OrderCancelled(2, 7, NO_LIQUIDITY));
    }

    @Test
    void neverRestsInBook() {
        assertThat(book.submit(market(1, BUY, 10)))
                .containsExactly(new OrderCancelled(1, 10, NO_LIQUIDITY));
        assertThat(book.bestBid()).isEmpty();
        assertThat(book.cancel(1)).isEmpty();
    }

    @Test
    void ignoresOwnSideOfBook() {
        book.submit(limit(1, BUY, 100, 10));

        assertThat(book.submit(market(2, BUY, 5)))
                .containsExactly(new OrderCancelled(2, 5, NO_LIQUIDITY));
        assertThat(book.depth(BUY, 5)).containsExactly(new PriceLevel(100, 10, 1));
    }
}
