#ifndef TUNBRIDGE_BRIDGE_H
#define TUNBRIDGE_BRIDGE_H

#include <stdbool.h>

typedef bool (*protect_socket_cb)(int);

void tunbridge_set_protect_callback(protect_socket_cb cb);
bool tunbridge_call_protect_socket(int fd);

#endif
