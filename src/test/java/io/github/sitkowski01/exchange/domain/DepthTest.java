package io.github.sitkowski01.exchange.domain;

import org.junit.jupiter.api.Test;

import static io.github.sitkowski01.exchange.domain.OrderRequest.limit;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static io.github.sitkowski01.exchange.domain.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DepthTest {

    private final OrderBook book = new OrderBook();

    @Test
    void aggregatesOrdersPerPriceFromBestDown() {
        book.submit(limit(1, BUY, 99, 1));
        book.submit(limit(2, BUY, 100, 2));
        book.submit(limit(3, BUY, 100, 3));
        book.submit(limit(4, BUY, 98, 4));
        book.submit(limit(5, SELL, 102, 5));
        book.submit(limit(6, SELL, 101, 6));

        assertThat(book.depth(BUY, 10)).containsExactly(
                new PriceLevel(100, 5, 2), new PriceLevel(99, 1, 1), new PriceLevel(98, 4, 1));
        assertThat(book.depth(SELL, 10)).containsExactly(
                new PriceLevel(101, 6, 1), new PriceLevel(102, 5, 1));
    }

    @Test
    void returnsAtMostRequestedLevels() {
        book.submit(limit(1, SELL, 101, 1));
        book.submit(limit(2, SELL, 102, 1));
        book.submit(limit(3, SELL, 103, 1));

        assertThat(book.depth(SELL, 2)).extracting(PriceLevel::price).containsExactly(101L, 102L);
        assertThat(book.depth(SELL, 0)).isEmpty();
    }

    @Test
    void sumsLargeOrdersWithoutOverflow() {
        book.submit(limit(1, BUY, 100, OrderRequest.MAX_QUANTITY));
        book.submit(limit(2, BUY, 100, OrderRequest.MAX_QUANTITY));

        assertThat(book.depth(BUY, 1)).containsExactly(new PriceLevel(100, 2 * OrderRequest.MAX_QUANTITY, 2));
    }

    @Test
    void rejectsNegativeLevels() {
        assertThatIllegalArgumentException().isThrownBy(() -> book.depth(BUY, -1));
    }
}
