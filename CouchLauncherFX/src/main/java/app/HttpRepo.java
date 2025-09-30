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
import java.util.List;
import java.util.Map;

public final class HttpRepo {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpRepo() {
    }

    public static List<AppTile> listApps(String baseUrl) throws Exception {
        String resolvedBase = (baseUrl == null || baseUrl.isBlank())
                ? System.getenv().getOrDefault("COUCH_API", "http://127.0.0.1:8080")
                : baseUrl;

        String target = resolvedBase.endsWith("/apps") ? resolvedBase : resolvedBase + "/apps";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Failed to load apps (" + response.statusCode() + ") from " + target);
        }

        JsonNode body = MAPPER.readTree(response.body());
        List<AppTile> tiles = new ArrayList<>();

        if (body.isArray()) {
            for (JsonNode node : body) {
                tiles.add(toTile(node));
            }
            return tiles;
        }

        // fallback for objects with "data": [] or map style
        if (body.has("data") && body.get("data").isArray()) {
            for (JsonNode node : body.get("data")) {
                tiles.add(toTile(node));
            }
            return tiles;
        }

        if (body.isObject()) {
            List<Map<String, Object>> mapList = MAPPER.convertValue(
                    body,
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            );
            for (Map<String, Object> entry : mapList) {
                tiles.add(toTile(MAPPER.valueToTree(entry)));
            }
            return tiles;
        }

        throw new IllegalStateException("Unsupported apps payload format from " + target);
    }

    private static AppTile toTile(JsonNode node) {
        String id = getText(node, "id");
        String name = getText(node, "name");
        String moonlightName = getText(node, "moonlight_name");
        if (moonlightName == null || moonlightName.isBlank()) {
            moonlightName = name;
        }
        return new AppTile(id, name, moonlightName);
    }

    private static String getText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
