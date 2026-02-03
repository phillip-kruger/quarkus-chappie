# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Quarkus Chappie is a Quarkus extension that adds AI assistance to the Quarkus development experience. It is a **dev-mode-only** extension that provides features like exception analysis, code explanation, JavaDoc generation, test generation, and TODO completion through integration with the Chappie Server and LLM providers (OpenAI-compatible services or Ollama).

**Important**: This extension does not add anything to production applications - it only operates in Quarkus dev mode.

## Build & Development Commands

### Building the Extension

```bash
# Full build from root
mvn clean install

# Build without docs and integration tests (faster)
mvn clean install -DskipTests -Ddocs.skip

# Run integration tests
mvn clean install -Pit
```

### Running the Sample Application

```bash
# Build extension first (see above), then:
cd sample
mvn quarkus:dev
```

After starting, configure the AI provider in Dev UI at `http://localhost:8080/q/dev-ui`, then navigate to `http://localhost:8080` to test exception handling.

### Running Tests

```bash
# Run tests for a specific module
cd deployment
mvn test

# Run integration tests
mvn clean install -Pit
```

### Working with Chappie Server

When developing against the Chappie Server:

1. Clone https://github.com/chappie-bot/chappie-server
2. Build with `mvn clean install -Dquarkus.profile=chappie`
3. Change `chappie-server.version` to `999-SNAPSHOT` in `runtime-dev/pom.xml`
4. Rebuild this extension after each server change to pull in new version

## Architecture

### Module Structure

The project follows Quarkus extension conventions with four main modules:

- **runtime**: Minimal runtime module (mostly empty, uses conditional dev dependencies)
- **runtime-dev**: Dev-mode runtime implementation, contains the Chappie assistant and server manager
  - Embeds the Chappie Server JAR (downloaded via Maven dependency plugin)
  - Provides `ChappieAssistant`, `ChappieServerManager`, and JSON-RPC services
- **deployment**: Build-time augmentation processors
  - `ChappieProcessor`: Main processor that sets up dev services, console commands, and library detection
  - `exception/`: Exception handling and analysis
  - `workspace/`: Built-in workspace actions (JavaDoc, test generation, explain, TODO completion)
- **sample**: Demo application for testing the extension
- **docs**: Antora-based documentation
- **integration-tests**: Integration test suite

### Key Architectural Patterns

**Quarkus Extension Pattern**: This follows the standard Quarkus extension architecture:
- Build steps run at build/augmentation time (in `deployment` module)
- Runtime behavior is provided conditionally only in dev mode (via `runtime-dev` module)
- The extension provides the `io.quarkus.assistant` capability

**Dev Services Integration**: ChappieProcessor starts a PostgreSQL container with pgvector extension for RAG (Retrieval-Augmented Generation) capabilities:
- Container image: `ghcr.io/quarkusio/chappie-ingestion-quarkus:{version}`
- Only starts when Docker is available and augmenting is enabled
- Provides vector storage for documentation embeddings

**Library Detection**: The `detectActiveLibraries` build step (ChappieProcessor.java:113-148) analyzes project dependencies to determine which documentation libraries should be available:
- Always includes Quarkus documentation
- Maps artifact patterns to library names (Hibernate ORM, SmallRye Config, SmallRye Reactive Messaging, etc.)
- Produces `ActiveLibrariesBuildItem` consumed by dev services configuration

**Assistant Integration**: Integrates with Quarkus's Assistant framework:
- Registers console commands via `AssistantConsoleBuildItem`
- Provides workspace actions via `WorkspaceActionBuildItem`
- Assistant instance is registered as a global in `DevConsoleManager`

**Build Item Pattern**: Uses Quarkus build items for cross-processor communication:
- `ExtensionVersionBuildItem`: Carries extension version
- `LastExceptionBuildItem`/`LastSolutionBuildItem`: Exception tracking
- `BroadcastsBuildItem`: Exception broadcasting
- `ActiveLibrariesBuildItem`: Detected libraries for RAG

**Workspace Actions**: Built-in actions are defined in `BuiltInActionsProcessor`:
- Each action has a label, assistant function, display mode, and file filter
- Actions use structured prompts (from `*Prompts.java` classes) with expected response types
- Path conversion for test generation (converts `/main/` paths to `/test/`)

## Version Compatibility

| Chappie Version | Quarkus Version |
|-----------------|-----------------|
| 1.2.x           | 3.26.1+         |
| 1.3.x           | 3.26.1+         |
| 1.4.x           | 3.28.3+         |
| 1.5.x           | 3.29.0+         |
| 1.6.x           | 3.31.0+         |

Current development version: `999-SNAPSHOT`

## Important Development Notes

- The extension reads version information from `/META-INF/quarkus-extension.yaml` at build time
- `@BuildSteps(onlyIf = IsLocalDevelopment.class)` ensures processors only run in dev mode
- Dev services only start when `launchMode.isDevOrTest()` and Docker is available
- The extension uses `@Record(ExecutionTime.RUNTIME_INIT)` for bean creation
- Console commands provide interactive CLI access to assistant features
- When adding new library mappings, update `createArtifactToLibraryMapping()` in ChappieProcessor
