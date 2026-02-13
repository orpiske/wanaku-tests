package ai.wanaku.test.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Information about a registered tool (returned from Router API).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolInfo {

    private String name;
    private String description;
    private String type;

    public ToolInfo() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "ToolInfo{name='" + name + "', type='" + type + "'}";
    }
}
