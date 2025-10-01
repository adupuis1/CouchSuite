package app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Linux-focused helper that tries to determine whether a Bluetooth game controller is connected.
 * The implementation favours direct device discovery and falls back to "bluetoothctl" output.
 * A development override is provided via DEV_FORCE_CONTROLLER=1/0.
 */
public final class ControllerService {

    private static final String FORCE_FLAG = "DEV_FORCE_CONTROLLER";
    private static final Path INPUT_BY_ID = Path.of("/dev/input/by-id");
    private static final Duration BLUETOOTHCTL_TIMEOUT = Duration.ofSeconds(2);
    private static final List<String> CONTROLLER_KEYWORDS = List.of(
            "controller",
            "gamepad",
            "dualshock",
            "dual sense",
            "dualsense",
            "xbox",
            "joy-con",
            "switch",
            "pro controller"
    );

    public ControllerInfo detect() {
        String forced = System.getenv(FORCE_FLAG);
        if ("1".equals(forced)) {
            return new ControllerInfo(true, Optional.of("development override"));
        }
        if ("0".equals(forced)) {
            return new ControllerInfo(false, Optional.empty());
        }

        ControllerInfo inputInfo = detectInputDevice();
        if (inputInfo.connected()) {
            return inputInfo;
        }
        return detectBluetoothDevice();
    }

    public boolean isControllerConnected() {
        return detect().connected();
    }

    private ControllerInfo detectInputDevice() {
        if (!Files.isDirectory(INPUT_BY_ID)) {
            return ControllerInfo.DISCONNECTED;
        }
        try {
            List<String> matches = new ArrayList<>();
            try (var stream = Files.list(INPUT_BY_ID)) {
                stream.filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
                            if (!name.contains("bluetooth")) {
                                return false;
                            }
                            return name.contains("-event-joystick")
                                    || name.contains("-event-gamepad")
                                    || name.contains("-gymotion");
                        })
                        .forEach(path -> matches.add(path.getFileName().toString()));
            }
            if (!matches.isEmpty()) {
                return new ControllerInfo(true, Optional.of(matches.get(0)));
            }
        } catch (IOException ignored) {
            // fall back to bluetoothctl detection
        }
        return ControllerInfo.DISCONNECTED;
    }

    private ControllerInfo detectBluetoothDevice() {
        ProcessBuilder builder = new ProcessBuilder("bluetoothctl", "devices", "Connected");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(BLUETOOTHCTL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ControllerInfo.DISCONNECTED;
            }
            try (InputStream stream = process.getInputStream()) {
                String output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (output.isEmpty()) {
                    return ControllerInfo.DISCONNECTED;
                }
                String lower = output.toLowerCase(Locale.ENGLISH);
                for (String keyword : CONTROLLER_KEYWORDS) {
                    if (lower.contains(keyword)) {
                        return new ControllerInfo(true, Optional.of(output.lines().findFirst().orElse("bluetooth controller")));
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // bluetoothctl not available; ignore
        }
        return ControllerInfo.DISCONNECTED;
    }

    public record ControllerInfo(boolean connected, Optional<String> label) {
        private static final ControllerInfo DISCONNECTED = new ControllerInfo(false, Optional.empty());

        public ControllerInfo(boolean connected, Optional<String> label) {
            this.connected = connected;
            this.label = label == null ? Optional.empty() : label;
        }
    }
}
