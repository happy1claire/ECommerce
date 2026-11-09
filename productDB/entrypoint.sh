#!/bin/bash

# ---------------------------------------------------------
# 1. Get MY OWN Private IP (Self Discovery)
# ---------------------------------------------------------
# We use the internal ECS metadata endpoint. 
# This is faster and safer than asking the AWS CLI.
echo "Retrieving Task Metadata..."
MY_PRIVATE_IP=$(curl -s $ECS_CONTAINER_METADATA_URI_V4 | jq -r '.Networks[0].IPv4Addresses[0]')

echo "MY PRIVATE IP is: $MY_PRIVATE_IP"

# ---------------------------------------------------------
# 2. Get PEER Private IPs (Peer Discovery)
# ---------------------------------------------------------
# We use AWS CLI to find everyone else in the service.
echo "Discovering Peers..."

# Get list of tasks
# Cluster and service names come from the task definition (Terraform sets
# ECS_CLUSTER_NAME / ECS_SERVICE_NAME); the old hardcoded names are only defaults.
CLUSTER_NAME="${ECS_CLUSTER_NAME:-my-cluster}"
SERVICE_NAME="${ECS_SERVICE_NAME:-my-db-service}"
TASK_ARNS=$(aws ecs list-tasks --cluster "$CLUSTER_NAME" --service-name "$SERVICE_NAME" --query 'taskArns[*]' --output text)

# Get details for those tasks
# We specifically ask for 'privateIPv4Address' to ensure we don't get the public one.
PEER_IPS=$(aws ecs describe-tasks --cluster "$CLUSTER_NAME" --tasks $TASK_ARNS \
  --query 'tasks[].attachments[].details[?name==`privateIPv4Address`].value' \
  --output text)

# Convert space-separated list to comma-separated (e.g., "10.0.0.1,10.0.0.2")
PEER_LIST_COMMA=$(echo $PEER_IPS | tr ' ' ',')

echo "Found Peers: $PEER_LIST_COMMA"

# ---------------------------------------------------------
# 3. Launch Java Application
# ---------------------------------------------------------
# We start the app in the background so we can send it API requests immediately
java -jar app.jar &
APP_PID=$!

# Wait a few seconds for Spring Boot/Java to start up
echo "Waiting for DB to start..."
sleep 15 

# ---------------------------------------------------------
# 4. Configure the Application via your API
# ---------------------------------------------------------

# A. Tell the node WHO IT IS (using Private IP)
#    Port 8080 is assumed; change if your app uses a different port.
curl -X POST http://localhost:8080/config/self \
     -H "Content-Type: application/json" \
     -d "{\"url\": \"http://$MY_PRIVATE_IP:8080\"}"

# B. Tell the node WHO THE PEERS ARE (using Private IPs)
#    We construct a JSON array: ["http://10.0.0.1:8080", "http://10.0.0.2:8080", ...]
#    (This little bit of awk/sed magic formats the IPs into a JSON list)
JSON_PEERS=$(echo $PEER_IPS | awk -v port=":8080" '{
  printf "["
  for (i=1; i<=NF; i++) {
    printf "\"http://%s%s\"", $i, port
    if (i<NF) printf ", "
  }
  printf "]"
}')

curl -X POST http://localhost:8080/config/peers \
     -H "Content-Type: application/json" \
     -d "$JSON_PEERS"

# ---------------------------------------------------------
# 5. Keep Container Alive
# ---------------------------------------------------------
wait $APP_PID