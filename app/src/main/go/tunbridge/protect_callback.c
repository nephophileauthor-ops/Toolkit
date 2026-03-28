#include "bridge.h"

bool callProtectSocket(protect_socket_cb cb, int fd) {
	if (cb == NULL) {
		return false;
	}
	return cb(fd);
}
