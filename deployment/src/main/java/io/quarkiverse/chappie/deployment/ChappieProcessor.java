package io.quarkiverse.chappie.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.SubmissionPublisher;

import org.jboss.logging.Logger;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

import io.quarkiverse.chappie.runtime.dev.ChappieAssistant;
import io.quarkiverse.chappie.runtime.dev.ChappieRecorder;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.assistant.deployment.spi.AssistantConsoleBuildItem;
import io.quarkus.assistant.runtime.dev.Assistant;
import io.quarkus.builder.Version;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleCommand;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.ConsoleStateManager;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.util.ArtifactInfoUtil;
import io.quarkus.dev.console.DevConsoleManager;
import io.quarkus.devservices.common.StartableContainer;
import io.quarkus.devui.spi.buildtime.FooterLogBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.util.ClassPathUtils;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.vertx.core.Vertx;

@BuildSteps(onlyIf = IsLocalDevelopment.class)
public class ChappieProcessor {
    private static final String DEVMCP = "dev-mcp";
    private static final Logger LOG = Logger.getLogger(ChappieProcessor.class);
    private static final String FEATURE = "assistant";
    static volatile ConsoleStateManager.ConsoleContext chappieConsoleContext;

    static volatile ChappieAssistant assistant = new ChappieAssistant();

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void createBeans(BuildProducer<FooterLogBuildItem> footerLogProducer,
            ChappieRecorder recorder,
            BeanContainerBuildItem beanContainer,
            ExtensionVersionBuildItem extensionVersionBuildItem,
            CurateOutcomeBuildItem curateOutcomeBuildItem,
            NonApplicationRootPathBuildItem nonApplicationRootPathBuildItem) {

        String devmcpPath = nonApplicationRootPathBuildItem.resolvePath(DEVMCP);

        RuntimeValue<SubmissionPublisher<String>> chappieLog = recorder.createChappieServerManager(beanContainer.getValue(),
                assistant,
                extensionVersionBuildItem.getVersion(), devmcpPath);

        DevConsoleManager.register("chappie.setBaseUrl", (t) -> {
            String baseUrl = null;
            if (t.containsKey("baseUrl")) {
                baseUrl = t.get("baseUrl");
            }
            assistant.setBaseUrl(baseUrl);
            return true;
        });

        DevConsoleManager.register("chappie.getArtifact", (t) -> {

            Class callerClass = toClass(t.get("caller"));

            if (callerClass != null) {
                Map.Entry<String, String> groupIdAndArtifactId = ArtifactInfoUtil.groupIdAndArtifactId(callerClass,
                        curateOutcomeBuildItem);

                return groupIdAndArtifactId.getKey() + ":" + cleanArtifactId(groupIdAndArtifactId.getValue());
            } else {
                return null;
            }
        });

        DevConsoleManager.setGlobal(DevConsoleManager.DEV_MANAGER_GLOBALS_ASSISTANT, assistant);

        footerLogProducer.produce(new FooterLogBuildItem("Assistant", chappieLog));

    }

    /**
     * Detects active documentation libraries from application dependencies.
     * Maps Quarkus extensions and dependencies to their corresponding documentation libraries.
     */
    @BuildStep
    public void detectActiveLibraries(
            CurateOutcomeBuildItem curateOutcomeBuildItem,
            BuildProducer<ActiveLibrariesBuildItem> activeLibrariesProducer) {

        Set<String> libraries = new HashSet<>();

        // Always include Quarkus documentation
        libraries.add("quarkus");

        // Map of artifact patterns to library names
        Map<String, String> artifactToLibrary = createArtifactToLibraryMapping();

        // Analyze all dependencies
        for (ResolvedDependency dependency : curateOutcomeBuildItem.getApplicationModel().getDependencies()) {
            String artifactId = dependency.getArtifactId();
            String groupId = dependency.getGroupId();

            // Check if this dependency maps to a known library
            for (Map.Entry<String, String> entry : artifactToLibrary.entrySet()) {
                String pattern = entry.getKey();
                String library = entry.getValue();

                // Match by artifact pattern (contains check for flexibility)
                if (artifactId.contains(pattern)) {
                    libraries.add(library);
                    LOG.debugf("Detected library '%s' from dependency %s:%s", library, groupId, artifactId);
                }
            }
        }

        if (libraries.size() > 1) {
            LOG.infof("Chappie detected active libraries: %s", libraries);
        }

        activeLibrariesProducer.produce(new ActiveLibrariesBuildItem(libraries));
    }

