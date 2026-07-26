package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.file.FileConfiguration;

/** Resolves the code-bound representation catalog for the frozen legacy Clan-War ruleset. */
final class LegacyClanWarRepresentationCatalogLoader {
    private LegacyClanWarRepresentationCatalogLoader() { }

    static LegacyClanWarRepresentationCatalog load(FileConfiguration config) {
        if (config == null) throw new NullPointerException("config");
        // Deliberately ignore any legacy clan-war.representations YAML entries. Representation changes alter combat
        // semantics and therefore require an explicit ruleset/code version change rather than an operator-side edit.
        return LegacyClanWarRepresentationCatalog.legacy189V1();
    }
}
