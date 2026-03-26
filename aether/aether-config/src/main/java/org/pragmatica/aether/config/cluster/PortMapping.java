package org.pragmatica.aether.config.cluster;
/// Port mapping for cluster deployment.
///
/// @param cluster cluster communication port
/// @param management management API port
/// @param appHttp application HTTP port
/// @param swim SWIM protocol port
public record PortMapping(int cluster,
                          int management,
                          int appHttp,
                          int swim) {
    /// Factory method.
    public static PortMapping portMapping(int cluster, int management, int appHttp, int swim) {
        return new PortMapping(cluster, management, appHttp, swim);
    }

    /// Default port mapping per spec defaults.
    public static PortMapping defaultPortMapping() {
        return new PortMapping(8090, 8080, 8070, 8190);
    }
}
