package org.pragmatica.scriptgate;

/// A `curl` stand-in for scripts that talk to the Hetzner API, so no test here can reach a real cloud API.
///
/// Answers come from files in `$STUB_STATE`: `servers.json`, `ssh_keys.json` and `firewalls.json` for the list
/// calls. A DELETE whose URL contains a line of `fail-delete` answers 409 with an error body; a POST whose URL
/// contains a line of `fail-post` does the same. Every call is appended to `calls` as `METHOD URL`, which is how a
/// test proves the stub, not the network, answered. The body goes to the `-o` file when given and to stdout
/// otherwise; the status code is printed only when `-w` was passed, as curl does.
sealed interface HetznerCurlStub {
    String SCRIPT = """
                    #!/bin/bash
                    out=""; method=GET; url=""; want_code=no
                    while [ $# -gt 0 ]; do
                      case "$1" in
                        -o) out="$2"; shift 2 ;;
                        -X) method="$2"; shift 2 ;;
                        -w) want_code=yes; shift 2 ;;
                        -H|-d|--max-time) shift 2 ;;
                        -*) shift ;;
                        *) url="$1"; shift ;;
                      esac
                    done
                    echo "$method $url" >> "$STUB_STATE/calls"
                    code=200
                    refused() { [ -f "$STUB_STATE/$1" ] && while IFS= read -r needle; do case "$url" in *"$needle"*) return 0 ;; esac; done < "$STUB_STATE/$1"; return 1; }
                    if [ "$method" = DELETE ] && refused fail-delete; then code=409; body='{"error":{"code":"conflict","message":"resource is still in use"}}'
                    elif [ "$method" = POST ] && refused fail-post; then code=409; body='{"error":{"code":"conflict","message":"action refused"}}'
                    else
                      case "$method $url" in
                        GET*/servers?*) body=$(cat "$STUB_STATE/servers.json") ;;
                        GET*/ssh_keys?*) body=$(cat "$STUB_STATE/ssh_keys.json") ;;
                        GET*/firewalls?*) body=$(cat "$STUB_STATE/firewalls.json") ;;
                        POST*/actions/*) body='{"actions":[{"status":"success"}]}' ;;
                        *) body='' ;;
                      esac
                    fi
                    if [ -n "$out" ]; then printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
                    [ "$want_code" = yes ] && printf '%s' "$code"
                    exit 0
                    """;

    String SERVERS = """
                     {"servers":[{"id":42,"name":"aether-test-pg-1","public_net":{"ipv4":{"ip":"192.0.2.10"}}}]}
                     """;

    String SSH_KEYS = """
                      {"ssh_keys":[{"id":7,"name":"aether-test-pg-key"}]}
                      """;

    String FIREWALLS = """
                       {"firewalls":[{"id":99,"name":"aether-pg-firewall"}]}
                       """;

    record unused() implements HetznerCurlStub {}
}
