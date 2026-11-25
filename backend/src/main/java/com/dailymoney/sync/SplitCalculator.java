package com.dailymoney.sync;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.dailymoney.shared.web.ApiException;

/**
 * Largest-remainder split (LLD §8.1): shares always add up to the total, and leftover minor units go
 * to the largest fractions, ties broken by user id. The app has the same function for its live preview;
 * docs/split-test-vectors.json keeps the two identical.
 */
public final class SplitCalculator {

    public enum Method { EQUAL, PERCENT, AMOUNT, SHARES }

    public record Entry(UUID userId, BigDecimal value) {
    }

    public static final int MAX_PEOPLE = 50;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SplitCalculator() {
    }

    public static List<Long> split(long totalMinor, Method method, List<Entry> entries) {
        if (method == null) throw invalid("Choose how to split");
        if (entries == null || entries.isEmpty()) throw invalid("Choose who shares the cost");
        if (entries.size() > MAX_PEOPLE) throw invalid("Too many people in the split");
        if (new HashSet<>(entries.stream().map(Entry::userId).toList()).size() != entries.size() || entries.stream().anyMatch(e -> e.userId() == null)) {
            throw invalid("Each person can appear once");
        }
        if (method != Method.EQUAL && entries.stream().anyMatch(e -> e.value() == null || e.value().signum() <= 0)) {
            throw invalid("Every value must be more than zero");
        }
        if (method == Method.AMOUNT) {
            List<Long> amounts = new ArrayList<>();
            for (Entry e : entries) {
                try {
                    amounts.add(e.value().longValueExact());
                } catch (ArithmeticException ex) {
                    throw invalid("Amounts must be whole minor units");
                }
            }
            if (amounts.stream().mapToLong(Long::longValue).sum() != totalMinor) throw invalid("The amounts must add up to the total");
            return amounts;
        }
        List<BigDecimal> weights = entries.stream().map(e -> method == Method.EQUAL ? BigDecimal.ONE : e.value()).toList();
        if (method == Method.PERCENT && weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(HUNDRED) != 0) {
            throw invalid("The percentages must add up to 100");
        }
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = BigDecimal.valueOf(totalMinor);
        List<BigDecimal> exact = weights.stream().map(w -> total.multiply(w).divide(sum, 10, RoundingMode.DOWN)).toList();
        long[] result = exact.stream().mapToLong(x -> x.setScale(0, RoundingMode.DOWN).longValueExact()).toArray();
        long remainder = totalMinor - java.util.Arrays.stream(result).sum();
        List<Integer> order = IntStream.range(0, entries.size()).boxed()
                .sorted(Comparator.<Integer, BigDecimal>comparing(i -> exact.get(i).subtract(BigDecimal.valueOf(result[i]))).reversed()
                        .thenComparing(i -> entries.get(i).userId().toString()))
                .toList();
        for (int i : order) {
            if (remainder == 0) break;
            result[i]++;
            remainder--;
        }
        return java.util.Arrays.stream(result).boxed().toList();
    }

    private static ApiException invalid(String detail) {
        return ApiException.rule("INVALID_SPLIT", detail);
    }
}
