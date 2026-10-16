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

## Production prerequisite: `[backup]` with a remote

A multi-core production cluster must configure `[backup]` with a `remote` **before** it takes production traffic. Backup is off by default in rc4.

**Why.** Consensus state lives in memory on the cores only. A core process that dies does not return under its old NodeId (the cluster refuses it while any peer remembers the old process), so if every core exits (for example a network cut that leaves no group with a majority — measured: cuts of 20, 25 and 30 s halted every core on five cores with every core isolated, Ember in-process; longer cuts not run — [No-majority partition](incident-response.md#no-majority-partition--every-core-halted)), blueprints, slice targets, API keys, cluster and stream configuration are gone. The leader's git backup of that declared state is the only thing a whole-cluster recovery restores from. With a `remote`, a recovery reads it from there; without one, it reads the local repository of whichever node leads the cold start, which needs that node's disk and may be older than another node's (`BACKUP_RESTORE_SOURCE_LOCAL`). The backup holds declared state only: entity state and acknowledged stream records are not recovered ([backup-recovery](backup-recovery.md)).

**Configure.**

```toml
[backup]
enabled = true
path = "/data/backups"
remote = "git@backups.example.com:ops/cluster-a-kv.git"
```

Put the section in the source's `node_config` (`[source.<name>.node_config.backup]`) so seeds and replacement cores boot with it, and give the node's user credentials for the remote: git never prompts, so a remote that needs credentials the process does not hold fails.

**Check that it is pushing.** The leader pushes; check on the current leader.

1. The leader logs `BACKUP_PUSH_FAILING` (WARN) when commits have been queued for 60 s without reaching the remote, and `BACKUP_RECOVERED` (INFO) when it is current again. It is a log line, not a cluster event: alert on it in your log pipeline. `[mechanism: BackupWarning.operatorWarningCode routes only restore, fork and config-missing codes to cluster events; KvBackupService.java:772-775]`
2. Compare the leader's local head with the remote's, as the node's user:
   ```bash
   git -C /data/backups/kv-backup rev-parse kv-backup
   git ls-remote git@backups.example.com:ops/cluster-a-kv.git refs/heads/kv-backup
   ```
   The two hashes are equal when everything committed has been pushed. On nodes started by `aether cluster bootstrap` in container mode the host directory `/opt/aether/backups` is bind-mounted at `path`, so on the host the repository is `/opt/aether/backups/kv-backup`. A change to declared state is flushed within about 5 s, so compare after a quiet moment.
3. Test the restore on a scratch cluster before production ([backup-recovery](backup-recovery.md)).

## Deployment Checklist

### Pre-Deployment
- [ ] Artifact published to repository
- [ ] New version tested in staging
- [ ] Rollback plan documented
- [ ] Monitoring alerts configured
- [ ] Multi-core production cluster: `[backup]` with a `remote` configured, `BACKUP_PUSH_FAILING` alerted on, restore tested (see above)

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
