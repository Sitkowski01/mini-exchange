package io.github.sitkowski01.exchange.domain;

/**
 * LIMIT ma cene graniczna i to, czego nie da sie od razu skojarzyc, czeka w arkuszu.
 * MARKET bierze to, co jest w arkuszu, a reszte anuluje -- nigdy w nim nie zostaje.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
