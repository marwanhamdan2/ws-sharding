# build container image
build:
    docker build -t localhost:32000/ws-app-image:latest -f ops/Dockerfile .

# push to microk8s registry (nodeport 32000)
push:
    podman push localhost:32000/ws-app-image:latest --tls-verify=false

# Deploy to microk8s
deploy:
    microk8s kubectl apply -f ops/k8s.yaml


# linux commands to setup local microk8s cluster for this experiment 
setup-microk8s:
    sudo snap install microk8s --classic
    sudo usermod -a -G microk8s $USER
    mkdir -p ~/.kube
    sudo chown -f -R $USER ~/.kube
    microk8s status --wait-ready
    microk8s enable dns hostpath-storage registry


# install istio as deamonset for easier access
setup-istio:
    microk8s helm repo add istio https://istio-release.storage.googleapis.com/charts
    microk8s helm repo update

    microk8s helm install istio-base istio/base -n istio-system --create-namespace --set defaultRevision=default
    microk8s helm install istiod istio/istiod -n istio-system --wait

    microk8s helm upgrade --install istio-ingress istio/gateway -n istio-system \
      --set service.type=NodePort \
      --set "service.ports[0].name=http2" \
      --set "service.ports[0].port=80" \
      --set "service.ports[0].targetPort=80" \
      --set "service.ports[0].nodePort=30080" \
      --set "service.ports[1].name=https" \
      --set "service.ports[1].port=443" \
      --set "service.ports[1].targetPort=443" \
      --set "service.ports[1].nodePort=30443" \
      --wait



# ip address of istio ingress clusterIP, so we can add dns mapping record at 
# /etc/hosts
# example: 10.5.4.6 ws-app.local
map-microk8s-local-dns:
    #!/usr/bin/env bash
    set -e
    INGRESS_IP=$(microk8s kubectl get svc -n istio-system istio-ingress -o jsonpath='{.spec.clusterIP}')
    
    if [ -z "$INGRESS_IP" ]; then
        echo "error: Could not find Istio Ingress ClusterIP"
        exit 1
    fi

    echo "ClusterIP: $INGRESS_IP"

    if grep -q "ws-app.local" /etc/hosts; then
        echo "updating Existing entry in /etc/hosts..."
        sudo sed -i "s/.*ws-app.local/$INGRESS_IP ws-app.local/" /etc/hosts
    else
        echo "Adding new entry to /etc/hosts..."
        echo "$INGRESS_IP ws-app.local" | sudo tee -a /etc/hosts > /dev/null
    fi
    echo "access at http://ws-app.local"


all: build push deploy