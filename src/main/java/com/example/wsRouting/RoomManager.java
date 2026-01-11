package com.example.wsRouting;

import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {

  // RoomID -> (Connection -> Profile)
  private final ConcurrentHashMap<String, Map<ServerWebSocket, UserProfile>> rooms = new ConcurrentHashMap<>();

  public void joinRoom(String roomId, ServerWebSocket ws, UserProfile profile) {
    rooms.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(ws, profile);

    broadcast(roomId, new JsonObject()
            .put("type", "userJoined")
            .put("user", profile.toJson()).encode());

    ws.closeHandler(v -> leaveRoom(roomId, ws));
  }

  public void leaveRoom(String roomId, ServerWebSocket ws) {
    Map<ServerWebSocket, UserProfile> members = rooms.get(roomId);
    if (members != null) {
      UserProfile removedUser = members.remove(ws);
      if (removedUser != null) {
        broadcast(roomId, new JsonObject()
                .put("type", "userLeft")
                .put("userId", removedUser.id()).encode());
      }
      if (members.isEmpty()) {
        rooms.remove(roomId);
      }
    }
  }

  public JsonArray getUsersInRoom(String roomId) {
    JsonArray users = new JsonArray();
    Map<ServerWebSocket, UserProfile> members = rooms.getOrDefault(roomId, Collections.emptyMap());
    members.values().forEach(profile -> users.add(profile.toJson()));
    return users;
  }

  public void broadcast(String roomId, String message) {
    Set<ServerWebSocket> members = rooms.get(roomId).keySet();
    if (members != null) {
      for (ServerWebSocket ws : members) {
        if (!ws.isClosed()) {
          ws.writeTextMessage(message);
        }
      }
    }
  }

}
