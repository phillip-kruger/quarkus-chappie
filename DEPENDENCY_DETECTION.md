# Automatic Library Detection - Documentation

## Overview

The quarkus-chappie extension now automatically detects which documentation libraries should be available based on your application's dependencies. This enables smart RAG filtering that only shows relevant documentation for the libraries you're actually using.

## How It Works

### Build-Time Detection

During the Quarkus build process (`quarkus:dev` or `quarkus:build`), the extension:

1. **Analyzes Dependencies** - Scans all resolved dependencies using `CurateOutcomeBuildItem`
2. **Maps to Libraries** - Matches artifact IDs to known documentation libraries
3. **Configures chappie-server** - Passes detected libraries via `chappie.rag.libraries` property
4. **Starts DevServices** - Launches pgvector container with appropriate configuration

### Runtime Behavior

When you ask questions to CHAPPiE:
- **Filtered Results** - Only documentation from active libraries is searched
- **Better Accuracy** - Reduced noise from irrelevant documentation
- **Smart Defaults** - Quarkus documentation always included

## Supported Libraries

The extension currently detects these libraries:

| Library | Artifact Pattern | Documentation |
|---------|-----------------|---------------|
| **Quarkus** | Always included | Quarkus guides (~250 docs) |
| **Hibernate ORM** | `hibernate-orm`, `hibernate-core` | Hibernate documentation |
| **SmallRye Config** | `smallrye-config`, `microprofile-config` | SmallRye Config docs |
| **SmallRye Reactive Messaging** | `smallrye-reactive-messaging` | Reactive Messaging docs |
| **SmallRye JWT** | `smallrye-jwt`, `microprofile-jwt` | JWT documentation |
| **SmallRye Fault Tolerance** | `smallrye-fault-tolerance` | Fault Tolerance docs |

**Note:** Documentation availability depends on the chappie-ingestion Docker image version.

## Examples

### Example 1: Quarkus + Hibernate Application

**pom.xml:**
```xml
<dependencies>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-hibernate-orm</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-jdbc-postgresql</artifactId>
    </dependency>
</dependencies>
```

**Detection Log:**
```
INFO  [io.qua.cha.dep.ChappieProcessor] Detected library 'hibernate-orm' from dependency org.hibernate.orm:hibernate-core
INFO  [io.qua.cha.dep.ChappieProcessor] Chappie detected active libraries: [quarkus, hibernate-orm]
INFO  [io.qua.cha.dep.ChappieProcessor] Chappie RAG Dev Service started from ghcr.io/quarkusio/chappie-ingestion-quarkus:3.15.0, libraries=quarkus,hibernate-orm
```

**Result:**
- Questions about Hibernate will return Hibernate-specific documentation
- Questions about Quarkus will return Quarkus documentation
- Generic questions search both libraries

### Example 2: Quarkus + SmallRye Config

**pom.xml:**
```xml
<dependencies>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-config-yaml</artifactId>
    </dependency>
</dependencies>
```

**Detection:**
```
INFO  Chappie detected active libraries: [quarkus, smallrye-config]
```

**Benefit:**
- "How do I configure my application?" → Returns SmallRye Config docs + Quarkus config guide

### Example 3: Minimal Quarkus Application

**pom.xml:**
```xml
<dependencies>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-rest</artifactId>
    </dependency>
</dependencies>
```

**Detection:**
```
INFO  Chappie detected active libraries: [quarkus]
```

**Result:**
- Only Quarkus documentation is searched (default behavior)

## Configuration

### Automatic (Recommended)

No configuration needed! The extension automatically detects libraries.

### Manual Override

If you want to manually specify libraries (for testing or override):

**application.properties:**
```properties
# Override auto-detection
chappie.rag.libraries=quarkus,hibernate-orm,smallrye-config

# Disable auto-detection and use only Quarkus docs
chappie.rag.libraries=quarkus
```

**Note:** Manual configuration takes precedence over auto-detection.

### Disable RAG Entirely

```properties
# Disable RAG (CHAPPiE still works, but without documentation search)
chappie.rag.enabled=false
```

## How Detection Works Internally

### ChappieProcessor.detectActiveLibraries()

**Location:** `deployment/src/main/java/io/quarkiverse/chappie/deployment/ChappieProcessor.java`

**Process:**

1. **Iterate Dependencies:**
   ```java
   for (ResolvedDependency dependency : curateOutcomeBuildItem.getApplicationModel().getDependencies()) {
       String artifactId = dependency.getArtifactId();
       // Check artifact patterns...
   }
   ```

2. **Pattern Matching:**
   ```java
   Map<String, String> artifactToLibrary = Map.of(
       "hibernate-orm", "hibernate-orm",
       "hibernate-core", "hibernate-orm",
       "smallrye-config", "smallrye-config",
       // ... more mappings
   );
   ```

3. **Produce BuildItem:**
   ```java
   activeLibrariesProducer.produce(new ActiveLibrariesBuildItem(libraries));
   ```

4. **Configure DevServices:**
   ```java
   .configProvider(Map.of(
       "chappie.rag.libraries", c -> librariesConfig,
       // ... other config
   ))
   ```

### ActiveLibrariesBuildItem

**Purpose:** Carries detected libraries between build steps.

**API:**
- `getLibraries()` - Returns `Set<String>` of library names
- `getLibrariesAsString()` - Returns comma-separated string

## Debugging

### View Detected Libraries

Enable debug logging to see detection details:

**application.properties:**
```properties
quarkus.log.category."io.quarkiverse.chappie.deployment".level=DEBUG
```

**Output:**
```
DEBUG Detected library 'hibernate-orm' from dependency org.hibernate.orm:hibernate-core:6.4.4.Final
DEBUG Detected library 'smallrye-config' from dependency io.smallrye.config:smallrye-config:3.5.0
INFO  Chappie detected active libraries: [quarkus, hibernate-orm, smallrye-config]
```

