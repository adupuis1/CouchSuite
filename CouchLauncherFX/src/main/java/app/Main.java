package app;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.net.URL;

public class Main extends Application {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "couchlauncherfx");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Path CACHE_FILE = CONFIG_DIR.resolve("apps_cache.json");
    private static final int TOTAL_STEPS = 3;
    private static final java.time.Duration INITIAL_TIMEOUT = java.time.Duration.ofSeconds(3);
    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "couchlauncherfx-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final ControllerService controllerService = new ControllerService();
    private ControllerService.ControllerInfo lastControllerInfo = new ControllerService.ControllerInfo(false, Optional.empty());
    private boolean firstLaunchFlow;
    private boolean hasUsersAvailable;
    private boolean controllerConnected;

    private Scene scene;
    private StackPane root;
    private final StackPane overlayLayer = new StackPane();

    private ConnectPane connectPane;
    private UserSelectPane userSelectPane;
    private HubPane hubPane;
    private HostSettingsOverlay hostSettingsOverlay;
    private LoginOverlay loginOverlay;

    private LauncherConfig config;
    private HttpRepo.UserProfile session;
    private boolean offlineMode;
    private List<AppTile> currentTiles = new ArrayList<>();

    private TextField hostField;

    private enum Screen {
        CONNECT,
        USER_SELECT,
        HUB
    }

    private Screen currentScreen;

    private enum OperatingSystem {
        MAC,
        LINUX,
        WINDOWS,
        OTHER
    }

    private record RepoResult(List<AppTile> apps, boolean fromCache, String rawJson) {
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        config = LauncherConfig.load();

        root = new StackPane();
        root.getStyleClass().add("root-container");

        overlayLayer.setMouseTransparent(true);

        scene = new Scene(new StackPane(root, overlayLayer), 1280, 800);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/application.css")).toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Couch Launcher");
        stage.setFullScreenExitHint("Press ESC to exit");
        stage.setFullScreen(true);
        stage.show();

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });

        connectPane = new ConnectPane(executor, controllerService, this::handleConnectContinue, this::openSettings);
        userSelectPane = new UserSelectPane(this::handleUserContinue, this::openCreateAccount, this::openLogin, this::openSettings);
        hubPane = new HubPane(this::openSettings, this::refreshData, this::openLoginFromHub);

        hostSettingsOverlay = new HostSettingsOverlay(() -> overlayLayer.setMouseTransparent(true), this::applyHostChanges, this::refreshData);
        loginOverlay = new LoginOverlay(() -> overlayLayer.setMouseTransparent(true), this::attemptLogin, this::attemptCreateAccount);

        root.getChildren().setAll(connectPane.getNode(), userSelectPane.getNode(), hubPane.getNode());
        overlayLayer.getChildren().addAll(hostSettingsOverlay.getNode(), loginOverlay.getNode());

        initializeFormValues();
        showScreen(Screen.CONNECT, false);
        evaluateStartupRoute();
    }

    @Override
    public void stop() {
        executor.shutdownNow();
    }

    // --------------------------------------------------------------------- UI

    private void showScreen(Screen screen, boolean animate) {
        this.currentScreen = screen;
        connectPane.setVisible(screen == Screen.CONNECT);
        userSelectPane.setVisible(screen == Screen.USER_SELECT);
        hubPane.setVisible(screen == Screen.HUB);

        if (animate) {
            Node node = switch (screen) {
                case CONNECT -> connectPane.getNode();
                case USER_SELECT -> userSelectPane.getNode();
                case HUB -> hubPane.getNode();
            };
            fadeIn(node);
        }

        switch (screen) {
            case CONNECT -> {
                hubPane.resetState();
                userSelectPane.prepare(config.username);
                connectPane.configure(firstLaunchFlow, lastControllerInfo);
            }
            case USER_SELECT -> {
                userSelectPane.prepare(config.username);
                userSelectPane.configure(firstLaunchFlow, controllerConnected);
                userSelectPane.setHasUsers(hasUsersAvailable);
                userSelectPane.updateControllerPresence(controllerConnected, lastControllerInfo.label());
            }
            case HUB -> {
                overlayLayer.setMouseTransparent(true);
                hubPane.onShown();
                refreshDefaultRepo(true);
                if (!offlineMode) {
                    fetchUserPresence();
                }
            }
        }
    }

    private void fadeIn(Node node) {
        FadeTransition transition = new FadeTransition(Duration.millis(350), node);
        transition.setFromValue(0.0);
        transition.setToValue(1.0);
        transition.play();
    }

    private void handleConnectContinue() {
        ControllerService.ControllerInfo info = lastControllerInfo;
        if (info == null) {
            info = controllerService.detect();
        }
        onControllerStatus(info);
        userSelectPane.prepare(config.username);
        userSelectPane.configure(firstLaunchFlow, controllerConnected);
        userSelectPane.setHasUsers(hasUsersAvailable);
        userSelectPane.updateControllerPresence(controllerConnected, info.label());
        if (firstLaunchFlow) {
            preloadDefaultRepo();
        }
        showScreen(Screen.USER_SELECT, true);
    }

    private void handleUserContinue() {
        if (session == null) {
            openLogin();
            return;
        }
        showScreen(Screen.HUB, true);
    }

    private void openSettings() {
        overlayLayer.setMouseTransparent(false);
        hostSettingsOverlay.show(resolvedHost(), offlineMode);
    }

    private void applyHostChanges(String host) {
        String sanitized = host == null ? "" : host.trim();
        if (!sanitized.startsWith("http://") && !sanitized.startsWith("https://")) {
            sanitized = sanitized.isEmpty() ? HttpRepo.DEFAULT_BASE_URL : "http://" + sanitized;
        }
        hostField.setText(sanitized);
        config.host = sanitized;
        saveConfig();
        refreshData();
    }

    private void openLogin() {
        overlayLayer.setMouseTransparent(false);
        loginOverlay.show(false, config.username == null ? "" : config.username);
    }

    private void openCreateAccount() {
        overlayLayer.setMouseTransparent(false);
        loginOverlay.show(true, config.username == null ? "" : config.username);
    }

    private void openLoginFromHub(boolean createAccount) {
        overlayLayer.setMouseTransparent(false);
        loginOverlay.show(createAccount, config.username == null ? "" : config.username);
    }

    private void openSettingsFromHub() {
        openSettings();
    }

    private void initializeFormValues() {
        String initialHost = config.host != null ? config.host : HttpRepo.DEFAULT_BASE_URL;
        hostField.setText(initialHost);
    }

    private void refreshData() {
        if (session == null) {
            refreshDefaultRepo(false);
        } else {
            refreshUserRepo();
        }
    }

    private void refreshDefaultRepo(boolean initial) {
        if (currentScreen != Screen.HUB) {
            return;
        }
        hubPane.showLoading();
        CompletableFuture.supplyAsync(() -> {
            String host = resolvedHost();
            try {
                Integer preloadUser = session != null ? session.userId() : config.userId;
                Integer preloadOrg = session != null && session.primaryOrgId() != null
                        ? session.primaryOrgId()
                        : config.orgId;
                HttpRepo.CatalogResult catalog = HttpRepo.loadCatalog(
                        host,
                        preloadUser,
                        preloadOrg,
                        initial ? INITIAL_TIMEOUT : java.time.Duration.ofSeconds(5),
                        3
                );
                CacheManager.save(catalog.rawJson());
                return new RepoResult(catalog.tiles(), false, catalog.rawJson());
            } catch (Exception ex) {
                String cached = CacheManager.read();
                if (cached != null) {
                    try {
                        List<AppTile> cachedApps = HttpRepo.parseApps(cached);
                        return new RepoResult(cachedApps, true, cached);
                    } catch (Exception ignored) {
                    }
                }
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                handleRepoFailure(throwable);
            } else {
                offlineMode = result.fromCache();
                hubPane.setOffline(offlineMode);
                currentTiles = result.apps();
                hubPane.displayTiles(currentTiles);
                hubPane.showStatus(offlineMode ? "Offline mode (cache)" : "Loaded default charts");
                if (!offlineMode) {
                    fetchUserPresence();
                }
            }
        }));
    }

    private void refreshUserRepo() {
        if (session == null || currentScreen != Screen.HUB) {
            return;
        }
        hubPane.showLoading();
        CompletableFuture.supplyAsync(() -> {
            try {
                Integer orgId = session.primaryOrgId() != null ? session.primaryOrgId() : config.orgId;
                HttpRepo.CatalogResult catalog = HttpRepo.loadCatalog(
                        resolvedHost(),
                        session.userId(),
                        orgId,
                        java.time.Duration.ofSeconds(5),
                        3
                );
                CacheManager.save(catalog.rawJson());
                return new RepoResult(catalog.tiles(), false, catalog.rawJson());
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                handleRepoFailure(throwable);
            } else {
                offlineMode = false;
                hubPane.setOffline(false);
                currentTiles = result.apps();
                hubPane.displayTiles(currentTiles);
                hubPane.showStatus("Loaded catalog for " + session.username());
            }
        }));
    }

    private void handleRepoFailure(Throwable throwable) {
        offlineMode = true;
        hubPane.setOffline(true);
        if (!currentTiles.isEmpty()) {
            hubPane.displayTiles(currentTiles);
        }
        hubPane.showServerError("Server unavailable", this::refreshData);
        hubPane.showStatus("Failed to contact server: " + throwable.getClass().getSimpleName());
    }

    private void fetchUserPresence() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return HttpRepo.fetchUserPresence(resolvedHost());
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((presence, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                loginOverlay.showPresenceMessage("Server unavailable for account check");
            } else if (!presence.hasUsers()) {
                loginOverlay.showPresenceMessage("No users found. Create the first account.");
                userSelectPane.setHasUsers(false);
            } else {
                loginOverlay.showPresenceMessage("Sign in to load your library.");
                userSelectPane.setHasUsers(true);
            }
        }));
    }

    private void attemptLogin(String username, String password) {
        if (offlineMode) {
            loginOverlay.showError("Offline mode. Connect to server to sign in.");
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            loginOverlay.showError("Username and password are required");
            return;
        }
        loginOverlay.setBusy(true, "Signing in...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return HttpRepo.login(resolvedHost(), username, password);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((profile, throwable) -> Platform.runLater(() -> {
            loginOverlay.setBusy(false, "");
            if (throwable != null) {
                loginOverlay.showError(summarizeError(throwable));
            } else {
                handleLoginSuccess(profile);
            }
        }));
    }

    private void attemptCreateAccount(String username, String password) {
        if (offlineMode) {
            loginOverlay.showError("Offline mode. Connect to server to create accounts.");
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            loginOverlay.showError("Username and password are required");
            return;
        }
        loginOverlay.setBusy(true, "Creating account...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return HttpRepo.register(resolvedHost(), username, password);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((profile, throwable) -> Platform.runLater(() -> {
            loginOverlay.setBusy(false, "");
            if (throwable != null) {
                loginOverlay.showError(summarizeError(throwable));
            } else {
                handleLoginSuccess(profile);
            }
        }));
    }

    private void handleLoginSuccess(HttpRepo.UserProfile profile) {
        session = profile;
        offlineMode = false;
        hubPane.setOffline(false);
        loginOverlay.hide();
        overlayLayer.setMouseTransparent(true);
        currentTiles = profile.apps();
        hubPane.displayTiles(currentTiles);
        hubPane.showStatus("Welcome, " + profile.username());
        config.username = profile.username();
        config.userId = profile.userId();
        config.orgId = profile.primaryOrgId();
        config.token = profile.token();
        saveConfig();
        userSelectPane.prepare(profile.username());
        firstLaunchFlow = false;
        hasUsersAvailable = true;
        controllerConnected = true;
        showScreen(Screen.HUB, true);
        refreshUserRepo();
    }

    private void launch(AppTile app) {
        if (!app.playable()) {
            hubPane.showStatus("Game not ready to stream. Verify ownership and install status.");
            return;
        }
        String hostValue = resolvedHost();
        boolean stubLaunch = false;
        HttpRepo.SessionResponse sessionResponse = null;
        if (session != null && session.primaryOrgId() != null && app.gameId != null) {
            try {
                sessionResponse = HttpRepo.startSession(hostValue, session.primaryOrgId(), session.userId(), app.gameId);
                if (sessionResponse.streamUrl() != null && !sessionResponse.streamUrl().isBlank()) {
                    hubPane.showStatus("Session ready: " + sessionResponse.streamUrl());
                } else {
                    hubPane.showStatus("Provisioning session (" + sessionResponse.status() + ")");
                }
            } catch (Exception ex) {
                hubPane.showStatus("Session allocation failed: " + summarizeError(ex));
                return;
            }
        }
        try {
            OperatingSystem os = detectOperatingSystem();
            String hostOnly = stripScheme(hostValue);
            if (os == OperatingSystem.MAC) {
                new ProcessBuilder("open", "-a", "TextEdit").start();
                stubLaunch = true;
            } else if (os == OperatingSystem.LINUX && !forceStub()) {
                new ProcessBuilder(
                        "flatpak",
                        "run",
                        "com.moonlight_stream.Moonlight",
                        "stream",
                        hostOnly,
                        "--app",
                        app.moonlightName
                ).start();
            } else {
                new ProcessBuilder("echo", "Stub launch for " + app.name + " -> " + hostOnly).start();
                stubLaunch = true;
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            hubPane.showStatus("Launch failed: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR, "Failed to launch: " + ex.getMessage(), ButtonType.OK).showAndWait();
            return;
        }

        config.host = hostValue;
        if (session != null) {
            config.userId = session.userId();
            config.username = session.username();
        }
        saveConfig();

        StringBuilder message = new StringBuilder();
        message.append(stubLaunch ? "Stub launch" : "Launching");
        message.append(" '").append(app.name).append("' against host: ").append(hostValue);
        if (sessionResponse != null && sessionResponse.streamUrl() != null) {
            message.append("\nStream URL: ").append(sessionResponse.streamUrl());
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message.toString(), ButtonType.OK);
        alert.setHeaderText("Launch");
        alert.showAndWait();
    }

    private OperatingSystem detectOperatingSystem() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (osName.contains("mac")) {
            return OperatingSystem.MAC;
        }
        if (osName.contains("linux")) {
            return OperatingSystem.LINUX;
        }
        if (osName.contains("win")) {
            return OperatingSystem.WINDOWS;
        }
        return OperatingSystem.OTHER;
    }

    private boolean forceStub() {
        String flag = System.getenv("DEV_FORCE_STUB");
        return flag != null && flag.equals("1");
    }

    private void evaluateStartupRoute() {
        CompletableFuture.supplyAsync(() -> {
            boolean controllerConnected = controllerService.isControllerConnected();
            boolean hasUsers = config.hasKnownUser();
            boolean offline = false;
            try {
                HttpRepo.UserPresence presence = HttpRepo.fetchUserPresence(resolvedHost());
                hasUsers = presence.hasUsers();
            } catch (Exception ex) {
                offline = true;
            }
            return new StartupState(controllerConnected, hasUsers, offline);
        }, executor).whenComplete((state, throwable) -> Platform.runLater(() -> handleStartupState(state, throwable)));
    }

    private void handleStartupState(StartupState state, Throwable throwable) {
        if (throwable != null || state == null) {
            firstLaunchFlow = !config.hasKnownUser();
            hasUsersAvailable = config.hasKnownUser();
            ControllerService.ControllerInfo info = controllerService.detect();
            onControllerStatus(info);
            connectPane.configure(firstLaunchFlow, info);
            preloadDefaultRepo();
            showScreen(Screen.CONNECT, false);
            return;
        }

        offlineMode = state.offline();
        firstLaunchFlow = !state.hasUsers();
        hasUsersAvailable = state.hasUsers();

        ControllerService.ControllerInfo info = controllerService.detect();
        onControllerStatus(info);

        if (!controllerConnected) {
            if (firstLaunchFlow) {
                preloadDefaultRepo();
            }
            showScreen(Screen.CONNECT, false);
            return;
        }

        userSelectPane.prepare(config.username);
        userSelectPane.configure(firstLaunchFlow, true);
        userSelectPane.setHasUsers(hasUsersAvailable);
        userSelectPane.updateControllerPresence(true, info.label());
        if (firstLaunchFlow) {
            preloadDefaultRepo();
        }
        showScreen(Screen.USER_SELECT, true);
    }

    private void onControllerStatus(ControllerService.ControllerInfo info) {
        if (info == null) {
            return;
        }
        lastControllerInfo = info;
        controllerConnected = info.connected();
        userSelectPane.updateControllerPresence(controllerConnected, info.label());
    }

    private void preloadDefaultRepo() {
        CompletableFuture.runAsync(() -> {
            try {
                String json = HttpRepo.fetchChartsJson(resolvedHost(), INITIAL_TIMEOUT, 3);
                CacheManager.save(json);
                List<AppTile> apps = HttpRepo.parseApps(json);
                currentTiles = apps;
            } catch (Exception ignored) {
                // Cache warm-up best effort only.
            }
        }, executor);
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Map<String, Object> payload = new HashMap<>();
            payload.put("host", config.host);
            payload.put("username", config.username);
            payload.put("userId", config.userId);
            payload.put("orgId", config.orgId);
            if (config.token != null && !config.token.isBlank()) {
                payload.put("token", config.token);
            }
            String json = CONFIG_MAPPER.writeValueAsString(payload);
            Files.writeString(CONFIG_FILE, json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private String summarizeError(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return "Request failed: " + cause.getClass().getSimpleName();
        }
        return message;
    }

    private String resolvedHost() {
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        if (host.isEmpty()) {
            return HttpRepo.DEFAULT_BASE_URL;
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        return "http://" + host;
    }

    private String stripScheme(String hostValue) {
        if (hostValue.startsWith("http://")) {
            return hostValue.substring(7);
        }
        if (hostValue.startsWith("https://")) {
            return hostValue.substring(8);
        }
        return hostValue;
    }

    private ImageView createIconView(Image image, double size) {
        ImageView imageView = new ImageView();
        if (image != null) {
            imageView.setImage(image);
        }
        imageView.setFitWidth(size);
        imageView.setFitHeight(size);
        imageView.setPreserveRatio(true);
        return imageView;
    }

    private Image loadImage(String... resourceCandidates) {
        for (String resource : resourceCandidates) {
            if (resource == null || resource.isBlank()) {
                continue;
            }
            URL url = getClass().getResource(resource);
            if (url != null) {
                return new Image(url.toExternalForm(), false);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ Nested types

    private final class ConnectPane {
        private final BorderPane container;
        private final Label controllerStatus;
        private final Button continueButton;
        private final Button settingsButton;
        private final ExecutorService executorService;
        private final ControllerService controllerService;
        private final Runnable onContinue;
        private final Timeline poller;
        private boolean firstLaunchMode;
        private boolean autoAdvanced;
        private boolean pollInFlight;

        private ConnectPane(ExecutorService executorService,
                             ControllerService controllerService,
                             Runnable onContinue,
                             Runnable onSettings) {
            this.executorService = executorService;
            this.controllerService = controllerService;
            this.onContinue = onContinue;

            FXMLLoader loader = new FXMLLoader(Main.class.getResource("/app/ConnectPane.fxml"));
            try {
                container = loader.load();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load ConnectPane.fxml", exception);
            }

            controllerStatus = (Label) loader.getNamespace().get("controllerStatus");
            continueButton = (Button) loader.getNamespace().get("continueButton");
            settingsButton = (Button) loader.getNamespace().get("settingsButton");

            continueButton.setOnAction(event -> onContinue.run());
            settingsButton.setOnAction(event -> onSettings.run());

            poller = new Timeline(new KeyFrame(Duration.seconds(2), event -> pollController()));
            poller.setCycleCount(Timeline.INDEFINITE);
        }

        private void configure(boolean firstLaunch, ControllerService.ControllerInfo info) {
            this.firstLaunchMode = firstLaunch;
            this.autoAdvanced = false;
            continueButton.setVisible(!firstLaunch);
            settingsButton.setVisible(!firstLaunch);
            settingsButton.setManaged(!firstLaunch);
            poller.stop();
            updateStatus(info);
            if (firstLaunch) {
                continueButton.setDisable(true);
            } else {
                continueButton.setDisable(!info.connected());
            }
            poller.playFromStart();
        }

        private void updateStatus(ControllerService.ControllerInfo info) {
            if (info.connected()) {
                String label = info.label().orElse("controller connected");
                controllerStatus.setText(label);
                if (!firstLaunchMode) {
                    continueButton.setDisable(false);
                }
                onControllerStatus(info);
                if (firstLaunchMode && !autoAdvanced) {
                    autoAdvanced = true;
                    poller.stop();
                    onContinue.run();
                }
            } else {
                controllerStatus.setText("Waiting for controller...");
                if (!firstLaunchMode) {
                    continueButton.setDisable(true);
                }
                onControllerStatus(info);
            }
        }

        private void pollController() {
            if (pollInFlight) {
                return;
            }
            pollInFlight = true;
            CompletableFuture.supplyAsync(controllerService::detect, executorService)
                    .whenComplete((info, throwable) -> Platform.runLater(() -> {
                        pollInFlight = false;
                        if (throwable != null || info == null) {
                            return;
                        }
                        updateStatus(info);
                    }));
        }

        private BorderPane getNode() {
            return container;
        }

        private void setVisible(boolean visible) {
            container.setVisible(visible);
            container.setManaged(visible);
            if (!visible) {
                poller.stop();
            }
        }
    }


    private final class UserSelectPane {
        private final BorderPane container;
        private final Label subtitle;
        private final Label presenceMessage;
        private final Label controllerBanner;
        private final Button continueButton;
        private final Button actionsSettingsButton;
        private final HBox avatarRow;
        private final VBox addUserTile;
        private Button headerSettingsButton;
        private boolean firstLaunch;
        private boolean hasUsers;

        private UserSelectPane(Runnable onContinue, Runnable onCreateAccount, Runnable onLogin, Runnable onSettings) {
            FXMLLoader loader = new FXMLLoader(Main.class.getResource("/app/UserSelectPane.fxml"));
            try {
                container = loader.load();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load UserSelectPane.fxml", exception);
            }

            subtitle = (Label) loader.getNamespace().get("subtitle");
            presenceMessage = (Label) loader.getNamespace().get("presenceMessage");
            controllerBanner = (Label) loader.getNamespace().get("controllerBanner");
            avatarRow = (HBox) loader.getNamespace().get("avatarRow");
            addUserTile = (VBox) loader.getNamespace().get("addUserTile");
            continueButton = (Button) loader.getNamespace().get("continueButton");
            actionsSettingsButton = (Button) loader.getNamespace().get("actionsSettingsButton");
            headerSettingsButton = (Button) loader.getNamespace().get("headerSettingsButton");

            Button controllerButton = (Button) loader.getNamespace().get("controllerButton");
            Button userButton = (Button) loader.getNamespace().get("userButton");
            Button wifiButton = (Button) loader.getNamespace().get("wifiButton");
            Button addUserButton = (Button) loader.getNamespace().get("addUserButton");

            controllerButton.setAccessibleText("Controllers");
            controllerButton.setOnAction(event -> showScreen(Screen.CONNECT, false));

            userButton.setAccessibleText("Sign in");
            userButton.setOnAction(event -> onLogin.run());

            wifiButton.setDisable(true);

            if (headerSettingsButton != null) {
                headerSettingsButton.setOnAction(event -> onSettings.run());
            }

            addUserButton.setOnAction(event -> onCreateAccount.run());

            continueButton.setOnAction(event -> onContinue.run());
            actionsSettingsButton.setOnAction(event -> onSettings.run());

            rebuildAvatars(config.hasKnownUser());
        }

        private BorderPane getNode() {
            return container;
        }

        private void setVisible(boolean visible) {
            container.setVisible(visible);
            container.setManaged(visible);
        }

        private void configure(boolean firstLaunch, boolean controllerConnected) {
            this.firstLaunch = firstLaunch;
            continueButton.setVisible(!firstLaunch);
            continueButton.setManaged(!firstLaunch);
            actionsSettingsButton.setVisible(!firstLaunch);
            actionsSettingsButton.setManaged(!firstLaunch);
            if (headerSettingsButton != null) {
                headerSettingsButton.setVisible(!firstLaunch);
                headerSettingsButton.setManaged(!firstLaunch);
            }
            continueButton.setDisable(firstLaunch || !controllerConnected);
            updateControllerPresence(controllerConnected, Optional.empty());
        }

        private void prepare(String knownUserName) {
            if (knownUserName != null && !knownUserName.isBlank()) {
                subtitle.setText("welcome back, " + knownUserName);
                continueButton.setText("Continue as " + knownUserName);
            } else {
                subtitle.setText("select user");
                continueButton.setText("Continue as guest");
            }
        }

        private void setHasUsers(boolean hasUsers) {
            this.hasUsers = hasUsers;
            rebuildAvatars(hasUsers);
            boolean hasStoredUser = config.username != null && !config.username.isBlank();
            if (!firstLaunch) {
                continueButton.setVisible(hasUsers || hasStoredUser);
                continueButton.setManaged(hasUsers || hasStoredUser);
                continueButton.setDisable(!hasUsers && !hasStoredUser);
            }
            presenceMessage.setText(hasUsers ? "Select an account or sign in." : "Create the first CouchSuite account.");
        }

        private void updateControllerPresence(boolean controllerConnected, Optional<String> label) {
            if (controllerConnected) {
                controllerBanner.setText(label.orElse("Controller connected"));
            } else {
                controllerBanner.setText("Waiting for controller...");
            }
            if (!firstLaunch) {
                boolean hasStoredUser = config.username != null && !config.username.isBlank();
                continueButton.setDisable(!controllerConnected || (!hasUsers && !hasStoredUser));
                continueButton.setManaged(continueButton.isVisible());
            }
        }

        private void rebuildAvatars(boolean hasUsers) {
            avatarRow.getChildren().clear();
            if (!hasUsers) {
                avatarRow.getChildren().add(addUserTile);
                return;
            }

            String primaryLabel = config.username != null && !config.username.isBlank() ? config.username : "player one";
            avatarRow.getChildren().add(buildAvatarTile(true, primaryLabel));
            avatarRow.getChildren().add(buildAvatarTile(false, "guest"));
            avatarRow.getChildren().add(addUserTile);
        }

        private VBox buildAvatarTile(boolean highlight, String caption) {
            Avatar avatar = new Avatar(highlight);
            Label label = captionLabel(caption);
            VBox tile = new VBox(8, avatar, label);
            tile.setAlignment(Pos.CENTER);
            return tile;
        }

        private Button createAvatarButton(Image icon, Runnable action) {
            Button button = new Button();
            button.getStyleClass().add("avatar-button");
            button.setGraphic(createIconView(icon, 96));
            button.setOnAction(event -> action.run());
            button.setMinSize(160, 160);
            button.setPrefSize(160, 160);
            button.setMaxSize(160, 160);
            return button;
        }

        private Label captionLabel(String text) {
            Label label = new Label(text);
            label.getStyleClass().add("avatar-caption");
            return label;
        }
    }


    private final class HubPane {
        private final BorderPane container;
        private final Label status;
        private final Label offlineBadge;
        private final VBox serverErrorPane;
        private final Label serverErrorMessage;
        private final Button serverErrorRetry;
        private final VBox contentColumns;
        private final ToggleGroup tabGroup;
        private final ImageView headerGlyph;
        private final Button wifiButton;
        private final ImageView wifiIcon;
        private final Image wifiOnlineImage;
        private final Image wifiSearchingImage;

        private HubPane(Runnable onSettings, Runnable onRefresh, Consumer<Boolean> onLogin) {
            FXMLLoader loader = new FXMLLoader(Main.class.getResource("/app/HubPane.fxml"));
            try {
                container = loader.load();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load HubPane.fxml", exception);
            }

            status = (Label) loader.getNamespace().get("status");
            offlineBadge = (Label) loader.getNamespace().get("offlineBadge");
            contentColumns = (VBox) loader.getNamespace().get("contentColumns");
            serverErrorPane = (VBox) loader.getNamespace().get("serverErrorPane");
            serverErrorMessage = (Label) loader.getNamespace().get("serverErrorMessage");
            serverErrorRetry = (Button) loader.getNamespace().get("serverErrorRetry");

            Button controllerButton = (Button) loader.getNamespace().get("controllerButton");
            Button userButton = (Button) loader.getNamespace().get("userButton");
            wifiButton = (Button) loader.getNamespace().get("wifiButton");
            ImageView wifiIconView = (ImageView) loader.getNamespace().get("wifiIcon");
            Button settingsButton = (Button) loader.getNamespace().get("settingsButton");
            Button signInButton = (Button) loader.getNamespace().get("signInButton");
            Button createButton = (Button) loader.getNamespace().get("createButton");

            headerGlyph = (ImageView) loader.getNamespace().get("headerGlyph");
            ToggleButton homeTab = (ToggleButton) loader.getNamespace().get("homeTab");
            ToggleButton gamingTab = (ToggleButton) loader.getNamespace().get("gamingTab");
            ToggleButton tvTab = (ToggleButton) loader.getNamespace().get("tvTab");

            wifiOnlineImage = loadImage("/app/assets/server_status_icons/icons8-wi-fi-100.png");
            wifiSearchingImage = loadImage("/app/assets/server_status_icons/icons8-scan-wi-fi-100.png");
            wifiIcon = wifiIconView;

            tabGroup = new ToggleGroup();
            configureTab(homeTab, new String[]{"/app/assets/generic_icons/icons8-user-100.png"});
            configureTab(gamingTab, new String[]{"/app/assets/console_or_controller_icons/icons8-game-controller-100.png"});
            configureTab(tvTab, new String[]{"/app/assets/generic_icons/icons8-add-user-male-100.png"});

            tabGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue != null) {
                    updateHeaderGlyph((ToggleButton) newValue);
                    displayTiles(currentTiles);
                }
            });

            tabGroup.selectToggle(homeTab);
            updateHeaderGlyph(homeTab);

            controllerButton.setAccessibleText("Controllers");
            controllerButton.setOnAction(event -> showScreen(Screen.CONNECT, true));

            userButton.setAccessibleText("Current user");
            userButton.setOnAction(event -> onLogin.accept(false));

            wifiButton.setAccessibleText("Wi-Fi status");
            wifiButton.setOnAction(event -> onRefresh.run());

            settingsButton.setOnAction(event -> onSettings.run());
            signInButton.setOnAction(event -> onLogin.accept(false));
            createButton.setOnAction(event -> onLogin.accept(true));

            serverErrorRetry.setOnAction(event -> onRefresh.run());

            serverErrorPane.setVisible(false);
            serverErrorPane.setManaged(false);
        }

        private void updateHeaderGlyph(ToggleButton toggle) {
            String[] resources = (String[]) toggle.getUserData();
            Image glyph = loadImage(resources);
            if (glyph != null) {
                headerGlyph.setImage(glyph);
            }
        }

        private void configureTab(ToggleButton button, String[] glyphCandidates) {
            button.setToggleGroup(tabGroup);
            button.setUserData(glyphCandidates);
        }

        private BorderPane getNode() {
            return container;
        }

        private void setVisible(boolean visible) {
            container.setVisible(visible);
            container.setManaged(visible);
        }

        private void onShown() {
            showStatus("Loading...");
        }

        private void showLoading() {
            showStatus("Contacting server...");
            hideServerError();
        }

        private void showStatus(String message) {
            status.setText(message);
        }

        private void setOffline(boolean offline) {
            offlineBadge.setVisible(offline);
            if (wifiIcon != null) {
                wifiIcon.setImage(offline ? wifiSearchingImage : wifiOnlineImage);
            }
        }

        private void showServerError(String message, Runnable onRetry) {
            serverErrorMessage.setText(message);
            serverErrorRetry.setOnAction(event -> onRetry.run());
            serverErrorPane.setVisible(true);
            serverErrorPane.setManaged(true);
            contentColumns.setVisible(false);
        }

        private void hideServerError() {
            serverErrorPane.setVisible(false);
            serverErrorPane.setManaged(false);
            contentColumns.setVisible(true);
        }

        private void displayTiles(List<AppTile> tiles) {
            if (!container.isVisible()) {
                return;
            }
            hideServerError();
            contentColumns.getChildren().clear();
            if (tiles == null || tiles.isEmpty()) {
                Label empty = new Label("No applications available");
                empty.getStyleClass().add("empty-label");
                contentColumns.getChildren().add(empty);
                return;
            }

            ToggleButton selectedTab = (ToggleButton) tabGroup.getSelectedToggle();
            String tabKey = selectedTab == null ? "home" : selectedTab.getText();

            List<AppTile> filtered = filterForTab(tabKey, tiles);
            if (filtered.isEmpty()) {
                Label message = new Label("No items for " + tabKey);
                message.getStyleClass().add("empty-label");
                contentColumns.getChildren().add(message);
                return;
            }

            List<Section> sections = buildSections(tabKey, filtered);
            for (Section section : sections) {
                VBox sectionBox = new VBox(12);
                sectionBox.getStyleClass().add("hub-section");

                Label heading = new Label(section.title);
                heading.getStyleClass().add("section-title");

                FlowGrid grid = new FlowGrid(section.tiles);

                sectionBox.getChildren().addAll(heading, grid.getNode());
                contentColumns.getChildren().add(sectionBox);
            }
        }

        private List<AppTile> filterForTab(String tabKey, List<AppTile> tiles) {
            return switch (tabKey.toLowerCase(Locale.ENGLISH)) {
                case "gaming" -> tiles.stream()
                        .filter(tile -> tile.enabled)
                        .collect(Collectors.toList());
                case "tv" -> tiles.stream()
                        .filter(tile -> tile.id.toLowerCase(Locale.ENGLISH).contains("tv")
                                || tile.name.toLowerCase(Locale.ENGLISH).contains("tv"))
                        .collect(Collectors.toList());
                default -> new ArrayList<>(tiles);
            };
        }

        private List<Section> buildSections(String tabKey, List<AppTile> tiles) {
            List<AppTile> sorted = new ArrayList<>(tiles);
            sorted.sort(Comparator
                    .comparingInt((AppTile tile) -> tile.sortOrder)
                    .thenComparing(tile -> tile.name.toLowerCase(Locale.ENGLISH)));

            List<Section> sections = new ArrayList<>();
            sections.add(new Section("recents", sorted));
            sections.add(new Section("top picks", sorted));
            sections.add(new Section(tabKey.equalsIgnoreCase("tv") ? "top 10 must watch" : "new releases", sorted));
            return sections;
        }

        private void resetState() {
            contentColumns.getChildren().clear();
            showStatus("Idle");
            hideServerError();
        }
    }
    private static final class Section {
        private final String title;
        private final List<AppTile> tiles;

        private Section(String title, List<AppTile> tiles) {
            this.title = title;
            this.tiles = tiles;
        }
    }

    private final class FlowGrid {
        private final GridPane grid = new GridPane();

        private FlowGrid(List<AppTile> tiles) {
            grid.getStyleClass().add("tile-grid");
            grid.setHgap(24);
            grid.setVgap(24);

            for (int i = 0; i < 4; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(25);
                column.setHalignment(HPos.CENTER);
                grid.getColumnConstraints().add(column);
            }

            List<AppTile> limited = tiles.size() > 12 ? tiles.subList(0, 12) : tiles;
            for (int index = 0; index < limited.size(); index++) {
                AppTile app = limited.get(index);
                int col = index % 4;
                int row = index / 4;

                Button tile = createTileButton(app);
                grid.add(tile, col, row);
            }
        }

        private Button createTileButton(AppTile app) {
            Button button = new Button(app.name);
            button.getStyleClass().add("launcher-tile");
            if (!app.playable()) {
                button.getStyleClass().add("launcher-tile-disabled");
                button.setDisable(true);
            }
            button.setOnAction(event -> launch(app));
            button.setMaxWidth(Double.MAX_VALUE);
            button.setWrapText(true);
            button.setPrefHeight(140);
            return button;
        }

        private GridPane getNode() {
            return grid;
        }
    }

    private final class HostSettingsOverlay {
        private final StackPane container;
        private final Label offlineLabel;
        private final TextField hostInput;
        private final Runnable onClosed;

        private HostSettingsOverlay(Runnable onHide, Consumer<String> onApply, Runnable onRefresh) {
            this.onClosed = onHide;

            FXMLLoader loader = new FXMLLoader(Main.class.getResource("/app/HostSettingsOverlay.fxml"));
            try {
                container = loader.load();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load HostSettingsOverlay.fxml", exception);
            }

            hostInput = (TextField) loader.getNamespace().get("hostInput");
            offlineLabel = (Label) loader.getNamespace().get("offlineLabel");
            Button applyButton = (Button) loader.getNamespace().get("applyButton");
            Button refreshButton = (Button) loader.getNamespace().get("refreshButton");
            Button closeButton = (Button) loader.getNamespace().get("closeButton");

            hostField = hostInput;

            applyButton.setOnAction(event -> {
                onApply.accept(hostInput.getText().trim());
                hide();
            });

            refreshButton.setOnAction(event -> {
                hide();
                onRefresh.run();
            });

            closeButton.setOnAction(event -> hide());

            container.setOnMouseClicked(event -> {
                if (event.getTarget() == container) {
                    hide();
                }
            });
        }

        private void show(String host, boolean offline) {
            hostInput.setText(host);
            offlineLabel.setVisible(offline);
            container.setVisible(true);
            container.setManaged(true);
            FadeTransition transition = new FadeTransition(Duration.millis(200), container);
            transition.setFromValue(0);
            transition.setToValue(1);
            transition.play();
        }

        private void hide() {
            container.setVisible(false);
            container.setManaged(false);
            onClosed.run();
        }

        private StackPane getNode() {
            return container;
        }
    }

    private final class LoginOverlay {
        private final StackPane container;
        private final TextField usernameField;
        private final PasswordField passwordField;
        private final Label statusLabel;
        private final Button primaryButton;
        private final Button secondaryButton;
        private final Runnable onClosed;
        private boolean createMode;

        private LoginOverlay(Runnable onHide, BiConsumer<String, String> onLogin, BiConsumer<String, String> onCreate) {
            FXMLLoader loader = new FXMLLoader(Main.class.getResource("/app/LoginOverlay.fxml"));
            try {
                container = loader.load();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load LoginOverlay.fxml", exception);
            }

            usernameField = (TextField) loader.getNamespace().get("usernameField");
            passwordField = (PasswordField) loader.getNamespace().get("passwordField");
            statusLabel = (Label) loader.getNamespace().get("statusLabel");
            primaryButton = (Button) loader.getNamespace().get("primaryButton");
            Button closeButton = (Button) loader.getNamespace().get("closeButton");
            secondaryButton = (Button) loader.getNamespace().get("secondaryButton");

            passwordField.setOnAction(event -> trigger(onLogin, onCreate));
            primaryButton.setOnAction(event -> trigger(onLogin, onCreate));
            secondaryButton.setOnAction(event -> switchMode(!createMode));
            closeButton.setOnAction(event -> hide());

            this.onClosed = onHide;

            container.setOnMouseClicked(event -> {
                if (event.getTarget() == container) {
                    hide();
                }
            });
        }

        private void trigger(BiConsumer<String, String> onLogin, BiConsumer<String, String> onCreate) {
            if (createMode) {
                onCreate.accept(usernameField.getText().trim(), passwordField.getText());
            } else {
                onLogin.accept(usernameField.getText().trim(), passwordField.getText());
            }
        }

        private void show(boolean create, String defaultUsername) {
            usernameField.setText(defaultUsername);
            passwordField.clear();
            statusLabel.setText(" ");
            switchMode(create);
            container.setVisible(true);
            container.setManaged(true);
            FadeTransition transition = new FadeTransition(Duration.millis(200), container);
            transition.setFromValue(0);
            transition.setToValue(1);
            transition.play();
        }

        private void hide() {
            container.setVisible(false);
            container.setManaged(false);
            onClosed.run();
        }

        private void setBusy(boolean busy, String message) {
            primaryButton.setDisable(busy);
            secondaryButton.setDisable(busy);
            usernameField.setDisable(busy);
            passwordField.setDisable(busy);
            statusLabel.setText(message);
        }

        private void showError(String message) {
            statusLabel.setText(message);
        }

        private void showPresenceMessage(String message) {
            statusLabel.setText(message);
        }

        private void switchMode(boolean create) {
            createMode = create;
            primaryButton.setText(create ? "Create Account" : "Sign In");
            secondaryButton.setText(create ? "Use Existing Account" : "Create Account Instead");
        }

        private StackPane getNode() {
            return container;
        }
    }

    private final class Avatar extends StackPane {
        private Avatar(boolean highlight) {
            double radius = highlight ? 86 : 74;
            Circle shell = new Circle(radius);
            shell.setFill(highlight ? Color.web("#283954") : Color.web("#ececec"));
            shell.setStroke(highlight ? Color.BLACK : Color.web("#3f3f3f"));
            shell.setStrokeWidth(4);
            shell.setEffect(new DropShadow(12, Color.color(0, 0, 0, 0.2)));

            Image icon = loadImage("/app/assets/generic_icons/icons8-user-100.png");
            ImageView graphic = createIconView(icon, highlight ? 88 : 72);

            getChildren().addAll(shell, graphic);
            setAlignment(Pos.CENTER);
            setPadding(new Insets(12));
            if (!highlight) {
                setOpacity(0.7);
            }
        }
    }

    private static final class LauncherConfig {
        private String host;
        private Integer userId;
        private String username;
        private Integer orgId;
        private String token;

        private boolean hasKnownUser() {
            return userId != null || (username != null && !username.isBlank());
        }

        private static LauncherConfig load() {
            LauncherConfig config = new LauncherConfig();
            if (!Files.exists(CONFIG_FILE)) {
                config.host = HttpRepo.DEFAULT_BASE_URL;
                return config;
            }
            try {
                String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
                Map<?, ?> payload = CONFIG_MAPPER.readValue(json, Map.class);
                Object storedHost = payload.get("host");
                if (storedHost instanceof String hostValue && !hostValue.isBlank()) {
                    config.host = hostValue;
                }
                Object storedUserId = payload.get("userId");
                if (storedUserId instanceof Number number) {
                    config.userId = number.intValue();
                }
                Object storedUsername = payload.get("username");
                if (storedUsername instanceof String name && !name.isBlank()) {
                    config.username = name;
                }
                Object storedOrgId = payload.get("orgId");
                if (storedOrgId instanceof Number orgNumber) {
                    config.orgId = orgNumber.intValue();
                }
                Object storedToken = payload.get("token");
                if (storedToken instanceof String tk && !tk.isBlank()) {
                    config.token = tk;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            if (config.host == null) {
                config.host = HttpRepo.DEFAULT_BASE_URL;
            }
            return config;
        }
    }

    private static final class CacheManager {
        private static void save(String json) {
            try {
                Files.createDirectories(CONFIG_DIR);
                Files.writeString(CACHE_FILE, json, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        private static String read() {
            if (!Files.exists(CACHE_FILE)) {
                return null;
            }
            try {
                return Files.readString(CACHE_FILE, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                ex.printStackTrace();
                return null;
            }
        }
    }

    private record StartupState(boolean controllerConnected, boolean hasUsers, boolean offline) {}
}
