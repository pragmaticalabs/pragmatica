# Deployment Runbook

## Slice Deployment

### Deploy a New Slice

1. **Publish artifact to repository**
   ```bash
   mvn deploy -DaltDeploymentRepository=releases::http://repo.example.com/releases
   ```

2. **Create blueprint (first time only)**
   ```bash
   aether --connect node1:8080
   aether> blueprint create org.example:my-slice:1.0.0 --instances=3
   ```

3. **Verify deployment**
   ```bash
   aether> slices | grep my-slice
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
   watch -n 2 'aether --connect node1:8080 slices | grep my-slice'
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
   # Install Java 25+
   apt install openjdk-25-jre

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
   ExecStart=/usr/bin/java -XX:+ExitOnOutOfMemoryError -Xmx4g -jar /opt/aether/aether-node.jar \
     --node-id=%H \
     --port=8090 \
     --peers=node1:8090,node2:8090,node3:8090
   # Restart must stay "no": a crashed node must not rejoin under the same id.
   # Recovery is a replacement with a NEW node id — see deployment-recovery.md §1 and §4.5.
   # %H is the hostname, so this id is tied to the host: once the node has been removed (a crash,
   # or a reboot long enough to be declared FAULTY), this host can never rejoin under it (#1467).
   Restart=no

   [Install]
   WantedBy=multi-user.target
   EOF
   ```

4. **Start service**
   ```bash
   systemctl daemon-reload
   systemctl start aether
   ```
   Do **not** `systemctl enable` this unit. Start-on-boot would relaunch a rebooted host under its old
   `%H` id. By then the cluster has removed that id, refuses the rejoin, and the node starts but never
   joins (#1467). After a reboot or rebuild, bring the host back as a replacement node with a new
   `--node-id`.

5. **Verify node joined cluster**
   ```bash
   curl http://localhost:8080/health
   ```

### Upgrade Aether Version

Upgrade by rolling replacement, not by restarting nodes. A node id never returns: stopping a node,
copying a new jar over it and starting it again under the same id is refused by the cluster (fresh
boot token). Each node is replaced under a **new** NodeId, one at a time:

1. **Verify cluster health**
   ```bash
   curl http://node1:8080/health
   # Must show quorum=true
   ```

2. **Run the rolling upgrade**
   ```bash
   aether cluster upgrade --version <X.Y.Z> --wait
   ```
   The leader replaces every node not on the target version, one at a time (cores first, the current
   leader last among them, then workers). Follow it with `aether cluster upgrade-status`; pause,
   resume or abort with `aether cluster upgrade-pause|upgrade-resume|upgrade-abort`. Workers are
   replaced serially (one live replacement cluster-wide); parallel batches come in a later release.
   See the [Rolling Upgrade guide](../../guides/rolling-upgrade.md) for failure handling.

   To replace a single node (for example a failed one) outside an upgrade:
   ```bash
   curl -X POST http://node1:8080/api/v1/nodes/replace/<node-id>
   ```
   The leader provisions the new node under a fresh id. To start it yourself, name a fresh id with
   `-d '{"replacement": "<fresh id>"}'` and launch the node under that id. Do not `systemctl start`
   the old unit again. See
   [`POST /api/v1/nodes/replace/{id}`](../../reference/management-api.md#post-apiv1nodesreplaceid).

3. **Verify all nodes on new version**
   ```bash
   curl -s http://node1:8080/api/v1/nodes/lifecycle | jq '.[] | {nodeId, version}'
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
