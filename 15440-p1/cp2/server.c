
/*
    This is a simple server that uses remote procedure calls (RPCs) to 
    provide access to remote service with an interface identical to local services. 
    Upon receiving a request packet, it demarshalls the parameters of the function,
    execute the function with the given parameters, then marshalls the returned result 
    from the function call, and the reply packet is then sent back to the client.
 */

#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <err.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <dirent.h>
#include "../include/dirtree.h"
#include <errno.h>
#include <sys/wait.h>
#include <string.h>

#define MAXMSGLEN 200

int sockfd = 0;


/// @brief a helper struct to help keep track of the current serialized buffer and its size
struct info{
    char *tmp; 
    int len;
};
typedef struct info ret;

/// @brief serialize a single tree node into a ret struct (including info of sizeof name, name, and numsubdir)
/// @param t the node to be serialized
/// @return serialized buffer and its size stored in a ret struct
ret *serializaTreeNode(struct dirtreenode* t){
    int cnt = 0;
    int n = (int)strlen(t->name);
    char *buf = malloc(n+sizeof(int)*2);
    if (buf == NULL){
        err(1, 0);
    }
    memcpy(buf, &n, sizeof(int));
    cnt += sizeof(int);
    memcpy(buf+cnt, &(t->num_subdirs), sizeof(int));
    cnt += sizeof(int);
    memcpy(buf+cnt, t->name, n);
    cnt += n;
    ret *base = malloc(sizeof(ret)); 
    if (base == NULL){
        err(1,0);
    }
    base->tmp = buf;
    base->len = n+sizeof(int)*2;
    return base;
}

/// @brief recursively serializes the dirtree t with preorder traversal
/// @param t the tree to be serialized
/// @return serialized buffer and its size stored in a ret struct
ret *serializeTree(struct dirtreenode* t){
    if (t->num_subdirs == 0){
        ret * root = serializaTreeNode(t);
        return root;
    }
    int size = MAXMSGLEN;
    char *buf = malloc(size);
    if (buf == NULL){
        err(1,0);
    }
    ret *root = serializaTreeNode(t);
    int offset = root->len;
    memcpy(buf,root->tmp,root->len);
    free(root->tmp);
    free(root); 
    for (int i = 0; i < t->num_subdirs; i++){
        ret *tmp = serializeTree(t->subdirs[i]);
        if (offset + tmp->len > size){
            char *resize;
            if (offset + tmp->len > 2*size){
                resize = malloc(offset+tmp->len+size);
                if (resize == NULL){
                    err(1,0);
                }
            }else{
                resize = malloc(size*2);
                if (resize == NULL){
                    err(1, 0);
                }
            }
            memcpy(resize, buf, offset);
            memcpy(resize+offset, tmp->tmp,tmp->len);
            free(buf);
            buf = resize;
            offset += tmp->len;
        }else{
            memcpy(buf+offset, tmp->tmp, tmp->len);
            offset += tmp->len;
        }
        free(tmp->tmp);
        free(tmp);
    }
    ret *rval = malloc(sizeof(ret));
    if (rval == NULL){
        err(1,0);
    }
    rval->tmp = buf;
    rval->len = offset;
    return rval;
}

/// @brief receive and combine the message sent by the client into one buffer
/// @param sessfd session fd with the client
/// @param buf the destination buffer of the message
/// @param bufSize size of the message to be received
void receiveAll(int sessfd, char *buf, int bufSize){
    int rv;
    int maxSize = MAXMSGLEN;
    int currlen = 0;
    if (bufSize < maxSize){
        maxSize = bufSize;
    }
    while (currlen < bufSize){
        rv = recv(sessfd,buf+currlen,maxSize,0);
        currlen += rv;
        if (rv < 0){
            err(1,0);
        }
        if (bufSize - currlen < maxSize){
            maxSize = bufSize-currlen;
        }
    }
}

