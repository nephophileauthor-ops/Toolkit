#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool tunbridge_start(
    int tun_fd,
    int mtu,
    const char* socks5_addr,
    const char* http_proxy_addr,
    bool udp_enabled,
    const char* dns_resolver
);

void tunbridge_stop();

void tunbridge_set_protect_callback(bool (*callback)(int));

bool tunbridge_has_real_backend();

#ifdef __cplusplus
}
#endif
