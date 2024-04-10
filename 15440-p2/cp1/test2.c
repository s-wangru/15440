#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <err.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <errno.h>
#include <string.h>

int main(int argc, char **argv) {
    int fd1= open("foo1", O_RDONLY);
    int fd2= open("foo1", O_RDONLY);
    sleep(5);
    int fd3= open("foo1", O_RDONLY);
    int fd4= open("foo1", O_RDONLY);
    return 0;
}
