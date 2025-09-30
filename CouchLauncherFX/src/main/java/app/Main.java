package app;

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
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main extends Application {

    private static final int GRID_COLUMNS = 3;

    private TextField hostField;
    private VBox root;
    private Scene scene;
    private GridPane tileGrid;
    private final List<Button> tileButtons = new ArrayList<>();

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

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Couch Launcher");

        root = new VBox();
        root.setSpacing(24);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("app-root");

        scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/application.css").toExternalForm());

        ToolBar toolBar = buildToolbar(stage);

        Label title = new Label("Select an experience");
        title.getStyleClass().add("launcher-title");

        tileGrid = buildTileGrid();

        root.getChildren().addAll(toolBar, title, tileGrid);

        loadTiles();

        configureResponsiveLayout();
        Platform.runLater(this::resizeTiles);

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });

        stage.setScene(scene);
        stage.setFullScreenExitHint("Press ESC to exit");
        stage.setFullScreen(true);
        stage.show();

        applyTheme(Theme.COSMIC_BLUE);
    }

    private ToolBar buildToolbar(Stage stage) {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("top-bar");

        Label hostLabel = new Label("Host:");
        hostLabel.getStyleClass().add("host-label");

        hostField = new TextField();
        hostField.setPromptText("GPU Host (e.g., 192.168.1.50)");
        hostField.setPrefWidth(280);

        ComboBox<Theme> themeSelector = new ComboBox<>(FXCollections.observableArrayList(Theme.values()));
        themeSelector.setPrefWidth(180);
        themeSelector.getSelectionModel().select(Theme.COSMIC_BLUE);
        themeSelector.valueProperty().addListener((observable, oldValue, newValue) -> applyTheme(newValue));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button exitButton = new Button("Exit");
        exitButton.setOnAction(event -> stage.close());
        exitButton.getStyleClass().add("exit-button");

        toolBar.getItems().addAll(hostLabel, hostField, new Separator(), themeSelector, spacer, exitButton);
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

    private void loadTiles() {
        String apiBase = System.getenv().getOrDefault("COUCH_API", "http://127.0.0.1:8080");
        List<AppTile> apps;
        try {
            apps = HttpRepo.listApps(apiBase);
            if (apps == null || apps.isEmpty()) {
                apps = defaultTiles();
            }
        } catch (Exception ex) {
            apps = defaultTiles();
            String message = "Failed to load apps from " + apiBase + "\n" + ex.getMessage();
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, message, ButtonType.OK)
                    .showAndWait());
        }

        populateTiles(apps);
    }

    private void populateTiles(List<AppTile> apps) {
        tileButtons.clear();
        tileGrid.getChildren().clear();

        if (apps == null || apps.isEmpty()) {
            return;
        }

        for (int index = 0; index < apps.size(); index++) {
            AppTile app = apps.get(index);
            Button tile = makeTile(app);

            int col = index % GRID_COLUMNS;
            int row = index / GRID_COLUMNS;

            tileGrid.add(tile, col, row);
            GridPane.setHgrow(tile, Priority.ALWAYS);
            GridPane.setFillWidth(tile, true);
        }
    }

    private Button makeTile(AppTile app) {
        Button button = new Button(app.name);
        button.setWrapText(true);
        button.getStyleClass().add("launcher-tile");
        button.setOnAction(event -> launch(app));
        button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        tileButtons.add(button);
        return button;
    }

    private void configureResponsiveLayout() {
        scene.widthProperty().addListener((observable, oldValue, newValue) -> resizeTiles());
        scene.heightProperty().addListener((observable, oldValue, newValue) -> resizeTiles());
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
        double horizontalPadding = root.getPadding().getLeft() + root.getPadding().getRight();
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
        root.getStyleClass().removeIf(style -> style.startsWith("theme-"));
        root.getStyleClass().add(theme.styleClass);
    }

    private void launch(AppTile app) {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            host = "<unset>";
        }

        if ("settings".equalsIgnoreCase(app.id)) {
            Alert settingsAlert = new Alert(
                    Alert.AlertType.INFORMATION,
                    "Settings placeholder.\nHost: " + host,
                    ButtonType.OK
            );
            settingsAlert.setHeaderText("Settings");
            settingsAlert.showAndWait();
            return;
        }

        try {
            new ProcessBuilder("open", "-a", "TextEdit").start();
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR,
                    "Failed to open stub launcher: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
            return;
        }

        Alert launchAlert = new Alert(
                Alert.AlertType.INFORMATION,
                "Stub launch for '" + app.name + "' against host: " + host,
                ButtonType.OK
        );
        launchAlert.setHeaderText("Launch (stub)");
        launchAlert.showAndWait();
    }

    private List<AppTile> defaultTiles() {
        return List.of(
                new AppTile("steam_big_picture", "Steam Big Picture", "Steam Big Picture"),
                new AppTile("switch_emulator", "Switch Emulator", "Switch Emulator"),
                new AppTile("settings", "Settings", "Settings")
        );
    }
}
