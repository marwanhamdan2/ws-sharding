package com.example.wsServer.Utils;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for managing Kubernetes/Istio resources for pod-level ingress routing.
 * Creates VirtualService and ServiceEntry resources on startup and destroys them on shutdown.
 */
public class KubernetesUtils {

    private static final Logger LOGGER = Logger.getLogger(KubernetesUtils.class.getName());

    private static final String VIRTUAL_SERVICE_TEMPLATE = "/k8s/pod-ingress-istio-vs.yaml";
    private static final String SERVICE_ENTRY_TEMPLATE = "/k8s/pod-ingress-istio-service-entry.yaml";

    private static final ResourceDefinitionContext VIRTUAL_SERVICE_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("networking.istio.io")
            .withVersion("v1alpha3")
            .withKind("VirtualService")
            .withPlural("virtualservices")
            .withNamespaced(true)
            .build();

    private static final ResourceDefinitionContext SERVICE_ENTRY_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("networking.istio.io")
            .withVersion("v1alpha3")
            .withKind("ServiceEntry")
            .withPlural("serviceentries")
            .withNamespaced(true)
            .build();

    private final KubernetesClient client;
    private final String podId;
    private final String podIp;
    private final String namespace;
    private final String ingressDomain;
    private final String istioIngressGateway;

    private String virtualServiceName;
    private String serviceEntryName;

    /**
     * Creates a new KubernetesUtils instance with configuration from environment variables.
     * 
     * Environment variables:
     * - HOSTNAME: Pod ID (required, typically set by Kubernetes)
     * - POD_IP: Pod IP address (optional, will be auto-detected if not set)
     * - NAMESPACE: Kubernetes namespace (defaults to "default")
     * - INGRESS_DOMAIN: Domain for the ingress gateway (defaults to "*")
     * - ISTIO_INGRESS_GATEWAY: Istio ingress gateway name (defaults to "istio-system/istio-ingressgateway")
     */
    public KubernetesUtils() {
        this.client = new KubernetesClientBuilder().build();
        this.podId = System.getenv("HOSTNAME");
        this.podIp = getPodIp();
        this.namespace = getEnvOrDefault("NAMESPACE", "default");
        this.ingressDomain = getEnvOrDefault("INGRESS_DOMAIN", "*");
        this.istioIngressGateway = getEnvOrDefault("ISTIO_INGRESS_GATEWAY", "istio-system/istio-ingressgateway");
    }

    /**
     * Checks if the application is running inside a Kubernetes cluster.
     * Detection is based on the presence of Kubernetes service account token or KUBERNETES_SERVICE_HOST env var.
     * 
     * @return true if running in Kubernetes, false otherwise
     */
    public static boolean isRunningInKubernetes() {
        // Check for Kubernetes service host environment variable
        String k8sServiceHost = System.getenv("KUBERNETES_SERVICE_HOST");
        if (k8sServiceHost != null && !k8sServiceHost.isEmpty()) {
            return true;
        }

        // Check for service account token file
        java.io.File tokenFile = new java.io.File("/var/run/secrets/kubernetes.io/serviceaccount/token");
        return tokenFile.exists();
    }

