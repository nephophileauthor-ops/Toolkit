#include "bridge.h"

static protect_socket_cb g_protect_callback = NULL;

void tunbridge_set_protect_callback(protect_socket_cb cb) {
	g_protect_callback = cb;
}

bool tunbridge_call_protect_socket(int fd) {
	if (g_protect_callback == NULL) {
		return false;
	}
	return g_protect_callback(fd);
}
