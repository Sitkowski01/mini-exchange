package io.github.sitkowski01.exchange.domain;

/** Jeden poziom cenowy arkusza w widoku zagregowanym: ile akcji i ile zlecen czeka po tej cenie. */
public record PriceLevel(long price, long quantity, int orders) {
}
