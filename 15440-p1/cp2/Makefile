PROGS=server test
CFLAGS+=-Wall

all: $(PROGS) mylib.so

mylib.o: mylib.c
	gcc -Wall -fPIC -DPIC -c mylib.c

mylib.so: mylib.o
	ld -shared -o mylib.so mylib.o -ldl

server.o: server.c
	gcc -I../include -c -g server.c -o server.o

server: server.o
	gcc -o server server.o -L../lib -ldirtree

clean:
	rm -f *.o *.so

