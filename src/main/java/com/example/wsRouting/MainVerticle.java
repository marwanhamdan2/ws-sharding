package com.example.wsRouting;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocketHandshake;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainVerticle extends VerticleBase {

  public static final String SERVER_ID = System.getenv("HOSTNAME") != null
      ? System.getenv("HOSTNAME")
      : "local-" + UUID.randomUUID().toString().substring(0, 8);
  
  private Map<String, String> parseQueryParams(String query) {
    Map<String, String> params = new HashMap<>();
    if (query != null) {
      for (String param : query.split("&")) {
        String[] pair = param.split("=", 2);
        if (pair.length == 2) {
          params.put(pair[0], pair[1]);
        }
      }
    }
    return params;
  }
  
  public void validateWebsocketHandshake(ServerWebSocketHandshake handshake, Handler<AsyncResult<Map<String, String>>> resultHandler) {
    if (!"/websocket".equals(handshake.path())) {
      resultHandler.handle(Future.failedFuture("Invalid Path: " + handshake.path()));
      return;
    }

    Map<String, String> params = parseQueryParams(handshake.query());
    String roomId = params.get("room");

    if (roomId != null && !roomId.isBlank()) {
      resultHandler.handle(Future.succeededFuture(params));
    } else {
      resultHandler.handle(Future.failedFuture("Missing or empty 'room' parameter"));
    }
  }

  private final RoomManager roomManager = new RoomManager();

  @Override
  public Future<?> start() {
    HttpServer httpServer = vertx.createHttpServer();
    Router router = Router.router(vertx);

    router.get("/info").respond(ctx -> {
      return Future.succeededFuture(new JsonObject().put("serverId", SERVER_ID));
    });

    // Serve static files from webroot, with index.html as default
    router.route("/*").handler(StaticHandler.create("webroot").setIndexPage("index.html"));

    httpServer.webSocketHandshakeHandler(handshake -> {
      validateWebsocketHandshake(handshake, ar -> {
        if (ar.succeeded()) {
          Map<String, String> params = ar.result();
          String roomId = params.get("room");
          // Get user info from query params (since WebSocket API doesn't support custom headers)
          String userId = params.getOrDefault("userId", handshake.headers().get("x-user-id"));
          String userName = params.getOrDefault("userName", handshake.headers().get("x-user-name"));
          
          // URL decode the values
          try {
            if (userId != null) userId = java.net.URLDecoder.decode(userId, "UTF-8");
            if (userName != null) userName = java.net.URLDecoder.decode(userName, "UTF-8");
          } catch (Exception e) {
            // ignore decoding errors
          }
          
          System.out.println("ws connection for room: " + roomId + ", user: " + userName);
          
          final String finalUserId = userId;
          final String finalUserName = userName;
          
          // Accept upgrade, switch protocol, 101 response
          handshake.accept().onSuccess(ws -> {
            UserProfile profile = new UserProfile(finalUserId, finalUserName);
            roomManager.joinRoom(roomId, ws, profile);

            ws.writeTextMessage(new JsonObject()
                .put("type", "welcome")
                .put("serverId", SERVER_ID)
                .put("roomId", roomId)
                .put("message", "Connected to server " + SERVER_ID)
                .encode());
            
            ws.textMessageHandler(msg -> {
              if (msg.equalsIgnoreCase("getAllUsers")) {
                JsonArray users = roomManager.getUsersInRoom(roomId);
                ws.writeTextMessage(new JsonObject().put("type", "userList").put("data", users).encode());
              } else {
                roomManager.broadcast(roomId, new JsonObject()
                        .put("type", "chat")
                        .put("from", finalUserName)
                        .put("text", msg).encode());
              }
            });
          });
        } else {
          System.out.println(ar.cause().getMessage());
          handshake.reject(500);
        }
      });
    }
    );

    return httpServer.requestHandler(router).listen(8080).onSuccess(http -> {
      System.out.println("HTTP server started on port 8080");
    });
  }
}
