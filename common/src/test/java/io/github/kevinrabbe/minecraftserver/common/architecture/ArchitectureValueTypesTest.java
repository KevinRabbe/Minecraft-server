package io.github.kevinrabbe.minecraftserver.common.architecture;

import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeDefinition;
import io.github.kevinrabbe.minecraftserver.common.crafting.RecipeIngredient;
import io.github.kevinrabbe.minecraftserver.common.economy.BankAccountSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierDefinition;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderSide;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.RollQuality;
import io.github.kevinrabbe.minecraftserver.common.item.RollRange;
import io.github.kevinrabbe.minecraftserver.common.item.UpgradeState;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillCapStage;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractStatus;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyFamilyId;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyTierDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;
import io.github.kevinrabbe.minecraftserver.common.verification.IntegrityIssue;
import io.github.kevinrabbe.minecraftserver.common.verification.IntegritySeverity;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionBallot;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionCandidate;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteDefinition;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteResult;
import io.github.kevinrabbe.minecraftserver.common.world.FeatureAccessibility;
import io.github.kevinrabbe.minecraftserver.common.world.FeatureState;
import io.github.kevinrabbe.minecraftserver.common.world.WorldEraId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureValueTypesTest {
    @Test
    void stagedSkillCapsAreLockedToFiftySeventyFiveAndOneHundred() {
        assertEquals(50, SkillCapStage.LAUNCH.activeCap());
        assertEquals(75, SkillCapStage.EXPANSION_75.activeCap());
        assertEquals(100, SkillCapStage.LATE_100.activeCap());
        assertEquals(SkillCapStage.EXPANSION_75, SkillCapStage.fromActiveCap(75));
        assertThrows(IllegalArgumentException.class, () -> SkillCapStage.fromActiveCap(60));
    }

    @Test
    void rollQualityRemainsNormalizedAndInterpolatesDeterministically() {
        RollRange range = new RollRange(10_000, 12_000);
        assertEquals(10_000, range.interpolate(RollQuality.MIN));
        assertEquals(11_000, range.interpolate(new RollQuality(5_000)));
        assertEquals(12_000, range.interpolate(RollQuality.PERFECT));
        assertTrue(RollQuality.PERFECT.perfect());
        assertEquals(0, UpgradeState.NONE.level());

        ItemRollProfile profile = new ItemRollProfile(Map.of("damage", range));
        assertTrue(profile.rolled());
        assertFalse(ItemRollProfile.NONE.rolled());
    }

    @Test
    void bankSnapshotEnforcesConfiguredCapacity() {
        UUID playerId = UUID.randomUUID();
        BankTierDefinition tier = new BankTierDefinition(2, 1_000_000L, 30);
        BankAccountSnapshot valid = new BankAccountSnapshot(
                playerId,
                900_000L,
                2,
                4,
                LocalDate.of(2026, 7, 25)
        );
        valid.requireWithin(tier);

        BankAccountSnapshot overCapacity = new BankAccountSnapshot(playerId, 1_000_001L, 2, 5, null);
        assertThrows(IllegalStateException.class, () -> overCapacity.requireWithin(tier));
    }

    @Test
    void bazaarOrderComputesCheckedMaximumNotional() {
        BazaarOrderRequest request = new BazaarOrderRequest("iron", BazaarOrderSide.BUY, 500, 120);
        assertEquals(60_000L, request.maximumNotionalMinor());
        assertThrows(
                ArithmeticException.class,
                () -> new BazaarOrderRequest("iron", BazaarOrderSide.BUY, Long.MAX_VALUE, 2)
        );
    }

    @Test
    void craftingRecipeKeepsSkillRequirementSeparateFromIngredients() {
        CraftRecipeDefinition recipe = new CraftRecipeDefinition(
                "iron_cleaver",
                List.of(new RecipeIngredient("iron", 40), new RecipeIngredient("coal", 8)),
                "iron_cleaver",
                1,
                new SkillId("crafting"),
                20
        );
        assertEquals(2, recipe.ingredients().size());
        assertEquals(20, recipe.requiredSkillLevel());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CraftRecipeDefinition(
                        "bad",
                        List.of(new RecipeIngredient("iron", 1)),
                        "iron_cleaver",
                        1,
                        null,
                        20
                )
        );
    }

    @Test
    void mapRunDefinitionIsDifficultyIndependentAndVersioned() {
        MapRunDefinition definition = new MapRunDefinition(
                new MapDifficulty(70),
                "forest",
                "spider",
                "extermination",
                List.of("frenzied", "swarm"),
                1234L,
                2,
                7,
                "founding"
        );
        assertEquals(70, definition.difficulty().value());
        assertEquals(2, definition.modifierIds().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MapRunDefinition(
                        new MapDifficulty(1),
                        "forest",
                        "spider",
                        "extermination",
                        List.of("swarm", "swarm"),
                        1L,
                        0,
                        0,
                        "founding"
                )
        );
    }

    @Test
    void terminalMapRunRequiresFinishTimestamp() {
        Instant created = Instant.parse("2026-07-25T00:00:00Z");
        MapRunDefinition definition = new MapRunDefinition(
                new MapDifficulty(1), "forest", "spider", "extermination", List.of(), 1L, 0, 0, "founding"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MapRunSnapshot(
                        UUID.randomUUID(), UUID.randomUUID(), MapRunStatus.COMPLETED, definition, 1, created, created, null
                )
        );
    }

    @Test
    void bountyTierAndContractKeepFamilyAndSummonStateExplicit() {
        BountyFamilyId spider = new BountyFamilyId("spider");
        BountyTierDefinition tier = new BountyTierDefinition(
                spider,
                3,
                25_000L,
                30,
                "spider_queen",
                List.of("dense_web", "enchanted_web")
        );
        assertEquals(30, tier.requiredEligibleKills());

        BountyContractSnapshot contract = new BountyContractSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spider,
                3,
                BountyContractStatus.SUMMON_READY,
                30,
                30,
                1,
                4
        );
        assertEquals(1, contract.summonAuthorizationsRemaining());
    }

    @Test
    void expansionVoteUsesImmutableCandidateSetAndStableBallots() {
        Instant opens = Instant.parse("2026-08-01T18:00:00Z");
        UUID voteId = UUID.randomUUID();
        ExpansionCandidate fishing = new ExpansionCandidate(
                "fishing",
                "Fishing District",
                List.of("fishing"),
                null
        );
        ExpansionCandidate logistics = new ExpansionCandidate(
                "logistics",
                "Logistics District",
                List.of("logistics"),
                null
        );
        ExpansionVoteDefinition definition = new ExpansionVoteDefinition(
                voteId,
                1,
                opens,
                opens.plusSeconds(86_400),
                List.of(fishing, logistics)
        );
        assertEquals(2, definition.candidates().size());

        ExpansionBallot ballot = new ExpansionBallot(voteId, UUID.randomUUID(), 1, "fishing", opens.plusSeconds(1));
        assertEquals("fishing", ballot.candidateId());

        ExpansionVoteResult result = new ExpansionVoteResult(
                voteId,
                1,
                "fishing",
                Map.of("fishing", 120L, "logistics", 110L),
                opens.plusSeconds(86_400)
        );
        assertEquals(230L, result.totalBallots());
    }

    @Test
    void availableFeatureRequiresAuditableSourceOperation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureState(
                        "nether_access",
                        FeatureAccessibility.AVAILABLE,
                        null,
                        Instant.now(),
                        1
                )
        );
        FeatureState state = new FeatureState(
                "nether_access",
                FeatureAccessibility.AVAILABLE,
                UUID.randomUUID(),
                Instant.now(),
                1
        );
        assertEquals(FeatureAccessibility.AVAILABLE, state.accessibility());
        assertEquals("founding", new WorldEraId("founding").value());
    }

    @Test
    void integrityIssuesUseStableMachineReadableCodes() {
        IntegrityIssue issue = new IntegrityIssue(
                IntegritySeverity.CRITICAL,
                "DUPLICATE_MAP_RUN",
                "map-1",
                "One Map item references multiple valid runs"
        );
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntegrityIssue(IntegritySeverity.ERROR, "bad-code", null, "bad")
        );
    }
}