/// @brief deserializes the parameter of open function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveOpen (char *buf, int sessfd){
    int flag = *(int*)(buf);
    mode_t m = *(mode_t*)(buf+sizeof(int));
    size_t pathLen = *(size_t*)(buf+sizeof(int)+sizeof(mode_t));
    char *path = malloc(pathLen+1);
    if (path == NULL){
        err(1,0);
    }
    memcpy(path,buf+sizeof(int)+sizeof(mode_t)+sizeof(size_t),pathLen);
    path[pathLen] ='\0';
    int res = open(path,flag,m);
    free(path);
    char *retval = malloc(sizeof(int)*3);
    if (retval == NULL){
        err(1,0);
    }
    int len = sizeof(int)*2;
    memcpy(retval,&len,sizeof(int));
    memcpy(retval+sizeof(int),&res,sizeof(int));
    memcpy(retval+sizeof(int)*2,&errno,sizeof(int));
    send(sessfd, retval, 3*sizeof(int), 0);
    free(retval);
}

/// @brief deserializes the parameter of close function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveClose(char *buf, int sessfd){
    int fd = *(int*)buf;
    int res = close(fd);
    char *retval = malloc(sizeof(int)*3);
    if (retval == NULL){
        err(1,0);
    }
    int len = sizeof(int)*2;
    memcpy(retval, &len, sizeof(int));
    memcpy(retval+sizeof(int),&res,sizeof(int));
    memcpy(retval+sizeof(int)*2,&errno,sizeof(int));
    send(sessfd, retval, 3*sizeof(int), 0);
    free(retval);
}


/// @brief deserializes the parameter of write function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveWrite(char* buf, int sessfd){
    int fildes = *(int*)buf;
    size_t nbyte = *(size_t*)(buf+sizeof(int));
    char *buff = malloc(nbyte);
    memcpy(buff,buf+sizeof(int)+sizeof(size_t),nbyte);
    ssize_t res = write(fildes, buff, nbyte);
    free(buff);
    char *retval = malloc(sizeof(int)*2+sizeof(ssize_t));
    if (retval == NULL){
        err(1,0);
    }
    int len = sizeof(int) + sizeof(ssize_t);
    memcpy(retval,&len, sizeof(int));
    memcpy(retval+sizeof(int),&res,sizeof(ssize_t));
    memcpy(retval+sizeof(int)+sizeof(ssize_t),&errno,sizeof(int));
    send(sessfd, retval, sizeof(ssize_t)+sizeof(int)*2, 0);
    free(retval);
}

/// @brief deserializes the parameter of read function call, execute, 
///         then send the serialized result + read buffer back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveRead(char *buf, int sessfd){
    int fildes = *(int*)buf;
    size_t nbyte = *(size_t*)(buf + sizeof(int));
    char *buff = malloc(nbyte);
    if (buff == NULL){
        err(1,0);
    }
    ssize_t res = read(fildes, buff, nbyte);
    if (res != -1){
        char *retval = malloc(res+sizeof(ssize_t) +sizeof(int)*2);
        if (retval == NULL){
            err(1,0);
        }
        int len = res + sizeof(ssize_t) + sizeof(int);
        memcpy(retval, &len, sizeof(int));
        memcpy(retval+sizeof(int),&res,sizeof(ssize_t));
        memcpy(retval+sizeof(ssize_t)+sizeof(int), &errno, sizeof(int));
        memcpy(retval + sizeof(int)*2 +sizeof(size_t), buff, res);
        send(sessfd,retval,sizeof(ssize_t)+sizeof(int)*2+res,0);
        free(retval);
    }else{
        char *retval = malloc(sizeof(ssize_t) +2*sizeof(int));
        if (retval == NULL){
            err(1,0);
        }
        int len = sizeof(ssize_t) + sizeof(int);
        memcpy(retval, &len, sizeof(int));
        memcpy(retval+sizeof(int),&res,sizeof(ssize_t));
        memcpy(retval+sizeof(ssize_t)+sizeof(int), &errno, sizeof(int));
        send(sessfd,retval,sizeof(ssize_t)+2*sizeof(int),0);
        free(retval);
    }
    free(buff);
}

