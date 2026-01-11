
package com.example.wsRouting;

import io.vertx.core.json.JsonObject;

public record UserProfile(String id, String name) {
    public JsonObject toJson() {
        return new JsonObject().put("id", id).put("name", name);
    }
}