    /**
     * Creates Istio VirtualService and ServiceEntry resources for this pod.
     * These resources enable direct routing to the pod via the Istio ingress gateway.
     * 
     * @throws RuntimeException if resource creation fails
     */
    public void createIstioResources() {
        if (podId == null || podId.isEmpty()) {
            LOGGER.warning("POD_ID (HOSTNAME) not set, skipping Istio resource creation");
            return;
        }

        LOGGER.info("Creating Istio resources for pod: " + podId + " with IP: " + podIp);

        try {
            // Create VirtualService
            String vsYaml = loadAndProcessTemplate(VIRTUAL_SERVICE_TEMPLATE);
            GenericKubernetesResource vs = client.genericKubernetesResources(VIRTUAL_SERVICE_CONTEXT)
                    .inNamespace(namespace)
                    .load(new java.io.ByteArrayInputStream(vsYaml.getBytes(StandardCharsets.UTF_8)))
                    .item();
            
            client.genericKubernetesResources(VIRTUAL_SERVICE_CONTEXT)
                    .inNamespace(namespace)
                    .resource(vs)
                    .createOrReplace();
            
            virtualServiceName = "pods-" + podId + "-virtual-service";
            LOGGER.info("Created VirtualService: " + virtualServiceName);

            // Create ServiceEntry
            String seYaml = loadAndProcessTemplate(SERVICE_ENTRY_TEMPLATE);
            GenericKubernetesResource se = client.genericKubernetesResources(SERVICE_ENTRY_CONTEXT)
                    .inNamespace(namespace)
                    .load(new java.io.ByteArrayInputStream(seYaml.getBytes(StandardCharsets.UTF_8)))
                    .item();
            
            client.genericKubernetesResources(SERVICE_ENTRY_CONTEXT)
                    .inNamespace(namespace)
                    .resource(se)
                    .createOrReplace();
            
            serviceEntryName = "pods-" + podId + "-service-entry";
            LOGGER.info("Created ServiceEntry: " + serviceEntryName);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create Istio resources", e);
            throw new RuntimeException("Failed to create Istio resources", e);
        }
    }

    /**
     * Destroys the Istio VirtualService and ServiceEntry resources created for this pod.
     * Should be called during application shutdown.
     */
    public void destroyIstioResources() {
        if (podId == null || podId.isEmpty()) {
            LOGGER.warning("POD_ID (HOSTNAME) not set, skipping Istio resource destruction");
            return;
        }

        LOGGER.info("Destroying Istio resources for pod: " + podId);

        try {
            // Delete VirtualService
            if (virtualServiceName != null) {
                client.genericKubernetesResources(VIRTUAL_SERVICE_CONTEXT)
                        .inNamespace(namespace)
                        .withName(virtualServiceName)
                        .delete();
                LOGGER.info("Deleted VirtualService: " + virtualServiceName);
            }

            // Delete ServiceEntry
            if (serviceEntryName != null) {
                client.genericKubernetesResources(SERVICE_ENTRY_CONTEXT)
                        .inNamespace(namespace)
                        .withName(serviceEntryName)
                        .delete();
                LOGGER.info("Deleted ServiceEntry: " + serviceEntryName);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to destroy Istio resources (may already be deleted)", e);
        }
    }

    /**
     * Closes the Kubernetes client connection.
     */
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Loads a YAML template from resources and replaces placeholders with actual values.
     */
    private String loadAndProcessTemplate(String templatePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(templatePath)) {
            if (is == null) {
                throw new IOException("Template not found: " + templatePath);
            }
            String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return template
                    .replace("{{POD_ID}}", podId)
                    .replace("{{POD_IP}}", podIp)
                    .replace("{{NAMESPACE}}", namespace)
                    .replace("{{INGRESS_DOMAIN}}", ingressDomain)
                    .replace("{{ISTIO_INGRESS_GATEWAY}}", istioIngressGateway);
        }
    }

    /**
     * Gets the pod IP from environment variable or auto-detects it.
     */
    private String getPodIp() {
        String envPodIp = System.getenv("POD_IP");
        if (envPodIp != null && !envPodIp.isEmpty()) {
            return envPodIp;
        }

        // Try to auto-detect the IP
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            LOGGER.warning("Could not auto-detect pod IP: " + e.getMessage());
            return "127.0.0.1";
        }
    }

    /**
     * Gets an environment variable value or returns a default.
     */
    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    // Getters for testing and debugging
    public String getPodId() {
        return podId;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getVirtualServiceName() {
        return virtualServiceName;
    }

    public String getServiceEntryName() {
        return serviceEntryName;
    }
}
