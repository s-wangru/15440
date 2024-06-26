#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>
 
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>

#include <stdlib.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <err.h>


int (*orig_open)(const char *pathname, int flags, ...);  // mode_t mode is needed when flags includes O_CREAT

int (*orig_close)(int fd);

ssize_t (*orig_read)(int fildes, void *buf, size_t nbyte);

ssize_t (*orig_write)(int fildes, const void *buf, size_t nbyte);

off_t (*orig_lseek)(int fd, off_t offset, int whence);

int (*orig_stat)(const char *restrict path, struct stat *restrict buf);

int (*orig_unlink)(const char *path);

ssize_t (*orig_getdirentries)(int fd, char *buf, size_t nbytes , off_t *basep);

struct dirtreenode* (*orig_getdirtree)( const char *path );

void (*orig_freedirtree)( struct dirtreenode* dt );

// The following line declares a function pointer with the same prototype as the open function.  
int sendToServer(char* arg){
	char *serverip;
	char *serverport;
	unsigned short port;
	char *msg = arg;
	int sockfd, rv;
	struct sockaddr_in srv;
	serverip = getenv("server15440");

	if (serverip) fprintf(stderr, "Got environment variable server15440: %s\n", serverip);
	else {
		fprintf(stderr, "Environment variable server15440 not found.  Using 127.0.0.1\n");
		serverip = "127.0.0.1";
	}

	serverport = getenv("serverport15440");
	if (serverport) fprintf(stderr, "Got environment variable serverport15440: %s\n", serverport);
	else {
		fprintf(stderr, "Environment variable serverport15440 not found.  Using 15440\n");
		serverport = "15440";
	}
	port = (unsigned short)atoi(serverport);

	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			// in case of error
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port


	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);

	send(sockfd, msg, strlen(msg), 0);	

	orig_close(sockfd);
	return 0;
}


// This is our replacement for the open function from libc.
int open(const char *pathname, int flags, ...) {
	mode_t m=0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}
	// we just print a message, then call through to the original open function (from libc)
	fprintf(stderr, "mylib: open called for path %s\n", pathname);
	sendToServer("open");
	return orig_open(pathname, flags, m);
}



int close(int fd){
	sendToServer("close");
	return orig_close(fd);
}

ssize_t read(int fildes, void *buf, size_t nbyte){
	sendToServer("read");
	return orig_read(fildes, buf, nbyte);
}

ssize_t write(int fildes, const void *buf, size_t nbyte){
	sendToServer("write");
	return orig_write(fildes, buf, nbyte);
}

off_t lseek(int fd, off_t offset, int whence){
	sendToServer("lseek");
	return orig_lseek(fd, offset, whence);
}

int stat(const char *restrict path, struct stat *restrict buf){
	sendToServer("stat");
	return orig_stat(path, buf);
}

int unlink(const char *path){
	sendToServer("unlink");
	return orig_unlink(path);
}

ssize_t getdirentries(int fd, char *buf, size_t nbytes , off_t *basep){
	sendToServer("getdirentries");
	return orig_getdirentries(fd,buf, nbytes, basep);
}

struct dirtreenode* getdirtree( const char *path ){
	sendToServer("getdirtree");
	return orig_getdirtree(path);
}

void freedirtree( struct dirtreenode* dt ){
	sendToServer("freedirtree");
	return orig_freedirtree(dt);
}

// This function is automatically called when program is started
void _init(void) {
	// set function pointer orig_open to point to the original open function
	orig_open = dlsym(RTLD_NEXT, "open");
	orig_close = dlsym(RTLD_NEXT, "close");
	orig_read = dlsym(RTLD_NEXT, "read");
	orig_write = dlsym(RTLD_NEXT, "write");
	orig_lseek = dlsym(RTLD_NEXT, "lseek");
	orig_stat = dlsym(RTLD_NEXT, "stat");
	orig_unlink = dlsym(RTLD_NEXT, "unlink");
	orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");
	orig_getdirtree = dlsym(RTLD_NEXT, "getdirtree");
	orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");
	fprintf(stderr, "Init mylib\n");
}


