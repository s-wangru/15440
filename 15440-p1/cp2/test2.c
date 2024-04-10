#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <errno.h>
#include <string.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/stat.h>
#include <dirent.h>

int main() {
    printf("--------start--------\n");

    // Mimicking: mylib: open called for path: localFile
    int fd1 = open("localFile", O_RDONLY);
    if (fd1 < 0) {
        printf("test_open_errno: %d\n", errno); // errno should be set by failed open
    }

    // Mimicking: mylib: open called for path: my_favorite_foo
    int fd2 = open("songs", O_RDONLY);
    if (fd2 < 0) {
        printf("test_open_errno: %d\n", errno); // errno should be set by failed open
    }

    // Mimicking: mylib: read called for fd: 3, count: 1024
    char buf[1024];
    ssize_t read_result = read(fd2, buf, sizeof(buf));
    if (read_result < 0) {
        printf("test_read_errno: %d\n", errno); // errno should be set by failed read
    }else{
        printf("yeeeee\n");
    }

    // Mimicking: mylib: close called for fd 3
    if (close(fd2) < 0) {
        printf("test_close_errno: %d\n", errno); // errno should be set by failed close
    }

    // Additional close call to simulate close_errno: 9
    if (close(1321) < 0) {
        printf("test_close_errn1321o: %d\n", errno); // errno should be set by failed close
    }

    // Additional close call to simulate close_errno: 9
    if ((off_t)lseek(1321, 6, SEEK_SET) < 0) {
        printf("test_close_errn1321o: %d\n", errno); // errno should be set by failed close
    }

    // Mimicking: mylib: stat called with path: localFile
    struct stat statbuf;
    if (stat("localFile", &statbuf) < 0) {
        printf("test_stat_errno: %d\n", errno); // errno should be set by failed stat
    }

    // Mimicking: mylib: unlink called with path: localFile
    if (unlink("localFile") < 0) {
        printf("test_unlink_errno: %d\n", errno); // errno should be set by failed unlink
    }



    char buf2[4096]; // Buffer for directory entries
    off_t base = 0; // Initial base offset

    ssize_t nbytes = getdirentries(-10, buf2, sizeof(buf2), &base);

    if (nbytes < 0) {
        printf("test_getdirentries_errno: %d\n", errno); // errno should be set by failed unlink
    }

    printf("---------end---------\n");

    return 0;
}