### Test Library Detection

Create a test application and check logs:

```bash
# Create test app
quarkus create app com.example:test-app

# Add dependency
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm</artifactId>
</dependency>

# Run dev mode
quarkus dev

# Check logs for "Chappie detected active libraries"
```

## Limitations

### Current Limitations

1. **Documentation Availability**
   - Library detection works, but documentation must be available in the Docker image
   - Some libraries may be detected but have no documentation yet

2. **Version Matching**
   - Detection is version-agnostic
   - Uses the documentation version bundled in the chappie-ingestion image

3. **Transitive Dependencies**
   - Currently detects all dependencies (including transitive)
   - May detect libraries you don't directly use

4. **Pattern Matching**
   - Uses simple string matching on artifact IDs
   - May not catch all variations of library artifacts

### Planned Improvements

- [ ] More sophisticated version matching
- [ ] Exclude transitive dependencies (optional)
- [ ] Custom library mappings via configuration
- [ ] Warn when detected library has no documentation
- [ ] Support for external documentation sources

## Adding New Library Mappings

To add support for a new library:

**1. Update ChappieProcessor.createArtifactToLibraryMapping():**

```java
private Map<String, String> createArtifactToLibraryMapping() {
    Map<String, String> mapping = new HashMap<>();

    // ... existing mappings

    // Add new library
    mapping.put("my-library", "my-library");           // Main artifact
    mapping.put("my-library-core", "my-library");      // Core module
    mapping.put("my-library-extensions", "my-library"); // Extensions

    return mapping;
}
```

**2. Ensure documentation is available:**
- Build documentation in chappie-docling-rag
- Include in Docker image
- Tag documents with `library=my-library` metadata

**3. Test:**
```bash
# Add dependency to test app
<dependency>
    <artifactId>my-library</artifactId>
</dependency>

# Run dev mode and verify detection
quarkus dev
```

## Architecture

### Build-Time Flow

```
Application Build
├─ Quarkus Build Process
│  ├─ Resolve Dependencies (Maven/Gradle)
│  └─ Execute BuildSteps
│     ├─ ChappieProcessor.detectActiveLibraries()
│     │  ├─ Read CurateOutcomeBuildItem
│     │  ├─ Match artifacts to libraries
│     │  └─ Produce ActiveLibrariesBuildItem
│     │
│     └─ ChappieProcessor.startPgvectorDevService()
│        ├─ Read ActiveLibrariesBuildItem
│        ├─ Configure chappie.rag.libraries
│        └─ Start pgvector container
│
└─ Runtime
   └─ ChappieServerManager starts chappie-server
      └─ Passes -Dchappie.rag.libraries=... as JVM arg
```

### Runtime Flow

```
Developer Asks Question
├─ CHAPPiE receives query
├─ chappie-server processes query
│  ├─ Read chappie.rag.libraries config
│  ├─ Build library filter: library IN ('quarkus', 'hibernate-orm')
│  ├─ Execute vector similarity search (filtered)
│  └─ Return relevant docs
└─ Display answer to developer
```

## Troubleshooting

### Issue: Library Detected But No Results

**Symptoms:**
```
INFO  Chappie detected active libraries: [quarkus, hibernate-orm]
```
But Hibernate questions return no results.

**Cause:** Documentation not available in Docker image.

**Solution:**
1. Check Docker image includes Hibernate docs
2. Verify image version matches Quarkus version
3. Use combined image: `chappie-ingestion-all:3.15.0`

### Issue: Wrong Library Detected

**Symptoms:**
```
INFO  Detected library 'smallrye-config' from dependency com.example:my-app
```

**Cause:** Artifact name contains pattern string.

**Solution:**
1. Update pattern matching to be more specific
2. Use full artifact ID matching instead of contains()
3. File issue to improve detection logic

### Issue: Library Not Detected

**Symptoms:**
Hibernate dependency present, but not detected.

**Cause:** Artifact ID doesn't match pattern.

**Solution:**
1. Check artifact ID: `mvn dependency:tree | grep hibernate`
2. Add mapping in `createArtifactToLibraryMapping()`
3. Report issue with artifact details

## FAQ

**Q: Does this work in test mode?**
A: Yes, detection works in both dev mode and test mode.

**Q: Can I disable auto-detection?**
A: Yes, set `chappie.rag.libraries` explicitly in application.properties.

**Q: How do I know which libraries were detected?**
A: Check logs for "Chappie detected active libraries" message.

**Q: Does this affect production builds?**
A: No, CHAPPiE only runs in dev mode. This has no impact on production.

**Q: Can I add custom libraries?**
A: Yes, modify `createArtifactToLibraryMapping()` and rebuild the extension.

**Q: What if my library isn't supported?**
A: File an issue with library details, or add mapping yourself and submit PR.

## Related Documentation

- **LIBRARY_FILTERING.md** (chappie-server) - Runtime filtering implementation
- **HIBERNATE_POC.md** (chappie-docling-rag) - Multi-source documentation POC
- **COMPLETE_IMPLEMENTATION_SUMMARY.md** - End-to-end architecture

## Future Enhancements

1. **Smart Version Matching**
   - Detect exact library versions
   - Use version-specific documentation

2. **Configuration UI**
   - DevUI panel to view/override detected libraries
   - Visual indicator of available documentation

3. **Documentation Discovery**
   - Automatic download of missing documentation
   - Plugin system for custom sources

4. **Analytics**
   - Track which libraries are most commonly used
   - Prioritize documentation building

5. **External Sources**
   - Support for company-internal documentation
   - Maven Central javadoc integration