/// @brief deserializes the parameter of lseek function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveLseek(char *buf, int sessfd){
    int fildes = *(int*)buf;
    off_t offset = *(off_t*)(buf+sizeof(int));
    int pos = *(int*) (buf+sizeof(int) + sizeof(off_t));
    off_t res = lseek(fildes,offset,pos);
    char *retval = malloc(sizeof(off_t)+sizeof(int)*2);
    if (retval == NULL){
        err(1,0);
    }
    int len = sizeof(off_t) + sizeof(int);
    memcpy(retval,&len,sizeof(int));
    memcpy(retval+sizeof(int),&res,sizeof(off_t));
    memcpy(retval+sizeof(off_t)+sizeof(int),&errno,sizeof(int));
    send(sessfd,retval,sizeof(off_t)+sizeof(int)*2,0);
    free(retval);
}


/// @brief deserializes the parameter of stat function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveStat(char *buf, int sessfd){
    int pathLen = *(int*)(buf);
    char *path = malloc(pathLen+1);
    if (path == NULL){
        err(1,0);
    }
    struct stat s;
    memcpy(path,buf+sizeof(int),pathLen);
    path[pathLen] ='\0';
    int res = stat(path,&s);
    char *retval = malloc(sizeof(int)*3+sizeof(struct stat));
    if (retval == NULL){
        err(1,0);
    }
    int len = sizeof(int)*2 + sizeof(struct stat);
    memcpy(retval, &len, sizeof(int));
    memcpy(retval+sizeof(int),&res, sizeof(int));
    memcpy(retval + sizeof(int)*2, &errno, sizeof(int));
    memcpy(retval + sizeof(int)*3, &s, sizeof(struct stat));
    send(sessfd,retval,sizeof(int)*3+sizeof(struct stat),0);
    free(path);
    free(retval);
}


/// @brief deserializes the parameter of unlink function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveUnlink(char *buf, int sessfd){
    int pathLen = *(int*)buf;
    char *path = malloc(pathLen+1);
    if (path == NULL){
        err(1,0);
    }
    memcpy(path, buf+sizeof(int), pathLen);
    path[pathLen] = '\0';
    int res = unlink(path);
    char *retval = malloc(sizeof(int)*3);
    if (retval == NULL){
        err(1,0);
    }
    int len = sizeof(int)*2;
    memcpy(retval, &len, sizeof(int));
    memcpy(retval+sizeof(int), &res, sizeof(int));
    memcpy(retval+sizeof(int)*2, &errno, sizeof(int));
    send(sessfd,retval,sizeof(int)*3,0);
    free(path);
    free(retval);
}

/// @brief deserializes the parameter of getdirentries function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveGetdirentries(char *buf, int sessfd){
    int fd = *(int*)buf;
    size_t nbyte = *(size_t*)(buf+sizeof(int));
    off_t basep;
    memcpy(&basep,buf+sizeof(int)+sizeof(size_t),sizeof(off_t));
    char buff[nbyte];
    ssize_t res = getdirentries(fd, buff, nbyte, &basep);
    char *retval = malloc(res+sizeof(int)*2+sizeof(ssize_t));
    if (retval == NULL){
        err(1,0);
    }
    int len = res+sizeof(int)+sizeof(ssize_t);
    memcpy(retval, &len, sizeof(int));
    memcpy(retval+sizeof(int), &res, sizeof(ssize_t));
    memcpy(retval + sizeof(ssize_t) + sizeof(int), &errno, sizeof(int));
    memcpy(retval + sizeof(int)*2 + sizeof(ssize_t), buff, res);
    send(sessfd,retval,sizeof(ssize_t)+sizeof(int)*2+res,0);
    free(retval);
}


