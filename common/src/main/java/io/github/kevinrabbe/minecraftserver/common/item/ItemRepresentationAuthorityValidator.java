package io.github.kevinrabbe.minecraftserver.common.item;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates custom ItemStack claims before they are trusted as live economic/gameplay representations. */
public final class ItemRepresentationAuthorityValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> ROLL_STATE_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;

    public ItemRepresentationAuthorityValidator(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    /** Compatibility view for existing callers that need only validation issues. */
    public List<ItemRepresentationIssue> validate(
            UUID playerId,
            Collection<ItemRepresentationClaim> claims
    ) throws SQLException {
        return validateAndSnapshot(playerId, claims).issues();
    }

    public ItemRepresentationValidationResult validateAndSnapshot(
            UUID playerId,
            Collection<ItemRepresentationClaim> claims
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(claims, "claims");

        ArrayList<ItemRepresentationIssue> issues = new ArrayList<>();
        ArrayList<ValidatedIndividualClaim> individualClaims = new ArrayList<>();
        Map<UUID, String> firstSourceByInstance = new HashMap<>();

        for (ItemRepresentationClaim claim : claims) {
            ItemRepresentationClaim nonNullClaim = Objects.requireNonNull(
                    claim,
                    "claims must not contain null"
            );
            ItemDefinition definition = itemCatalog.find(nonNullClaim.definitionId()).orElse(null);
            if (definition == null) {
                issues.add(issue(
                        nonNullClaim,
                        ItemRepresentationIssueCode.UNKNOWN_DEFINITION,
                        "Unknown definition_id " + nonNullClaim.definitionId()
                ));
                continue;
            }

            boolean structurallyValid = true;
            if (!definition.minecraftMaterial().equals(nonNullClaim.minecraftMaterial())) {
                structurallyValid = false;
                issues.add(issue(
                        nonNullClaim,
                        ItemRepresentationIssueCode.MATERIAL_MISMATCH,
                        "Expected material " + definition.minecraftMaterial()
                                + " but representation uses " + nonNullClaim.minecraftMaterial()
                ));
            }
            if (nonNullClaim.amount() > definition.maxStackSize()) {
                structurallyValid = false;
                issues.add(issue(
                        nonNullClaim,
                        ItemRepresentationIssueCode.INVALID_STACK_SIZE,
                        "Amount " + nonNullClaim.amount()
                                + " exceeds definition max stack " + definition.maxStackSize()
                ));
            }

            if (definition.identityKind() == ItemIdentityKind.COMMODITY) {
                if (nonNullClaim.itemInstanceId() != null || nonNullClaim.authorityVersion() != null) {
                    issues.add(issue(
                            nonNullClaim,
                            ItemRepresentationIssueCode.IDENTITY_SHAPE_MISMATCH,
                            "COMMODITY representation must not carry item_instance_id or authority_version"
                    ));
                }
                continue;
            }

            if (nonNullClaim.amount() != 1
                    || nonNullClaim.itemInstanceId() == null
                    || nonNullClaim.authorityVersion() == null) {
                issues.add(issue(
                        nonNullClaim,
                        ItemRepresentationIssueCode.IDENTITY_SHAPE_MISMATCH,
                        "INDIVIDUAL representation requires amount=1, item_instance_id, and authority_version"
                ));
                continue;
            }
            if (!structurallyValid) {
                continue;
            }

            String firstSource = firstSourceByInstance.putIfAbsent(
                    nonNullClaim.itemInstanceId(),
                    nonNullClaim.source()
            );
            if (firstSource != null) {
                issues.add(issue(
                        nonNullClaim,
                        ItemRepresentationIssueCode.DUPLICATE_INSTANCE_ID,
                        "item_instance_id " + nonNullClaim.itemInstanceId()
                                + " is already represented at " + firstSource
                ));
                continue;
            }

            individualClaims.add(new ValidatedIndividualClaim(nonNullClaim, definition));
        }

        if (individualClaims.isEmpty()) {
            return new ItemRepresentationValidationResult(issues, Map.of());
        }

        Set<UUID> instanceIds = new HashSet<>();
        for (ValidatedIndividualClaim validated : individualClaims) {
            instanceIds.add(validated.claim().itemInstanceId());
        }
        Map<UUID, AuthorityHead> authorityHeads = loadAuthorityHeads(instanceIds);
        LinkedHashMap<UUID, ItemRuntimeStatSnapshot> snapshots = new LinkedHashMap<>();

        for (ValidatedIndividualClaim validated : individualClaims) {
            ItemRepresentationClaim claim = validated.claim();
            AuthorityHead head = authorityHeads.get(claim.itemInstanceId());
            if (head == null) {
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.UNKNOWN_INSTANCE_ID,
                        "Unknown item_instance_id " + claim.itemInstanceId()
                ));
                continue;
            }

            boolean authorityValid = true;
            if (!validated.definition().definitionId().equals(head.definitionId())) {
                authorityValid = false;
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.INSTANCE_DEFINITION_MISMATCH,
                        "Authoritative instance belongs to definition " + head.definitionId()
                ));
            }
            if (claim.authorityVersion().longValue() != head.stateVersion()) {
                authorityValid = false;
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.AUTHORITY_VERSION_MISMATCH,
                        "Representation authority_version " + claim.authorityVersion()
                                + " does not match authoritative version " + head.stateVersion()
                ));
            }
            if (head.locationKind() != ItemLocationKind.PLAYER_INVENTORY
                    || !playerId.equals(head.locationId())) {
                authorityValid = false;
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.AUTHORITY_LOCATION_MISMATCH,
                        "Authoritative location is " + formatLocation(head)
                                + ", not this player's inventory"
                ));
            }
            if (!authorityValid) {
                continue;
            }

            try {
                if (validated.definition().category() != ItemCategory.EQUIPMENT && head.upgradeLevel() != 0) {
                    throw new IllegalArgumentException(
                            "non-equipment definition carries generic upgrade state: " + head.upgradeLevel()
                    );
                }
                Map<String, Integer> rollState = parseRollState(head.rollStateJson());
                Map<String, Integer> intrinsicMultipliers = IntrinsicRollResolver.resolveMultipliers(
                        validated.definition().rollProfile(),
                        rollState
                );
                UpgradeState upgradeState = new UpgradeState(head.upgradeLevel());
                snapshots.put(claim.itemInstanceId(), new ItemRuntimeStatSnapshot(
                        claim.itemInstanceId(),
                        head.definitionId(),
                        new ItemLocation(head.locationKind(), head.locationId()),
                        head.stateVersion(),
                        rollState,
                        intrinsicMultipliers,
                        upgradeState
                ));
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.AUTHORITY_STAT_STATE_INVALID,
                        "Authoritative roll/upgrade state is incompatible with current item definition: "
                                + exception.getMessage()
                ));
            }
        }

        return new ItemRepresentationValidationResult(issues, snapshots);
    }

    private Map<UUID, AuthorityHead> loadAuthorityHeads(Set<UUID> instanceIds) throws SQLException {
        String sql = """
                SELECT item_instance_id,
                       definition_id,
                       location_kind,
                       location_id,
                       state_version,
                       roll_state::text AS roll_state_json,
                       upgrade_level
                FROM item_instances
                WHERE item_instance_id = ANY (?)
                """;

        try (Connection connection = dataSource.getConnection()) {
            Array idArray = connection.createArrayOf("uuid", instanceIds.toArray());
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setArray(1, idArray);
                    try (ResultSet results = statement.executeQuery()) {
                        LinkedHashMap<UUID, AuthorityHead> heads = new LinkedHashMap<>();
                        while (results.next()) {
                            UUID instanceId = results.getObject("item_instance_id", UUID.class);
                            heads.put(instanceId, new AuthorityHead(
                                    results.getString("definition_id"),
                                    ItemLocationKind.valueOf(results.getString("location_kind")),
                                    results.getObject("location_id", UUID.class),
                                    results.getLong("state_version"),
                                    results.getString("roll_state_json"),
                                    results.getInt("upgrade_level")
                            ));
                        }
                        return Map.copyOf(heads);
                    }
                }
            } finally {
                idArray.free();
            }
        }
    }

    private static Map<String, Integer> parseRollState(String json) throws JsonProcessingException {
        if (json == null) {
            throw new IllegalArgumentException("roll_state is null");
        }
        Map<String, Integer> parsed = JSON.readValue(json, ROLL_STATE_TYPE);
        if (parsed == null) {
            throw new IllegalArgumentException("roll_state is null");
        }
        return Map.copyOf(parsed);
    }

    private static ItemRepresentationIssue issue(
            ItemRepresentationClaim claim,
            ItemRepresentationIssueCode code,
            String detail
    ) {
        return new ItemRepresentationIssue(claim.source(), code, detail);
    }

    private static String formatLocation(AuthorityHead head) {
        return head.locationId() == null
                ? head.locationKind().name()
                : head.locationKind().name() + ":" + head.locationId();
    }

    private record ValidatedIndividualClaim(
            ItemRepresentationClaim claim,
            ItemDefinition definition
    ) {
    }

    private record AuthorityHead(
            String definitionId,
            ItemLocationKind locationKind,
            UUID locationId,
            long stateVersion,
            String rollStateJson,
            int upgradeLevel
    ) {
    }
}
