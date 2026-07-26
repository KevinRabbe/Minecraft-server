package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded aggregate verifier across economy/custody, persistent PvE, and isolated competitive evidence. */
public final class PersistentIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final EconomyIntegrityVerifier economy;
    private final PersistentPveIntegrityVerifier pve;
    private final CompetitiveIntegrityVerifier competitive;

    public PersistentIntegrityVerifier(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.economy = new EconomyIntegrityVerifier(dataSource);
        this.pve = new PersistentPveIntegrityVerifier(dataSource);
        this.competitive = new CompetitiveIntegrityVerifier(dataSource);
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        ArrayList<IntegrityIssue> issues = new ArrayList<>(economy.verify(maxIssues));
        int remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(pve.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(competitive.verify(remaining));
        }
        return List.copyOf(issues);
    }
}
