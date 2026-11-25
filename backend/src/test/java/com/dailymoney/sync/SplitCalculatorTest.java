package com.dailymoney.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.dailymoney.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs docs/split-test-vectors.json, the same file the app's tests use. */
class SplitCalculatorTest {

    @TestFactory
    Stream<DynamicTest> sharedVectors() {
        JsonNode root = JsonMapper.builder().build().readTree(Path.of("..", "docs", "split-test-vectors.json").toFile());
        List<DynamicTest> tests = new ArrayList<>();
        root.get("cases").forEach(c -> tests.add(DynamicTest.dynamicTest(c.get("name").asString(), () -> {
            List<SplitCalculator.Entry> entries = new ArrayList<>();
            c.get("entries").forEach(e -> entries.add(new SplitCalculator.Entry(UUID.fromString(e.get(0).asString()), new BigDecimal(e.get(1).asString()))));
            SplitCalculator.Method method = SplitCalculator.Method.valueOf(c.get("method").asString());
            long total = c.get("total").asLong();
            if (c.has("error")) {
                assertThatThrownBy(() -> SplitCalculator.split(total, method, entries))
                        .isInstanceOf(ApiException.class)
                        .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_SPLIT"));
            } else {
                List<Long> expected = new ArrayList<>();
                c.get("shares").forEach(x -> expected.add(x.asLong()));
                List<Long> shares = SplitCalculator.split(total, method, entries);
                assertThat(shares).isEqualTo(expected);
                assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
            }
        })));
        return tests.stream();
    }
}
