package com.example.mainApi;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;

import java.util.logging.Logger;

/**
 * Main API Verticle - serves static content and provides the router/placement service
 * for directing WebSocket clients to the appropriate server.
 */
public class MainVerticle extends VerticleBase {

    private static final Logger LOGGER = Logger.getLogger(MainVerticle.class.getName());
    
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    private PlacementService placementService;

    @Override
    public Future<?> start() {
        // Initialize the placement service
        placementService = new PlacementService(vertx);

        HttpServer httpServer = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // Health check endpoint
        router.get("/api/health").respond(ctx -> 
            Future.succeededFuture(new JsonObject().put("status", "healthy"))
        );

        // Router/Placement API - get the best server for a room
        router.get("/api/get-server-for-room").respond(ctx -> {
            String roomId = ctx.queryParams().get("room");
            
            if (roomId == null || roomId.isBlank()) {
                ctx.response().setStatusCode(400);
                return Future.succeededFuture(new JsonObject().put("error", "Missing room parameter"));
            }

            return placementService.getServerForRoom(roomId)
                .map(result -> {
                    LOGGER.info("Assigned room " + roomId + " to server " + result.getString("serverId"));
                    return result;
                })
                .recover(err -> {
                    LOGGER.warning("Failed to get server for room " + roomId + ": " + err.getMessage());
                    ctx.response().setStatusCode(503);
                    return Future.succeededFuture(new JsonObject()
                        .put("error", "No servers available")
                        .put("message", err.getMessage()));
                });
        });

        // List all servers and their metrics (for debugging)
        router.get("/api/servers").respond(ctx -> 
            placementService.getAllServerMetrics()
                .recover(err -> {
                    ctx.response().setStatusCode(503);
                    return Future.succeededFuture(new JsonObject()
                        .put("error", "Failed to list servers")
                        .put("message", err.getMessage()));
                })
        );

        // Clear placement cache (for testing)
        router.post("/api/clear-cache").respond(ctx -> {
            placementService.clearCache();
            return Future.succeededFuture(new JsonObject().put("message", "Cache cleared"));
        });

        // Serve static files from webroot, with index.html as default
        router.route("/*").handler(StaticHandler.create("webroot").setIndexPage("index.html"));

        return httpServer.requestHandler(router).listen(PORT).onSuccess(http -> {
            LOGGER.info("Main API server started on port " + PORT);
        });
    }

    @Override
    public Future<?> stop() throws Exception {
        LOGGER.info("Stopping Main API Verticle");
        return super.stop();
    }
}
