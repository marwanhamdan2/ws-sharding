package com.example.mainApi;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.SrvRecord;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Placement Service - discovers WebSocket server pods and routes clients to the least loaded server.
 * Uses DNS SRV records to discover pods via the headless service.
 */
public class PlacementService {

    private static final Logger LOGGER = Logger.getLogger(PlacementService.class.getName());

    // Configuration from environment variables
    private static final String WS_HEADLESS_SERVICE = System.getenv().getOrDefault(
            "WS_HEADLESS_SERVICE", "ws-app-srv-headless.ws-app-ns.svc.cluster.local");
    private static final int WS_POD_PORT = Integer.parseInt(
            System.getenv().getOrDefault("WS_POD_PORT", "8080"));
    private static final long CACHE_TTL_MS = Long.parseLong(
            System.getenv().getOrDefault("CACHE_TTL_MS", "60000")); // 1 minute default

    private final Vertx vertx;
    private final WebClient webClient;
    private final DnsClient dnsClient;

    // Cache for room-to-server assignments
    private final Map<String, CachedAssignment> roomAssignments = new ConcurrentHashMap<>();

    private static class CachedAssignment {
        final String serverId;
        final String address;
        final long timestamp;

        CachedAssignment(String serverId, String address) {
            this.serverId = serverId;
            this.address = address;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public PlacementService(Vertx vertx) {
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx, new WebClientOptions()
                .setConnectTimeout(5000)
                .setIdleTimeout(10));
        this.dnsClient = vertx.createDnsClient();
        
        LOGGER.info("PlacementService initialized with headless service: " + WS_HEADLESS_SERVICE);
    }

    /**
     * Gets the best server for a room. Returns cached assignment if available,
     * otherwise discovers pods and selects the one with least connections.
     */
    public Future<JsonObject> getServerForRoom(String roomId) {
        // Check cache first
        CachedAssignment cached = roomAssignments.get(roomId);
        if (cached != null && !cached.isExpired()) {
            LOGGER.fine("Cache hit for room " + roomId + ": " + cached.serverId);
            return Future.succeededFuture(new JsonObject()
                    .put("room", roomId)
                    .put("serverId", cached.serverId)
                    .put("routingKey", cached.serverId));
        }

        // Discover pods and get metrics
        return getAllServerMetrics()
            .compose(metricsResult -> {
                JsonArray servers = metricsResult.getJsonArray("servers");
                
                if (servers == null || servers.isEmpty()) {
                    return Future.failedFuture("No healthy WebSocket servers available");
                }

                // Check if any server already has this room
                for (int i = 0; i < servers.size(); i++) {
                    JsonObject server = servers.getJsonObject(i);
                    JsonObject rooms = server.getJsonObject("rooms");
                    if (rooms != null && rooms.containsKey(roomId)) {
                        String serverId = server.getString("serverId");
                        String address = server.getString("address");
                        LOGGER.info("Room " + roomId + " already exists on server " + serverId);
                        
                        // Update cache
                        roomAssignments.put(roomId, new CachedAssignment(serverId, address));
                        
                        return Future.succeededFuture(new JsonObject()
                                .put("room", roomId)
                                .put("serverId", serverId)
                                .put("routingKey", serverId));
                    }
                }

                // Select server with least connections
                JsonObject bestServer = servers.getJsonObject(0);
                int minConnections = bestServer.getInteger("connectionCount", Integer.MAX_VALUE);
                
                for (int i = 1; i < servers.size(); i++) {
                    JsonObject server = servers.getJsonObject(i);
                    int connections = server.getInteger("connectionCount", Integer.MAX_VALUE);
                    if (connections < minConnections) {
                        minConnections = connections;
                        bestServer = server;
                    }
                }

                String serverId = bestServer.getString("serverId");
                String address = bestServer.getString("address");
                LOGGER.info("Selected server " + serverId + " for room " + roomId + 
                           " (" + minConnections + " connections)");

                // Update cache
                roomAssignments.put(roomId, new CachedAssignment(serverId, address));

                return Future.succeededFuture(new JsonObject()
                        .put("room", roomId)
                        .put("serverId", serverId)
                        .put("routingKey", serverId));
            });
    }

    /**
     * Discovers all WebSocket server pods and fetches their metrics.
     */
    public Future<JsonObject> getAllServerMetrics() {
        return discoverPods()
            .compose(pods -> {
                if (pods.isEmpty()) {
                    return Future.succeededFuture(new JsonObject()
                            .put("count", 0)
                            .put("servers", new JsonArray()));
                }

                LOGGER.info("Discovered " + pods.size() + " pods");

                // Fetch metrics from all pods in parallel
                List<Future<JsonObject>> metricsFutures = new ArrayList<>();
                for (PodInfo pod : pods) {
                    metricsFutures.add(fetchPodMetrics(pod));
                }

                return Future.all(metricsFutures)
                    .map(cf -> {
                        JsonArray servers = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            JsonObject result = cf.resultAt(i);
                            if (result != null) {
                                servers.add(result);
                            }
                        }
                        return new JsonObject()
                                .put("count", servers.size())
                                .put("servers", servers);
                    });
            });
    }

    /**
     * Clears the room assignment cache.
     */
    public void clearCache() {
        roomAssignments.clear();
        LOGGER.info("Room assignment cache cleared");
    }

    /**
     * Discovers WebSocket server pods using DNS SRV records.
     */
    private Future<List<PodInfo>> discoverPods() {
        return dnsClient.resolveSRV(WS_HEADLESS_SERVICE)
            .map(records -> {
                List<PodInfo> pods = new ArrayList<>();
                for (SrvRecord record : records) {
                    String podName = record.target().split("\\.")[0];
                    pods.add(new PodInfo(podName, record.target(), record.port()));
                }
                return pods;
            })
            .recover(err -> {
                LOGGER.warning("SRV lookup failed, trying A record fallback: " + err.getMessage());
                // Fallback to A record lookup
                return dnsClient.resolveA(WS_HEADLESS_SERVICE)
                    .map(ips -> {
                        List<PodInfo> pods = new ArrayList<>();
                        for (String ip : ips) {
                            pods.add(new PodInfo(ip, ip, WS_POD_PORT));
                        }
                        return pods;
                    });
            });
    }

    /**
     * Fetches metrics from a single WebSocket server pod.
     */
    private Future<JsonObject> fetchPodMetrics(PodInfo pod) {
        return webClient.get(pod.port, pod.address, "/metrics")
            .send()
            .map(response -> {
                if (response.statusCode() == 200) {
                    JsonObject metrics = response.bodyAsJsonObject();
                    return new JsonObject()
                            .put("serverId", metrics.getString("serverId", pod.name))
                            .put("address", pod.address)
                            .put("connectionCount", metrics.getInteger("connectionCount", 0))
                            .put("roomCount", metrics.getInteger("roomCount", 0))
                            .put("rooms", metrics.getJsonObject("rooms", new JsonObject()));
                } else {
                    LOGGER.warning("Failed to fetch metrics from " + pod.name + ": HTTP " + response.statusCode());
                    return null;
                }
            })
            .recover(err -> {
                LOGGER.warning("Failed to fetch metrics from " + pod.name + " (" + pod.address + "): " + err.getMessage());
                return Future.succeededFuture(null);
            });
    }

    private static class PodInfo {
        final String name;
        final String address;
        final int port;

        PodInfo(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }
    }
}
