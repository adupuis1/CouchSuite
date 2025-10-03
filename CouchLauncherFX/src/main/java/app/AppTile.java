package app;

public class AppTile {
    public final String id;
    public final String name;
    public final String moonlightName;
    public final boolean enabled;
    public final int sortOrder;
    public final boolean installed;
    public final boolean owned;
    public final Integer chartRank;
    public final String chartDate;
    public final String description;
    public final String coverUrl;
    public final Integer steamAppId;
    public final Integer gameId;

    public AppTile(String id, String name, String moonlightName) {
        this(id, name, moonlightName, true, 100, false, false, null, null, null, null, null, null);
    }

    public AppTile(String id, String name, String moonlightName, boolean enabled, int sortOrder, boolean installed) {
        this(id, name, moonlightName, enabled, sortOrder, installed, false, null, null, null, null, null, null);
    }

    public AppTile(String id,
                   String name,
                   String moonlightName,
                   boolean enabled,
                   int sortOrder,
                   boolean installed,
                   boolean owned,
                   Integer chartRank,
                   String chartDate,
                   String description,
                   String coverUrl,
                   Integer steamAppId,
                   Integer gameId) {
        this.id = id;
        this.name = name;
        this.moonlightName = moonlightName;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.installed = installed;
        this.owned = owned;
        this.chartRank = chartRank;
        this.chartDate = chartDate;
        this.description = description;
        this.coverUrl = coverUrl;
        this.steamAppId = steamAppId;
        this.gameId = gameId;
    }

    public AppTile withOwnership(boolean newOwned, boolean installReady) {
        return new AppTile(id, name, moonlightName, enabled, sortOrder, installReady, newOwned, chartRank, chartDate, description, coverUrl, steamAppId, gameId);
    }

    public boolean playable() {
        return enabled && installed && owned;
    }
}
