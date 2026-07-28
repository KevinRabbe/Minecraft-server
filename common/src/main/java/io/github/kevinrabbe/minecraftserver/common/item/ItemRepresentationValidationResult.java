package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Full result of validating one player's loaded ItemStack representations against persistent authority. */
public record ItemRepresentationValidationResult(
        List<ItemRepresentationIssue> issues,
        Map<UUID, ItemRuntimeStatSnapshot> validatedIndividualSnapshots
) {
    public ItemRepresentationValidationResult {
        Objects.requireNonNull(issues, "issues");
        issues = List.copyOf(issues);
        Objects.requireNonNull(validatedIndividualSnapshots, "validatedIndividualSnapshots");
        validatedIndividualSnapshots = Map.copyOf(validatedIndividualSnapshots);
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}
