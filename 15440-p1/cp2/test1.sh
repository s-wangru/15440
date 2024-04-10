#!/usr/bin/bash

for i in $(seq 10); do
 chmod u=rwx,g=r,o=r file${i}
done
wait
