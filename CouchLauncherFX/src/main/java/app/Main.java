package app;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main extends Application {

    private static final int GRID_COLUMNS = 3;
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "couchlauncherfx");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Path CACHE_FILE = CONFIG_DIR.resolve("apps_cache.json");
    private static final Duration INITIAL_TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "couchlauncherfx-worker");
        thread.setDaemon(true);
        return thread;
    });

    private TextField hostField;
    private StackPane root;
    private VBox mainContent;
    private Scene scene;
    private GridPane tileGrid;
    private final List<Button> tileButtons = new ArrayList<>();
    private VBox errorPane;
    private Label errorMessage;
    private Button retryButton;
    private Label offlineBadge;
    private Label statusBar;

    private StackPane loginOverlay;
    private TextField usernameField;
    private PasswordField passwordField;
    private Label loginStatus;
    private Button loginButton;
    private Button createButton;

    private LauncherConfig config;
    private HttpRepo.UserProfile session;
    private boolean offlineMode;
    private List<AppTile> currentTiles = new ArrayList<>();

    private enum Theme {
        COSMIC_BLUE("Cosmic Blue", "theme-cosmic-blue"),
        LIGHT_GLAZE("Light Glaze", "theme-light-glaze"),
        NOIR_CONTRAST("Noir Contrast", "theme-noir-contrast");

        private final String displayName;
        private final String styleClass;

        Theme(String displayName, String styleClass) {
            this.displayName = displayName;
            this.styleClass = styleClass;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private enum OperatingSystem {
        MAC,
        LINUX,
        WINDOWS,
        OTHER
    }

    private record RepoResult(List<AppTile> apps, boolean fromCache) {
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        config = LauncherConfig.load();

        root = new StackPane();
        mainContent = new VBox();
        mainContent.setSpacing(24);
        mainContent.setPadding(new Insets(24));
        mainContent.getStyleClass().add("app-root");

        ToolBar toolBar = buildToolbar(stage);

        Label title = new Label("Select an experience");
        title.getStyleClass().add("launcher-title");

        tileGrid = buildTileGrid();
        errorPane = buildErrorPane();
        StackPane tileStack = new StackPane(tileGrid, errorPane);

        statusBar = new Label("Idle");
        statusBar.getStyleClass().add("status-bar");

        mainContent.getChildren().addAll(toolBar, title, tileStack, statusBar);

        loginOverlay = buildLoginOverlay();
        loginOverlay.managedProperty().bind(loginOverlay.visibleProperty());
        root.getChildren().addAll(mainContent, loginOverlay);

        Button offOverlayButton = buildOverlayOffButton(stage);
        root.getChildren().add(offOverlayButton);

        scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/application.css")).toExternalForm());

        configureResponsiveLayout();
        Platform.runLater(this::resizeTiles);

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });

        stage.setScene(scene);
        stage.setTitle("Couch Launcher");
        stage.setFullScreenExitHint("Press ESC to exit");
        stage.setFullScreen(true);
        stage.show();

        applyTheme(Theme.COSMIC_BLUE);
        initializeFormValues();
        refreshDefaultRepo(true);
    }

    @Override
    public void stop() {
        executor.shutdownNow();
    }

    private void initializeFormValues() {
        String initialHost = config.host != null ? config.host : HttpRepo.DEFAULT_BASE_URL;
        hostField.setText(initialHost);
        if (config.username != null) {
            usernameField.setText(config.username);
        }
    }

    private ToolBar buildToolbar(Stage stage) {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("top-bar");

        Label hostLabel = new Label("Host:");
        hostLabel.getStyleClass().add("host-label");

        hostField = new TextField();
        hostField.setPromptText("Server host (e.g., 192.168.4.229:8080)");
        hostField.setPrefWidth(280);

        ComboBox<Theme> themeSelector = new ComboBox<>(FXCollections.observableArrayList(Theme.values()));
        themeSelector.setPrefWidth(180);
        themeSelector.getSelectionModel().select(Theme.COSMIC_BLUE);
        themeSelector.valueProperty().addListener((observable, oldValue, newValue) -> applyTheme(newValue));

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> refreshData());

        offlineBadge = new Label("Offline cache");
        offlineBadge.getStyleClass().add("offline-badge");
        offlineBadge.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        toolBar.getItems().addAll(hostLabel, hostField, new Separator(), themeSelector, refreshButton, offlineBadge, spacer);
        return toolBar;
    }

    private GridPane buildTileGrid() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setHgap(32);
        grid.setVgap(24);
        grid.setPadding(new Insets(0));
        grid.getStyleClass().add("tile-grid");
        return grid;
    }

    private VBox buildErrorPane() {
        errorMessage = new Label("Server unavailable");
        errorMessage.setWrapText(true);
        errorMessage.setAlignment(Pos.CENTER);
        errorMessage.getStyleClass().add("server-error-message");

        retryButton = new Button("Retry");
        retryButton.setOnAction(event -> refreshDefaultRepo(false));
        retryButton.getStyleClass().add("retry-button");

        VBox pane = new VBox(16, errorMessage, retryButton);
        pane.setAlignment(Pos.CENTER);
        pane.getStyleClass().add("server-error-pane");
        pane.setVisible(false);
        pane.managedProperty().bind(pane.visibleProperty());
        return pane;
    }

    private StackPane buildLoginOverlay() {
        Label heading = new Label("Sign in to CouchSuite");
        heading.getStyleClass().add("login-heading");

        usernameField = new TextField();
        usernameField.setPromptText("Username");

        passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setOnAction(event -> attemptLogin(false));

        loginStatus = new Label(" ");
        loginStatus.getStyleClass().add("status-message");

        loginButton = new Button("Log In");
        loginButton.setOnAction(event -> attemptLogin(false));

        createButton = new Button("Create Account");
        createButton.setOnAction(event -> attemptLogin(true));

        HBox buttons = new HBox(12, loginButton, createButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(12, heading, usernameField, passwordField, buttons, loginStatus);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPickOnBounds(true);

        StackPane overlay = new StackPane(card);
        overlay.setAlignment(Pos.CENTER);
        overlay.getStyleClass().add("login-overlay");
        overlay.setPickOnBounds(false);

        return overlay;
    }

    private Button buildOverlayOffButton(Stage stage) {
        Button offButton = new Button("Off");
        offButton.getStyleClass().add("exit-button");
        offButton.setFocusTraversable(false);
        offButton.setOnAction(event -> Platform.exit());
        StackPane.setAlignment(offButton, Pos.TOP_RIGHT);
        StackPane.setMargin(offButton, new Insets(16));
        offButton.setPickOnBounds(false);
        return offButton;
    }

    private void configureResponsiveLayout() {
        scene.widthProperty().addListener((observable, oldValue, newValue) -> resizeTiles());
        scene.heightProperty().addListener((observable, oldValue, newValue) -> resizeTiles());
    }

    private void refreshData() {
        if (session == null) {
            refreshDefaultRepo(false);
        } else {
            refreshUserRepo();
        }
    }

    private void refreshDefaultRepo(boolean initial) {
        showStatus("Contacting server...");
        clearServerError();
        CompletableFuture.supplyAsync(() -> {
            String host = resolvedHost();
            try {
                String json = HttpRepo.fetchAppsJson(host, initial ? INITIAL_TIMEOUT : Duration.ofSeconds(5), 3);
                CacheManager.save(json);
                List<AppTile> apps = HttpRepo.parseApps(json);
                return new RepoResult(apps, false);
            } catch (Exception ex) {
                String cached = CacheManager.read();
                if (cached != null) {
                    try {
                        List<AppTile> cachedApps = HttpRepo.parseApps(cached);
                        return new RepoResult(cachedApps, true);
                    } catch (Exception ignored) {
                        // fall through to failure below
                    }
                }
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                handleRepoFailure(throwable);
            } else {
                offlineMode = result.fromCache();
                setOfflineBadge(offlineMode);
                currentTiles = result.apps();
                renderTiles(currentTiles);
                showStatus(offlineMode ? "Offline mode (cache)" : "Loaded default apps");
                if (!offlineMode) {
                    fetchUserPresence();
                }
            }
        }));
    }

    private void refreshUserRepo() {
        if (session == null) {
            return;
        }
        showStatus("Refreshing library...");
        clearServerError();
        CompletableFuture.supplyAsync(() -> {
            try {
                List<AppTile> apps = HttpRepo.fetchUserApps(resolvedHost(), session.userId());
                return new RepoResult(apps, false);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                handleRepoFailure(throwable);
            } else {
                offlineMode = false;
                setOfflineBadge(false);
                currentTiles = result.apps();
                renderTiles(currentTiles);
                showStatus("Loaded apps for " + session.username());
            }
        }));
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
                loginStatus.setText("Server unavailable for account check");
            } else if (!presence.hasUsers()) {
                loginStatus.setText("No users found. Create the first account.");
            } else {
                loginStatus.setText("Sign in to load your library.");
            }
        }));
    }

    private void handleRepoFailure(Throwable throwable) {
        offlineMode = true;
        setOfflineBadge(true);
        renderTiles(currentTiles);
        showServerError("Server unavailable");
        showStatus("Failed to contact server: " + throwable.getClass().getSimpleName());
    }

    private void renderTiles(List<AppTile> apps) {
        tileButtons.clear();
        tileGrid.getChildren().clear();

        if (apps == null || apps.isEmpty()) {
            showServerError("No applications available");
            return;
        }

        List<AppTile> sorted = new ArrayList<>(apps);
        sorted.sort(Comparator
                .comparingInt((AppTile tile) -> tile.sortOrder)
                .thenComparing(tile -> tile.name.toLowerCase(Locale.ENGLISH)));

        for (int index = 0; index < sorted.size(); index++) {
            AppTile app = sorted.get(index);
            Button tile = makeTile(app);

            int col = index % GRID_COLUMNS;
            int row = index / GRID_COLUMNS;

            tileGrid.add(tile, col, row);
            GridPane.setHgrow(tile, Priority.ALWAYS);
            GridPane.setFillWidth(tile, true);
        }

        tileGrid.setVisible(true);
        errorPane.setVisible(false);
        resizeTiles();
    }

    private Button makeTile(AppTile app) {
        Button button = new Button(app.name);
        button.setWrapText(true);
        button.getStyleClass().add("launcher-tile");
        if (!app.installed || !app.enabled) {
            button.getStyleClass().add("launcher-tile-disabled");
            button.setDisable(!app.installed);
        }
        button.setOnAction(event -> launch(app));
        button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        tileButtons.add(button);
        return button;
    }

    private void resizeTiles() {
        if (scene == null || tileButtons.isEmpty()) {
            return;
        }

        double width = scene.getWidth();
        if (width <= 0) {
            return;
        }

        double columns = GRID_COLUMNS;
        double horizontalPadding = mainContent.getPadding().getLeft() + mainContent.getPadding().getRight();
        double usableWidth = width - horizontalPadding;
        if (usableWidth <= 0) {
            return;
        }

        double gap = clamp(24, usableWidth * 0.03, 96);
        double outer = clamp(24, gap, 120);

        double totalStatic = (gap * (columns - 1)) + (outer * 2);
        if (totalStatic >= usableWidth) {
            double scale = (usableWidth - columns * 120) / Math.max(totalStatic, 1);
            scale = clamp(0.2, scale, 1.0);
            gap *= scale;
            outer *= scale;
            totalStatic = (gap * (columns - 1)) + (outer * 2);
        }

        double spaceForTiles = usableWidth - totalStatic;
        double tileWidth = spaceForTiles / columns;
        double tileHeight = Math.max(160, tileWidth * 9 / 16);

        tileGrid.setPadding(new Insets(0, outer, 0, outer));
        tileGrid.setHgap(gap);
        tileGrid.setVgap(clamp(16, gap * 0.6, 48));

        for (Button tile : tileButtons) {
            tile.setMinWidth(tileWidth);
            tile.setPrefWidth(tileWidth);
            tile.setMaxWidth(tileWidth);
            tile.setMinHeight(tileHeight);
            tile.setPrefHeight(tileHeight);
            tile.setMaxHeight(tileHeight);
        }
    }

    private static double clamp(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private void applyTheme(Theme theme) {
        if (theme == null) {
            return;
        }
        mainContent.getStyleClass().removeIf(style -> style.startsWith("theme-"));
        mainContent.getStyleClass().add(theme.styleClass);
    }

    private void attemptLogin(boolean create) {
        if (offlineMode) {
            loginStatus.setText("Offline mode. Connect to server to sign in.");
            return;
        }

        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        if (username.isEmpty() || password.isEmpty()) {
            loginStatus.setText("Username and password are required");
            return;
        }

        setLoginBusy(true, create ? "Creating account..." : "Signing in...");
        CompletableFuture.supplyAsync(() -> {
            try {
                if (create) {
                    return HttpRepo.register(resolvedHost(), username, password);
                }
                return HttpRepo.login(resolvedHost(), username, password);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor).whenComplete((profile, throwable) -> Platform.runLater(() -> {
            setLoginBusy(false, " ");
            if (throwable != null) {
                loginStatus.setText(summarizeError(throwable));
            } else {
                handleLoginSuccess(profile);
            }
        }));
    }

    private void handleLoginSuccess(HttpRepo.UserProfile profile) {
        session = profile;
        offlineMode = false;
        setOfflineBadge(false);
        loginOverlay.setVisible(false);
        loginStatus.setText(" ");
        passwordField.clear();
        currentTiles = profile.apps();
        renderTiles(currentTiles);
        showStatus("Welcome, " + profile.username() + "");
        config.username = profile.username();
        config.userId = profile.userId();
        saveConfig();
    }

    private void setLoginBusy(boolean busy, String message) {
        loginButton.setDisable(busy);
        createButton.setDisable(busy);
        passwordField.setDisable(busy);
        usernameField.setDisable(busy);
        loginStatus.setText(message);
    }

    private void launch(AppTile app) {
        String hostValue = hostField.getText() == null ? "" : hostField.getText().trim();
        if (hostValue.isEmpty()) {
            hostValue = HttpRepo.DEFAULT_BASE_URL;
        }
        boolean stubLaunch = false;
        try {
            OperatingSystem os = detectOperatingSystem();
            String hostOnly = hostValue;
            if (hostOnly.startsWith("http://")) {
                hostOnly = hostOnly.substring(7);
            } else if (hostOnly.startsWith("https://")) {
                hostOnly = hostOnly.substring(8);
            }
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
            showStatus("Launch failed: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR, "Failed to launch: " + ex.getMessage(), ButtonType.OK).showAndWait();
            return;
        }

        config.host = hostValue;
        if (session != null) {
            config.userId = session.userId();
            config.username = session.username();
        }
        saveConfig();

        Alert launchAlert = new Alert(
                Alert.AlertType.INFORMATION,
                (stubLaunch ? "Stub launch" : "Launching") + " '" + app.name + "' against host: " + hostValue,
                ButtonType.OK
        );
        launchAlert.setHeaderText("Launch");
        launchAlert.showAndWait();
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

    private void showServerError(String message) {
        errorMessage.setText(message);
        errorPane.setVisible(true);
        tileGrid.setVisible(currentTiles != null && !currentTiles.isEmpty());
    }

    private void clearServerError() {
        errorPane.setVisible(false);
    }

    private void setOfflineBadge(boolean offline) {
        offlineBadge.setVisible(offline);
    }

    private void showStatus(String message) {
        statusBar.setText(message);
    }

    private String resolvedHost() {
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        if (host.isEmpty()) {
            return HttpRepo.DEFAULT_BASE_URL;
        }
        return host;
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Map<String, Object> payload = new HashMap<>();
            payload.put("host", config.host);
            payload.put("username", config.username);
            payload.put("userId", config.userId);
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

    private static final class LauncherConfig {
        private String host;
        private Integer userId;
        private String username;

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
}
