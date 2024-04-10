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
    int fd = open("foo",O_RDONLY);
    close(fd);
    int fd2 = open("foo2",O_RDONLY);
    close(fd2);
    int fd3 = open("foo3",O_RDONLY);
    close(fd3);
    fd2 = open("foo2",O_RDONLY);
    close(fd2);
    int fd4 = open("foo4",O_RDONLY);
    close(fd4);
    int fd5 = open("foo5",O_RDONLY);
    close(fd5);
    fd2 = open("foo2",O_RDONLY);
    close(fd2);
    int fd6 = open("foo6",O_RDONLY);
    int fd7 = open("foo7",O_RDONLY);
    char buf[10];
    close(fd6);
    close(fd7);
    return 0;
}
