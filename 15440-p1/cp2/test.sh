#!/usr/bin/bash

for i in $(seq 10); do
 LD_PRELOAD=./mylib.so ./test ${i} &
done
wait
