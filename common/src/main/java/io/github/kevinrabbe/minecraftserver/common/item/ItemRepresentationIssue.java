package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;

public record ItemRepresentationIssue(
        String source,
        ItemRepresentationIssueCode code,
        String detail
) {
    public ItemRepresentationIssue {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        source = source.trim();
        code = Objects.requireNonNull(code, "code");
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        detail = detail.trim();
    }
}
