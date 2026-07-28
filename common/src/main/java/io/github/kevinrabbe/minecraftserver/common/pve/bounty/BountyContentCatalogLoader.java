package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict JSON loader for version-controlled bounty tiers, eligibility and fixed launch rewards. */
public final class BountyContentCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public BountyContentCatalog load(
            Path path,
            ItemCatalog itemCatalog,
            ResourceSourceCatalog resourceSources
    ) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString(), itemCatalog, resourceSources);
        } catch (IOException exception) {
            throw new BountyException("Could not read bounty content: " + path, exception);
        }
    }

    public BountyContentCatalog loadResource(
            String resourcePath,
            ItemCatalog itemCatalog,
            ResourceSourceCatalog resourceSources
    ) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = BountyContentCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new BountyException("Bounty content resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath, itemCatalog, resourceSources);
        } catch (IOException exception) {
            throw new BountyException("Could not close bounty content resource: " + resourcePath, exception);
        }
    }

    BountyContentCatalog load(
            InputStream input,
            String sourceDescription,
            ItemCatalog itemCatalog,
            ResourceSourceCatalog resourceSources
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(resourceSources, "resourceSources");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();

        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new BountyException("Invalid bounty JSON in " + source, exception);
        }
        if (raw == null || raw.tiers() == null) {
            throw new BountyException("Bounty content must contain a tiers array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new BountyException(
                    "Unsupported bounty schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<BountyContentCatalog.ConfiguredTier> configured = new ArrayList<>(raw.tiers().size());
        for (int index = 0; index < raw.tiers().size(); index++) {
            RawTier value = raw.tiers().get(index);
            if (value == null) {
                throw new BountyException("tiers[" + index + "] must not be null in " + source);
            }
            try {
                List<String> eligibleSources = Objects.requireNonNull(
                        value.eligibleSourceIds(),
                        "eligible_source_ids must not be null"
                );
                for (String sourceId : eligibleSources) {
                    resourceSources.require(sourceId);
                }

                Map<String, Long> rewards = Objects.requireNonNull(
                        value.fixedRewards(),
                        "fixed_rewards must not be null"
                );
                for (String definitionId : rewards.keySet()) {
                    ItemDefinition item = itemCatalog.require(definitionId);
                    if (item.identityKind() != ItemIdentityKind.COMMODITY) {
                        throw new BountyException("Bounty reward must be COMMODITY: " + definitionId);
                    }
                }

                BountyFamilyId familyId = new BountyFamilyId(value.familyId());
                BountyTierDefinition definition = new BountyTierDefinition(
                        familyId,
                        value.tier(),
                        value.contractFeeMinor(),
                        value.requiredEligibleKills(),
                        value.bossDefinitionId(),
                        rewards.keySet().stream().sorted().toList()
                );
                configured.add(new BountyContentCatalog.ConfiguredTier(
                        definition,
                        eligibleSources,
                        rewards
                ));
            } catch (RuntimeException exception) {
                throw new BountyException(
                        "Invalid bounty tier at tiers[" + index + "] in " + source + ": " + exception.getMessage(),
                        exception
                );
            }
        }

        try {
            return new BountyContentCatalog(configured);
        } catch (RuntimeException exception) {
            throw new BountyException("Invalid bounty content authority references in " + source, exception);
        }
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("tiers") List<RawTier> tiers
    ) { }

    private record RawTier(
            @JsonProperty("family_id") String familyId,
            @JsonProperty("tier") int tier,
            @JsonProperty("contract_fee_minor") long contractFeeMinor,
            @JsonProperty("required_eligible_kills") int requiredEligibleKills,
            @JsonProperty("boss_definition_id") String bossDefinitionId,
            @JsonProperty("eligible_source_ids") List<String> eligibleSourceIds,
            @JsonProperty("fixed_rewards") Map<String, Long> fixedRewards
    ) { }
}
