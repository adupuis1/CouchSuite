package app;

public class AppTile {
    public final String id;
    public final String name;
    public final String moonlightName;
    public final boolean enabled;
    public final int sortOrder;
    public final boolean installed;

    public AppTile(String id, String name, String moonlightName) {
        this(id, name, moonlightName, true, 100, true);
    }

    public AppTile(String id, String name, String moonlightName, boolean enabled, int sortOrder, boolean installed) {
        this.id = id;
        this.name = name;
        this.moonlightName = moonlightName;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.installed = installed;
    }
}
