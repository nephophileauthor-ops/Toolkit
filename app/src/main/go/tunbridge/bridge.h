#ifndef TUNBRIDGE_BRIDGE_H
#define TUNBRIDGE_BRIDGE_H

#include <stdbool.h>

typedef bool (*protect_socket_cb)(int);

bool callProtectSocket(protect_socket_cb cb, int fd);

#endif
