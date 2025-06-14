package io.quarkiverse.chappie.runtime.dev;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.jboss.logging.Logger;

import io.quarkus.assistant.runtime.dev.Assistant;
import io.quarkus.dev.console.DevConsoleManager;
import io.quarkus.runtime.util.ClassPathUtils;

@ApplicationScoped
public class ChappieServerManager {
    private static final Logger LOG = Logger.getLogger(ChappieServerManager.class);
    private static Process process;
    private final SubmissionPublisher<String> logPublisher = new SubmissionPublisher<>();
    private ScheduledExecutorService logExecutor;
    private volatile boolean logStreaming = false;
    private Future<?> logTask;

    private ChappieAssistant assistant;
    private String version;

    public SubmissionPublisher<String> init(String version, ChappieAssistant assistant) {
        this.assistant = assistant;
        this.version = version;
        if (Files.notExists(configDir)) {
            try {
                Files.createDirectories(configDir);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        if (Files.notExists(logFile)) {
            try {
                Files.createFile(logFile);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        // Make sure chappie server is installed
        if (!isInstalled()) {
            install(version);
        }

        // Make sure we start the server if it's configured
        if (isConfigured()) {
            start();
        }
        return this.logPublisher;
    }

    @Produces
    // @RequestScoped
    public Optional<Assistant> getAssistantIfConfigured() {
        if (isConfigured()) {
            return Optional.of(this.assistant);
        } else {
            return Optional.empty();
        }
    }

    private boolean isInstalled() {
        Path chappieBase = getChappieBaseDir(version);
        if (Files.exists(chappieBase)) {
            Path chappieServer = getChappieServer(chappieBase);
            return Files.exists(chappieServer);
        }
        return false;
    }

    public final void install(String version) {
        try {
            ClassPathUtils.consumeAsStreams("/bin/" + CHAPPIE_SERVER, (InputStream t) -> {
                try {
                    Path chappieBase = getChappieBaseDir(version);

                    if (!Files.exists(chappieBase)) {
                        Files.createDirectories(chappieBase);
                    }
                    Path chappieServer = getChappieServer(chappieBase);
                    File extractedFile = chappieServer.toFile();

                    try (FileOutputStream outputStream = new FileOutputStream(extractedFile)) {
                        t.transferTo(outputStream);
                    }
                } catch (IOException ex) {
                    LOG.error("Error saving Quarkus Assistant Server", ex);
                }
            });
        } catch (IOException ioe) {
            LOG.error("Error saving Quarkus Assistant Server", ioe);
        }
    }

    public final boolean isConfigured() {
        Properties properties = this.loadConfiguration();
        return properties.containsKey(KEY_NAME);
    }

    public Properties loadConfigurationFor(String name) {
        return load(name);
    }

    public Properties loadConfiguration() {
        return load(null);
    }

    private Properties load(String name) {
        Properties fullProps = new Properties();

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                fullProps.load(in);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        if (name == null || name.isBlank()) {
            name = fullProps.getProperty("name");
        }

        if (name == null || name.isBlank()) {
            return new Properties(); // or throw if name is mandatory
        }

        Properties scopedProps = new Properties();
        scopedProps.setProperty("name", name);

        String prefix = name + ".";

        for (String key : fullProps.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String trimmedKey = key.substring(prefix.length());
                scopedProps.setProperty(trimmedKey, fullProps.getProperty(key));
            }
        }

        return scopedProps;
    }

    public boolean storeConfiguration(Map<String, String> configuration) {
        Properties existingProps = readFullConfiguration();

        String name = configuration.get("name");

        if (name != null && !name.isBlank()) {
            // Add new configuration with prefix (except "name" itself)
            configuration.forEach((key, value) -> {
                if (!"name".equals(key)) {
                    existingProps.setProperty(name + "." + key, value);
                } else {
                    existingProps.setProperty("name", value);
                }
            });
        } else {
            // Remove 'name' key if name is null or blank
            existingProps.remove("name");
        }

        // Store configuration
        return saveFullConfiguration(existingProps, this::start);
    }

    public boolean clearConfiguration() {
        Properties existingProps = readFullConfiguration();
        existingProps.remove("name");
        return saveFullConfiguration(existingProps, this::stop);
    }

    private Properties readFullConfiguration() {
        Properties existingProps = new Properties();

        // Load existing configuration if present
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                existingProps.load(in);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return existingProps;
    }

    private boolean saveFullConfiguration(Properties p, Runnable postAction) {
        try (OutputStream out = Files.newOutputStream(configFile)) {
            p.store(out, "Chappie Configuration");
            postAction.run();
            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public String getConfiguredProviderName() {
        Properties properties = this.loadConfiguration();
        return properties.getProperty(KEY_NAME, null);
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    private Map<String, String> start() {
        if (isRunning()) {
            // TODO: Check if the configuration changed
            stop();
        }

        try {
            Path chappieServer = getChappieServer(this.version);
            Map<String, String> chappieServerArguments = getChappieServerArguments();

            List<String> command = new ArrayList<>();
            command.add("java");
            for (Map.Entry<String, String> es : chappieServerArguments.entrySet()) {
                command.add("-D" + es.getKey() + "=" + es.getValue());
            }

            command.add("-jar");
            command.add(chappieServer.toString());

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectOutput(logFile.toFile())
                    .redirectErrorStream(true);

            process = processBuilder.start();

            chappieServerArguments.put("processId", String.valueOf(process.pid()));

            String chappieServerBase = "http://" + chappieServerArguments.get(SERVER_PROPERTY_KEY_HOST) + ":"
                    + chappieServerArguments.get(SERVER_PROPERTY_KEY_PORT);

            setAssistantBaseUrl(chappieServerBase);

            startStreamingLog();

            return chappieServerArguments;
        } catch (IOException ex) {
            throw new UncheckedIOException("Problem while starting Chappie server", ex);
        }
    }

    public long stop() {
        if (isRunning()) {
            long pid = process.pid();
            process.destroyForcibly();

            setAssistantBaseUrl(null);

            stopStreamingLog();

            return pid;
        }
        return -1L;
    }

    private void startStreamingLog() {
        logStreaming = true;
        this.logExecutor = Executors.newSingleThreadScheduledExecutor();
        logExecutor.scheduleWithFixedDelay(() -> {
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile.toFile()))) {
                String line;
                while (logStreaming) {
                    if ((line = reader.readLine()) != null) {
                        logPublisher.submit(line);
                    }
                }
            } catch (Exception e) {
                logPublisher.closeExceptionally(e);
                logExecutor.shutdownNow();
            } finally {
                //publisher.close();
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
    }

    private void stopStreamingLog() {
        logStreaming = false;
        if (logTask != null) {
            logTask.cancel(true);
        }
        logExecutor.shutdownNow();
        //publisher.close();
    }

    @PreDestroy
    public void destroy() {
        stop();
        logPublisher.close();
    }

    private Path getChappieServer(String version) {
        return getChappieServer(getChappieBaseDir(version));
    }

    private Path getChappieServer(Path chappieBase) {
        if (Files.exists(chappieBase)) {
            return chappieBase.resolve(new File(CHAPPIE_SERVER).getName());
        }
        return null;
    }

    private Path getChappieBaseDir(String version) {
        return configDir.resolve(version);
    }

    private int findAvailablePort(int startPort) {
        int port = startPort;
        while (true) {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                port++;
            }
        }
    }

    private Map<String, String> getChappieServerArguments() {
        Properties providerProperties = this.loadConfiguration();
        if (providerProperties.containsKey(KEY_NAME)) {
            String provider = providerProperties.getProperty(KEY_NAME);

            Map<String, String> properties = new HashMap<>();
            int port = findAvailablePort(4315);
            properties.put(SERVER_PROPERTY_KEY_HOST, "localhost");
            properties.put(SERVER_PROPERTY_KEY_PORT, String.valueOf(port));
            properties.put("chappie.log.request", "true");
            properties.put("chappie.log.response", "true");
            properties.put("chappie.timeout", "PT120S"); // TODO: Make configurable

            if (isOpenAiCompatible(provider)) {
                String baseUrl = providerProperties.getProperty("baseUrl");
                if (baseUrl != null && !baseUrl.isBlank()) {
                    properties.put("chappie.openai.base-url", baseUrl);
                }
                String apiKey = providerProperties.getProperty("apiKey");
                if (apiKey != null && !apiKey.isBlank()) {
                    properties.put("chappie.openai.api-key", apiKey);
                }
                String model = providerProperties.getProperty("model");
                if (model != null && !model.isBlank()) {
                    properties.put("chappie.openai.model-name", model);
                }
            } else if (isOllama(provider)) {
                String baseUrl = providerProperties.getProperty("baseUrl");
                if (baseUrl != null && !baseUrl.isBlank()) {
                    properties.put("chappie.ollama.base-url", baseUrl);
                }
                String model = providerProperties.getProperty("model");
                if (model != null && !model.isBlank()) {
                    properties.put("chappie.ollama.model-name", model);
                }
            }
            return properties;
        }
        return null;
    }

    public boolean isOpenAiCompatible(String name) {
        return name != null
                && (name.equals(OPEN_AI) || name.equals(PODMAN_AI) || name.equals(OPENSHIFT_AI) || name.equals(GENERIC_OPENAI));
    }

    public boolean isOllama(String name) {
        return name != null
                && (name.equals(OLLAMA));
    }

    private void setAssistantBaseUrl(String baseUrl) {
        this.assistant.setBaseUrl(baseUrl);

        Map<String, String> m = new HashMap<>();
        if (baseUrl != null) {
            m.put("baseUrl", baseUrl);
        }
        DevConsoleManager.invoke("chappie.setBaseUrl", m);
    }

    private final Path configDir = Paths.get(System.getProperty("user.home"), ".quarkus", "chappie");
    private final Path configFile = configDir.resolve("chappie-assistant.properties");
    private final Path logFile = configDir.resolve("chappie-assistant.log");

    private static final String CHAPPIE_SERVER = "chappie-server.jar";

    public static final String KEY_NAME = "name";

    public static final String OPEN_AI = "OpenAI";
    public static final String PODMAN_AI = "Podman AI";
    public static final String OPENSHIFT_AI = "OpenShift AI";
    public static final String GENERIC_OPENAI = "Generic OpenAI-Compatible";
    public static final String OLLAMA = "Ollama";

    private static final String SERVER_PROPERTY_KEY_HOST = "quarkus.http.host";
    private static final String SERVER_PROPERTY_KEY_PORT = "quarkus.http.port";

}