    /**
     * Creates mapping from Maven artifact patterns to documentation library names.
     * This mapping determines which documentation is available based on project dependencies.
     */
    private Map<String, String> createArtifactToLibraryMapping() {
        Map<String, String> mapping = new HashMap<>();

        // Hibernate ORM
        mapping.put("hibernate-orm", "hibernate-orm");
        mapping.put("hibernate-core", "hibernate-orm");
        mapping.put("hibernate-entitymanager", "hibernate-orm");

        // SmallRye Config
        mapping.put("smallrye-config", "smallrye-config");
        mapping.put("microprofile-config", "smallrye-config");

        // SmallRye Reactive Messaging
        mapping.put("smallrye-reactive-messaging", "smallrye-reactive-messaging");
        mapping.put("microprofile-reactive-messaging", "smallrye-reactive-messaging");

        // SmallRye JWT
        mapping.put("smallrye-jwt", "smallrye-jwt");
        mapping.put("microprofile-jwt", "smallrye-jwt");

        // SmallRye Fault Tolerance
        mapping.put("smallrye-fault-tolerance", "smallrye-fault-tolerance");
        mapping.put("microprofile-fault-tolerance", "smallrye-fault-tolerance");

        // Add more library mappings as documentation sources are added
        // mapping.put("pattern", "library-name");

        return mapping;
    }

