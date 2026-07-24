package io.github.kevinrabbe.minecraftserver.common.item;

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

/** Validates custom ItemStack claims before they are trusted as live economic representations. */
public final class ItemRepresentationAuthorityValidator {
    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;

    public ItemRepresentationAuthorityValidator(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    public List<ItemRepresentationIssue> validate(
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
            return List.copyOf(issues);
        }

        Set<UUID> instanceIds = new HashSet<>();
        for (ValidatedIndividualClaim validated : individualClaims) {
            instanceIds.add(validated.claim().itemInstanceId());
        }
        Map<UUID, AuthorityHead> authorityHeads = loadAuthorityHeads(instanceIds);

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
            if (!validated.definition().definitionId().equals(head.definitionId())) {
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.INSTANCE_DEFINITION_MISMATCH,
                        "Authoritative instance belongs to definition " + head.definitionId()
                ));
            }
            if (claim.authorityVersion().longValue() != head.stateVersion()) {
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.AUTHORITY_VERSION_MISMATCH,
                        "Representation authority_version " + claim.authorityVersion()
                                + " does not match authoritative version " + head.stateVersion()
                ));
            }
            if (head.locationKind() != ItemLocationKind.PLAYER_INVENTORY
                    || !playerId.equals(head.locationId())) {
                issues.add(issue(
                        claim,
                        ItemRepresentationIssueCode.AUTHORITY_LOCATION_MISMATCH,
                        "Authoritative location is " + formatLocation(head)
                                + ", not this player's inventory"
                ));
            }
        }

        return List.copyOf(issues);
    }

    private Map<UUID, AuthorityHead> loadAuthorityHeads(Set<UUID> instanceIds) throws SQLException {
        String sql = """
                SELECT item_instance_id,
                       definition_id,
                       location_kind,
                       location_id,
                       state_version
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
                                    results.getLong("state_version")
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
            long stateVersion
    ) {
    }
}
