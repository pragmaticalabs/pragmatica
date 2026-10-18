### Fixed
- HTTP/1.1 response writers expose response-flush completion (`ResponseWriter.writeAsync`) for graceful application shutdown. (TLS fail-closed startup and the reported bound port already shipped in rc4 via #2025.)
