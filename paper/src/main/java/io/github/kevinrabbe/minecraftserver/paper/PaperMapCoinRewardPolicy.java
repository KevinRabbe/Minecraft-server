package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/** Bounded balance policy for the successful-Map Coin faucet. */
final class PaperMapCoinRewardPolicy {
    private static final int SCHEMA_VERSION = 1;

    private final long baseCoinMinor;
    private final long coinMinorPerDifficulty;
    private final long maxCoinMinor;

    PaperMapCoinRewardPolicy(long baseCoinMinor, long coinMinorPerDifficulty, long maxCoinMinor) {
        if (baseCoinMinor <= 0) {
            throw new IllegalArgumentException("baseCoinMinor must be > 0");
        }
        if (coinMinorPerDifficulty < 0) {
            throw new IllegalArgumentException("coinMinorPerDifficulty must be >= 0");
        }
        if (maxCoinMinor < baseCoinMinor) {
            throw new IllegalArgumentException("maxCoinMinor must be >= baseCoinMinor");
        }
        this.baseCoinMinor = baseCoinMinor;
        this.coinMinorPerDifficulty = coinMinorPerDifficulty;
        this.maxCoinMinor = maxCoinMinor;
    }

    static PaperMapCoinRewardPolicy loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperMapCoinRewardPolicy.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Map Coin reward resource does not exist: " + resourcePath);
        }
        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        try (input) {
            RawConfig raw = mapper.readValue(input, RawConfig.class);
            if (raw == null) {
                throw new IllegalStateException("Map Coin reward content must not be null");
            }
            if (raw.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "Unsupported Map Coin reward schema_version " + raw.schemaVersion()
                                + "; expected " + SCHEMA_VERSION
                );
            }
            return new PaperMapCoinRewardPolicy(
                    raw.baseCoinMinor(),
                    raw.coinMinorPerDifficulty(),
                    raw.maxCoinMinor()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Map Coin reward JSON: " + resourcePath, exception);
        }
    }

    long amountMinor(int difficulty) {
        if (difficulty < 1) {
            throw new IllegalArgumentException("difficulty must be >= 1");
        }
        long extraDifficulty = (long) difficulty - 1L;
        if (coinMinorPerDifficulty == 0L || extraDifficulty == 0L) {
            return baseCoinMinor;
        }
        long headroom = maxCoinMinor - baseCoinMinor;
        if (extraDifficulty > headroom / coinMinorPerDifficulty) {
            return maxCoinMinor;
        }
        return baseCoinMinor + extraDifficulty * coinMinorPerDifficulty;
    }

    private record RawConfig(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("base_coin_minor") long baseCoinMinor,
            @JsonProperty("coin_minor_per_difficulty") long coinMinorPerDifficulty,
            @JsonProperty("max_coin_minor") long maxCoinMinor
    ) { }
}
