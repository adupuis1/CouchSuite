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

    public record OrgSummary(int id, String slug, String name, String role) {}

    public record UserProfile(int userId,
                              String username,
                              List<AppTile> apps,
                              Map<String, Object> settings,
                              List<OrgSummary> orgs,
                              String token) {

        public Integer primaryOrgId() {
            if (orgs == null || orgs.isEmpty()) {
                return null;
            }
            return orgs.get(0).id();
        }
    }

    public record UserPresence(boolean hasUsers) {
    }

    public record CatalogResult(List<AppTile> tiles, boolean fromCache, String rawJson) {}

    private record LibraryRecord(String slug, int gameId, boolean installReady) {
        String slugKey() {
            return slug != null && !slug.isBlank() ? slug : null;
        }

        String gameKey() {
            return "game-" + gameId;
        }
    }

    public record SessionResponse(int id, String status, String streamUrl) {}

    public static String fetchChartsJson(String baseUrl, Duration requestTimeout, int maxRetries) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/charts/top10";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(requestTimeout)
                .GET()
                .header("Accept", "application/json")
                .build();
        return sendForBody(request, maxRetries);
    }

    public static List<AppTile> listDefaultApps(String baseUrl) throws Exception {
        String json = fetchChartsJson(baseUrl, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES);
        return parseApps(json);
    }

    public static CatalogResult loadCatalog(String baseUrl, Integer userId, Integer orgId, Duration timeout, int retries) throws Exception {
        String json = fetchChartsJson(baseUrl, timeout, retries);
        List<AppTile> charts = parseApps(json);
        if (userId != null && orgId != null) {
            List<AppTile> merged = mergeWithLibrary(baseUrl, charts, userId, orgId);
            return new CatalogResult(merged, false, json);
        }
        return new CatalogResult(charts, false, json);
    }

    public static List<AppTile> mergeWithLibrary(String baseUrl, List<AppTile> source, int userId, int orgId) throws Exception {
        Map<String, LibraryRecord> records = fetchLibraryMap(baseUrl, userId, orgId);
        List<AppTile> merged = new ArrayList<>(source.size());
        for (AppTile tile : source) {
            LibraryRecord record = records.get(tile.id);
            if (record == null && tile.gameId != null) {
                record = records.get("game-" + tile.gameId);
            }
            if (record != null) {
                boolean installReady = tile.installed || record.installReady();
                merged.add(tile.withOwnership(true, installReady));
            } else {
                merged.add(tile.withOwnership(false, tile.installed));
            }
        }
        return merged;
    }

    private static Map<String, LibraryRecord> fetchLibraryMap(String baseUrl, int userId, int orgId) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/users/" + userId + "/library?org_id=" + orgId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .build();
        String body = sendForBody(request, DEFAULT_MAX_RETRIES);
        JsonNode node = MAPPER.readTree(body);
        Map<String, LibraryRecord> map = new HashMap<>();
        if (!node.isArray()) {
            return map;
        }
        for (JsonNode entry : node) {
            JsonNode gameNode = entry.path("game");
            String slug = gameNode.path("slug").asText(null);
            int gameId = gameNode.path("id").asInt(-1);
            boolean installReady = entry.path("install_ready").asBoolean(false);
            if (gameId < 0) {
                continue;
            }
            LibraryRecord record = new LibraryRecord(slug, gameId, installReady);
            if (record.slugKey() != null) {
                map.put(record.slugKey(), record);
            }
            map.put(record.gameKey(), record);
        }
        return map;
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

    public static SessionResponse startSession(String baseUrl, int orgId, int userId, int gameId) throws Exception {
        String resolvedBase = resolveBase(baseUrl);
        String target = resolvedBase + "/sessions";
        Map<String, Object> payload = Map.of(
                "org_id", orgId,
                "user_id", userId,
                "game_id", gameId
        );
        String json = MAPPER.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();
        String body = sendForBody(request, DEFAULT_MAX_RETRIES);
        JsonNode node = MAPPER.readTree(body);
        int id = node.path("id").asInt();
        String status = node.path("status").asText("provisioning");
        String streamUrl = node.path("stream_url").isMissingNode() ? null : node.get("stream_url").asText(null);
        return new SessionResponse(id, status, streamUrl);
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
        List<OrgSummary> orgs = new ArrayList<>();
        JsonNode orgNode = node.path("orgs");
        if (orgNode.isArray()) {
            for (JsonNode entry : orgNode) {
                OrgSummary summary = new OrgSummary(
                        entry.path("id").asInt(-1),
                        entry.path("slug").asText(""),
                        entry.path("name").asText(""),
                        entry.path("role").asText("member")
                );
                orgs.add(summary);
            }
        }
        String token = node.path("token").asText("");
        return new UserProfile(userId, username, apps, settings, orgs, token);
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
        int sortOrder = node.path("sort_order").asInt(node.path("chart_rank").asInt(100));
        boolean installed = node.path("installed").asBoolean(false);
        boolean owned = node.path("owned").asBoolean(false);
        Integer chartRank = node.hasNonNull("chart_rank") ? node.get("chart_rank").asInt() : null;
        String chartDate = node.hasNonNull("chart_date") ? node.get("chart_date").asText() : null;
        String description = node.hasNonNull("description") ? node.get("description").asText() : null;
        String coverUrl = node.hasNonNull("cover_url") ? node.get("cover_url").asText() : null;
        Integer steamAppId = node.hasNonNull("steam_appid") ? node.get("steam_appid").asInt() : null;
        Integer gameId = node.hasNonNull("game_id") ? node.get("game_id").asInt() : null;
        return new AppTile(id, name, moonlightName, enabled, sortOrder, installed, owned, chartRank, chartDate, description, coverUrl, steamAppId, gameId);
    }
}
