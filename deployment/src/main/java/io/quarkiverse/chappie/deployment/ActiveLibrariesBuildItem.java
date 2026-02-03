package io.quarkiverse.chappie.deployment;

import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Build item containing the active documentation libraries detected from application dependencies.
 * Used to configure chappie-server with the appropriate library filters for RAG.
 */
public final class ActiveLibrariesBuildItem extends SimpleBuildItem {

    private final Set<String> libraries;

    public ActiveLibrariesBuildItem(Set<String> libraries) {
        this.libraries = Set.copyOf(libraries); // Defensive copy
    }

    /**
     * Get the set of active library names (e.g., "quarkus", "hibernate-orm", "smallrye-config").
     * These correspond to the library metadata in the documentation vector store.
     *
     * @return Immutable set of library names
     */
    public Set<String> getLibraries() {
        return libraries;
    }

    /**
     * Get libraries as comma-separated string for configuration.
     *
     * @return Comma-separated library names (e.g., "quarkus,hibernate-orm")
     */
    public String getLibrariesAsString() {
        return String.join(",", libraries);
    }
}
