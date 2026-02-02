# Deployment Runbook

## Slice Deployment

### Deploy a New Slice

1. **Publish artifact to repository**
   ```bash
   mvn deploy -DaltDeploymentRepository=releases::http://repo.example.com/releases
   ```

2. **Create blueprint (first time only)**
   ```bash
   aether-cli --host node1:8080
   aether> blueprint create org.example:my-slice:1.0.0 --instances=3
   ```

3. **Verify deployment**
   ```bash
   aether> slices list | grep my-slice
   # Should show 3 ACTIVE instances
   ```

4. **Test endpoints**
   ```bash
   curl http://node1:8080/api/my-slice/health
   ```

### Update a Slice (Rolling Update)

1. **Publish new version**
   ```bash
   mvn deploy
   ```

2. **Update blueprint to new version**
   ```bash
   aether> blueprint update org.example:my-slice:1.1.0 --instances=3
   ```

3. **Monitor rollout**
   ```bash
   watch -n 2 'aether-cli --host node1:8080 -c "slices list" | grep my-slice'
   ```

   The cluster will:
   - Deploy new instances first
   - Activate new instances
   - Deactivate old instances
   - Unload old instances

4. **Verify new version active**
   ```bash
   curl http://node1:8080/api/my-slice/version
   ```

### Rollback

1. **Update blueprint to previous version**
   ```bash
   aether> blueprint update org.example:my-slice:1.0.0 --instances=3
   ```

2. **Verify rollback complete**
   ```bash
   aether> slices list | grep my-slice
   # Should show 1.0.0 version
   ```

## Node Deployment

### Deploy a New Node

1. **Prepare the server**
   ```bash
   # Install Java 21+
   apt install openjdk-21-jre

   # Create aether user
   useradd -r -s /bin/false aether
   ```

2. **Install Aether**
   ```bash
   mkdir -p /opt/aether
   cp aether-node.jar /opt/aether/
   chown -R aether:aether /opt/aether
   ```

3. **Configure systemd service**
   ```bash
   cat > /etc/systemd/system/aether.service << 'EOF'
   [Unit]
   Description=Aether Node
   After=network.target

   [Service]
   Type=simple
   User=aether
   ExecStart=/usr/bin/java -Xmx4g -jar /opt/aether/aether-node.jar \
     --node-id=${HOSTNAME} \
     --port=8090 \
     --peers=node1:8090,node2:8090,node3:8090
   Restart=always
   RestartSec=10

   [Install]
   WantedBy=multi-user.target
   EOF
   ```

4. **Start service**
   ```bash
   systemctl daemon-reload
   systemctl enable aether
   systemctl start aether
   ```

5. **Verify node joined cluster**
   ```bash
   curl http://localhost:8080/health
   ```

### Upgrade Aether Version

Rolling upgrade procedure (one node at a time):

1. **Verify cluster health**
   ```bash
   curl http://node1:8080/health
   # Must show quorum=true
   ```

2. **For each node (one at a time):**

   a. Stop the node
   ```bash
   systemctl stop aether
   ```

   b. Backup current version
   ```bash
   cp /opt/aether/aether-node.jar /opt/aether/aether-node.jar.backup
   ```

   c. Deploy new version
   ```bash
   cp aether-node-new.jar /opt/aether/aether-node.jar
   ```

   d. Start node
   ```bash
   systemctl start aether
   ```

   e. Wait for node to rejoin cluster
   ```bash
   while ! curl -s http://localhost:8080/health | grep -q '"quorum":true'; do
     sleep 5
   done
   ```

   f. Verify cluster health before proceeding to next node
   ```bash
   curl http://node1:8080/health
   ```

3. **Verify all nodes on new version**
   ```bash
   for node in node1 node2 node3; do
     echo -n "$node: "
     curl -s http://$node:8080/info | jq -r '.version'
   done
   ```

## TLS Configuration

### Enable TLS for New Cluster

1. **Generate certificates**
   ```bash
   # Using keytool for self-signed (dev/test only)
   keytool -genkeypair -alias aether -keyalg RSA -keysize 2048 \
     -keystore keystore.p12 -storetype PKCS12 \
     -validity 365 -dname "CN=aether,O=Example"

   # Or use your CA-signed certificates
   ```

2. **Configure node with TLS**
   ```java
   var tlsConfig = TlsConfig.fromFiles(
       "/path/to/keystore.p12",
       "keystorePassword",
       "/path/to/truststore.p12",
       "truststorePassword"
   );

   var config = AetherNodeConfig.aetherNodeConfig(...)
                                .withTls(tlsConfig);
   ```

3. **Verify TLS is active**
   ```bash
   # Should show HTTPS
   curl -k https://node1:8080/health
   ```

## Deployment Checklist

### Pre-Deployment
- [ ] Artifact published to repository
- [ ] New version tested in staging
- [ ] Rollback plan documented
- [ ] Monitoring alerts configured

### During Deployment
- [ ] Cluster health verified
- [ ] Deployment initiated
- [ ] Rollout monitored
- [ ] Functional tests passed

### Post-Deployment
- [ ] All instances active
- [ ] Endpoints responding
- [ ] No errors in logs
- [ ] Metrics within normal range