/// @brief deserializes the parameter of getdirtree function call, execute, 
///         then send the serialized result back to the client
/// @param buf the serialized buffer received from the client
/// @param sessfd current session fd
void serveGetdirtree(char *buf, int sessfd){
    int n = *(int*)buf;
    char path[n+1];
    memcpy(path, buf+sizeof(int), n);
    path[n] ='\0';
    struct dirtreenode* t = getdirtree(path);
    if (t == NULL){
        char retval[sizeof(int)*3];
        int bufLen = sizeof(int)*2;
        int error = 1;
        memcpy(retval, &bufLen, sizeof(int));
        memcpy(retval + sizeof(int), &error, sizeof(int));
        memcpy(retval + sizeof(int)*2 , &errno, sizeof(int));
        send(sessfd, retval, sizeof(int)*3, 0);
    }else{
        ret *s = serializeTree(t);
        ssize_t len = (ssize_t)s->len;
        char retval[sizeof(int)*3+sizeof(ssize_t)+len+1];
        int bufLen = sizeof(int)*2+ sizeof(ssize_t)+len+1;
        memcpy(retval, &bufLen, sizeof(int));
        int error = 0;
        memcpy(retval + sizeof(int), &error, sizeof(int));
        memcpy(retval+sizeof(int)*2, &len, sizeof(ssize_t));
        memcpy(retval + sizeof(ssize_t) + sizeof(int)*2, &errno, sizeof(int));
        memcpy(retval + sizeof(int)*3 + sizeof(ssize_t), s->tmp, len);
        retval[sizeof(int)*3+sizeof(ssize_t)+len] = '\0';
        send(sessfd,retval,s->len+sizeof(int)*3+sizeof(ssize_t)+1,0);
        freedirtree(t);
        free(s->tmp);
        free(s);   
    }
}


/// @brief serve the client 
/// @param sessfd 
/// @return if the current session with the client is finished (-1 indicates connection finished)
int serve(int sessfd){
    char op[sizeof(int)*2];
    int rv = recv(sessfd, op, sizeof(int)*2, 0);
    if (rv <= 0){
        if (rv < 0){ //an unexpected error occured
            err(1,0);
        }
        return -1;
    }
    int *fID = (int*)op;
    int bufSize = *(fID+1);
    char buf [bufSize];
    receiveAll(sessfd, buf, bufSize);
    if (*fID == 0){ //open
        serveOpen(buf, sessfd);
    }else if (*fID == 1){ //close
        serveClose(buf,sessfd);
    }else if (*fID == 2){
        serveWrite(buf,sessfd);
    }else if (*fID == 3){
        serveRead(buf,sessfd);
    }else if (*fID == 4){
        serveLseek(buf,sessfd);
    }else if (*fID == 5){
        serveStat(buf,sessfd);
    }else if (*fID == 6){
        serveUnlink(buf,sessfd);
    }else if (*fID == 7){
        serveGetdirentries(buf,sessfd);
    }else if (*fID == 8){
        serveGetdirtree(buf, sessfd);
    }else{
        fprintf(stderr,"undefined function \n");
        return -1;
    }
    return 0;
}


int main(int argc, char**argv) {
	char *serverport;
	unsigned short port;
	int sessfd, rv, i;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short)atoi(serverport);
	else port=15400;
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			        // in case of error
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			    // clear it first
	srv.sin_family = AF_INET;			        // IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			        // server port

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv<0) err(1,0);
	
	// main server loop, handle clients one at a time
	while(1) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
        if (sessfd<0) err(1,0);
        int r = fork();
        if (r < 0){
            err(1,0);
        }
        if (r == 0){
            close(sockfd);
            while (1){
                if (serve(sessfd) == -1){ //current client has closed connection
                    break;
                }
            }
            close(sessfd);
            exit(0);
        }
		close(sessfd);
	}
	// close socket
	close(sockfd);

	return 0;
}
