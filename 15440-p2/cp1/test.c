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
    int fd = open("foo1", O_RDWR|O_CREAT);
    printf("error: %d\n",errno);
    char buf[10];
    read(fd,buf,10);
    printf("%s\n",buf);
    close(fd);
    return 0;
}
