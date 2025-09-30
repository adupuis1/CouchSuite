package app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HttpRepo {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final int DEFAULT_MAX_RETRIES = 3;
    public static final String DEFAULT_BASE_URL = "http://192.168.5.12:8080";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpRepo() {
    }

    public record UserProfile(int userId, String username, List<AppTile> apps, Map<String, Object> settings) {
    }

    public record UserPresence(boolean hasUsers) {
    }

    public static String fetchAppsJson(String baseUrl, Duration requestTimeout, int maxRetries) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase.endsWith("/apps") ? resolvedBase : resolvedBase + "/apps";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(requestTimeout)
                .GET()
                .header("Accept", "application/json")
                .build();
        return sendForBody(request, maxRetries);
    }

    public static List<AppTile> listDefaultApps(String baseUrl) throws Exception {
        String json = fetchAppsJson(baseUrl, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES);
        return parseApps(json);
    }

    public static UserPresence fetchUserPresence(String baseUrl) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/users/exists";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .header("Accept", "application/json")
                .build();
        String body = sendForBody(request, DEFAULT_MAX_RETRIES);
        JsonNode node = MAPPER.readTree(body);
        boolean hasUsers = node.path("has_users").asBoolean(node.path("hasUsers").asBoolean(false));
        return new UserPresence(hasUsers);
    }

    public static UserProfile register(String baseUrl, String username, String password) throws Exception {
        return sendUserRequest(baseUrl, "/users", username, password);
    }

    public static UserProfile login(String baseUrl, String username, String password) throws Exception {
        return sendUserRequest(baseUrl, "/auth/login", username, password);
    }

    public static List<AppTile> fetchUserApps(String baseUrl, int userId) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/users/" + userId + "/apps";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .build();
        String body = sendForBody(request, DEFAULT_MAX_RETRIES);
        JsonNode node = MAPPER.readTree(body);
        return parseAppsNode(node);
    }

    public static void updateInstalled(String baseUrl, int userId, String appId, boolean installed) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/users/" + userId + "/apps/" + appId;
        Map<String, Object> payload = new HashMap<>();
        payload.put("installed", installed);
        String json = MAPPER.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();
        sendForBody(request, DEFAULT_MAX_RETRIES);
    }

    public static void updateSettings(String baseUrl, int userId, Map<String, Object> settings) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/users/" + userId + "/settings";
        Map<String, Object> payload = Map.of("settings", settings);
        String json = MAPPER.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();
        sendForBody(request, DEFAULT_MAX_RETRIES);
    }

    public static List<AppTile> parseApps(String json) throws Exception {
        JsonNode body = MAPPER.readTree(json);
        return parseAppsNode(body);
    }

    private static String resolveBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return baseUrl;
    }

    private static UserProfile sendUserRequest(String baseUrl, String path, String username, String password) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + path;
        Map<String, Object> payload = Map.of(
                "username", username,
                "password", password
        );
        String json = MAPPER.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();
        String body = sendForBody(request, DEFAULT_MAX_RETRIES);
        return parseUserProfile(body);
    }

    private static String sendForBody(HttpRequest request, int maxRetries) throws Exception {
        int attempts = Math.max(1, maxRetries);
        long backoffMillis = 250;
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                throw new IllegalStateException("Request failed with status " + status + " for " + request.uri());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception ex) {
                lastError = ex;
                if (attempt >= attempts) {
                    break;
                }
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                backoffMillis = Math.min(500, backoffMillis + 100);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Failed to execute request " + request.uri());
    }

    private static UserProfile parseUserProfile(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        int userId = node.path("user_id").asInt(node.path("userId").asInt(-1));
        if (userId < 0) {
            throw new IllegalStateException("Missing user id in response");
        }
        String username = node.path("username").asText("");
        JsonNode settingsNode = node.path("settings");
        Map<String, Object> settings = settingsNode.isMissingNode()
                ? Collections.emptyMap()
                : MAPPER.convertValue(settingsNode, new TypeReference<>() {
        });
        List<AppTile> apps = node.has("apps") ? parseAppsNode(node.get("apps")) : Collections.emptyList();
        return new UserProfile(userId, username, apps, settings);
    }

    private static List<AppTile> parseAppsNode(JsonNode node) throws Exception {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        List<AppTile> tiles = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode entry : node) {
                tiles.add(toTile(entry));
            }
            return tiles;
        }
        if (node.has("data") && node.get("data").isArray()) {
            for (JsonNode entry : node.get("data")) {
                tiles.add(toTile(entry));
            }
            return tiles;
        }
        if (node.isObject()) {
            List<Map<String, Object>> mapList = MAPPER.convertValue(
                    node,
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            );
            for (Map<String, Object> entry : mapList) {
                tiles.add(toTile(MAPPER.valueToTree(entry)));
            }
            return tiles;
        }
        throw new IllegalStateException("Unsupported apps payload format");
    }

    private static AppTile toTile(JsonNode node) {
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("App tile missing id");
        }
        String name = node.path("name").asText(id);
        String moonlightName = node.path("moonlight_name").asText(name);
        boolean enabled = node.path("enabled").asBoolean(true);
        int sortOrder = node.path("sort_order").asInt(100);
        boolean installed = node.path("installed").asBoolean(enabled);
        return new AppTile(id, name, moonlightName, enabled, sortOrder, installed);
    }
}