    @BuildStep
    public void findExtensionVersion(BuildProducer<ExtensionVersionBuildItem> extensionVersionProducer) {
        try {
            final Yaml yaml = new Yaml();

            ClassPathUtils.consumeAsPaths(YAML_FILE, (Path p) -> {
                try {

                    final String extensionYaml;
                    try (Scanner scanner = new Scanner(Files.newBufferedReader(p, StandardCharsets.UTF_8))) {
                        scanner.useDelimiter("\\A");
                        extensionYaml = scanner.hasNext() ? scanner.next() : null;
                    }
                    if (extensionYaml == null) {
                        // This is a internal extension
                        return;
                    }

                    final Map<String, Object> extensionMap = yaml.load(extensionYaml);

                    if (extensionMap.containsKey("name")) {
                        String artifactId = (String) extensionMap.getOrDefault("artifact", null);
                        if (artifactId != null && artifactId.startsWith(GAV_START)) {
                            String version = artifactId.substring(GAV_START.length());
                            extensionVersionProducer.produce(new ExtensionVersionBuildItem(version));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace();
                }
            });
        } catch (IOException ex) {
            LOG.error("Error getting version for the Quarkus Assistant", ex);
        }
    }

    @Consume(ConsoleInstalledBuildItem.class)
    @BuildStep
    FeatureBuildItem setupConsole(List<AssistantConsoleBuildItem> assistantConsoleBuildItems) {

        if (!assistantConsoleBuildItems.isEmpty()) {
            if (chappieConsoleContext == null) {
                chappieConsoleContext = ConsoleStateManager.INSTANCE.createContext("Assistant");
            }

            Collections.sort(assistantConsoleBuildItems, Comparator.comparing(AssistantConsoleBuildItem::getDescription));

            Vertx vertx = Vertx.vertx();
            List<ConsoleCommand> consoleCommands = new ArrayList<>();
            for (AssistantConsoleBuildItem assistantConsoleBuildItem : assistantConsoleBuildItems) {
                if (assistantConsoleBuildItem.getConsoleCommand() != null) {
                    consoleCommands.add(assistantConsoleBuildItem.getConsoleCommand());
                } else {
                    ConsoleCommand.HelpState helpState = null;
                    if (assistantConsoleBuildItem.getStateSupplier() != null) {
                        helpState = new ConsoleCommand.HelpState(
                                assistantConsoleBuildItem.getColorSupplier(),
                                assistantConsoleBuildItem.getStateSupplier());
                    }

                    ConsoleCommand consoleCommand = new ConsoleCommand(assistantConsoleBuildItem.getKey(),
                            assistantConsoleBuildItem.getDescription(), helpState,
                            new Runnable() {
                                @Override
                                public void run() {
                                    System.out.println("""
                                            =========================================
                                            Talking to Quarkus Assistant, please wait""");

                                    long timer = vertx.setPeriodic(800, id -> System.out.print("."));

                                    Assistant assistant = DevConsoleManager
                                            .getGlobal(DevConsoleManager.DEV_MANAGER_GLOBALS_ASSISTANT);

                                    if (assistantConsoleBuildItem.getFunction().isPresent()) {
                                        CompletionStage<?> response = assistantConsoleBuildItem.getFunction().get()
                                                .apply(assistant);
                                        printResponse(response, vertx, timer);
                                    } else {
                                        CompletionStage response = assistant.assistBuilder()
                                                .systemMessage(assistantConsoleBuildItem.getSystemMessage().orElse(null))
                                                .userMessage(assistantConsoleBuildItem.getUserMessage())
                                                .variables(assistantConsoleBuildItem.getVariables())
                                                .assist();
                                        printResponse(response, vertx, timer);
                                    }
                                }
                            });

                    consoleCommands.add(consoleCommand);
                }

            }
            chappieConsoleContext.reset(consoleCommands.toArray(new ConsoleCommand[] {}));
        }

        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public DevServicesResultBuildItem startPgvectorDevService(
            LaunchModeBuildItem launchMode,
            DockerStatusBuildItem dockerStatus,
            ChappieConfig cfg,
            ActiveLibrariesBuildItem activeLibraries) {

        if (launchMode.getLaunchMode().isDevOrTest()
                && cfg.augmenting().enabled()
                && dockerStatus.isContainerRuntimeAvailable()) {

            // Include active libraries in configuration
            String librariesConfig = activeLibraries.getLibrariesAsString();

            return DevServicesResultBuildItem.owned()
                    .name("Assistant_Store")
                    .serviceConfig(cfg.augmenting())
                    .startable(this::createContainer)
                    .postStartHook(
                            c -> LOG.infof("Chappie RAG Dev Service started from %s, JDBC=%s, libraries=%s",
                                    c.getContainer().getImage(),
                                    c.getContainer().getJdbcUrl(),
                                    librariesConfig))
                    .configProvider(Map.of(
                            "chappie.rag.db-kind", c -> "postgresql",
                            "chappie.rag.jdbc.url", c -> c.getContainer().getJdbcUrl(),
                            "chappie.rag.username", c -> c.getContainer().getUsername(),
                            "chappie.rag.password", c -> c.getContainer().getPassword(),
                            "chappie.rag.libraries", c -> librariesConfig,
                            "chappie.rag.active", c -> "false"))
                    .build();
        }
        return null;
    }

    private StartableContainer<? extends PostgreSQLContainer<?>> createContainer() {
        String version = Version.getVersion();

        if (version.equalsIgnoreCase("999-SNAPSHOT")) {
            version = "latest";
        }

        return createContainer(version);
    }

    private StartableContainer<PostgreSQLContainer<?>> createContainer(String version) {
        try {
            String image = getImage(version);

            DockerImageName img = DockerImageName.parse(image)
                    .asCompatibleSubstituteFor("postgres");

            return new StartableContainer<>(new PostgreSQLContainer<>(img)
                    .withDatabaseName("postgres")
                    .withUsername("postgres")
                    .withPassword("postgres"));
        } catch (Throwable t) {
            if (version.equals("latest")) {
                throw new RuntimeException("Could not start Chappie RAG Dev Service using Quarkus version latest", t);
            }
            LOG.warnf("Could not start Chappie RAG Dev Service using Quarkus version %s. Falling back to latest version",
                    version);
            return createContainer("latest");
        }
    }

    private String getImage(String version) {
        return "ghcr.io/quarkusio/chappie-ingestion-quarkus:" + version;
    }

    private void printResponse(CompletionStage response, Vertx vertx, long timer) {
        response.thenAccept((output) -> {
            vertx.cancelTimer(timer);
            if (Map.class.isAssignableFrom(output.getClass())) {
                for (Object value : ((Map) output).values()) {
                    System.out.println("\n\n" + value.toString());
                }
            } else {
                System.out.println("\n\n" + output.toString());
            }
        }).exceptionally(ex -> {
            vertx.cancelTimer(timer);
            System.err.println("Failed to get response from Quarkus Assistant [ " + ((Throwable) ex).getMessage() + "]");
            return null;
        });
    }

    private Class toClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    private String cleanArtifactId(String artifactId) {
        if (artifactId.endsWith(DASH_DEV)) {
            artifactId = artifactId.substring(0, artifactId.lastIndexOf(DASH_DEV));
        } else if (artifactId.endsWith(DASH_DEPLOYMENT)) {
            artifactId = artifactId.substring(0, artifactId.lastIndexOf(DASH_DEPLOYMENT));
        } else if (artifactId.endsWith(DASH_DEPLOYMENT_SPI)) {
            artifactId = artifactId.substring(0, artifactId.lastIndexOf(DASH_DEPLOYMENT_SPI));
        } else if (artifactId.endsWith(DASH_SPI)) {
            artifactId = artifactId.substring(0, artifactId.lastIndexOf(DASH_SPI));
        }
        return artifactId;
    }

    private static final String DASH_DEV = "-dev";
    private static final String DASH_DEPLOYMENT = "-deployment";
    private static final String DASH_DEPLOYMENT_SPI = "-deployment-spi";
    private static final String DASH_SPI = "-spi";
    private static final String YAML_FILE = "/META-INF/quarkus-extension.yaml";
    private static final String GROUP_ID = "io.quarkiverse.chappie";
    private static final String ARTIFACT_ID = "quarkus-chappie";
    private static final String GAV_START = GROUP_ID + ":" + ARTIFACT_ID + "::jar:";
}
