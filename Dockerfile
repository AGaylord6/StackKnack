FROM ubuntu:22.04

# Set the working directory within container (where . defaults to)
WORKDIR /stack_knack

# RUN apk add gdb build-base nasm gcc
RUN apt-get update && \
    apt-get install -y gdb && \
    apt-get install -y gdbserver && \
    apt-get clean

# Build an image that will run gdbserver on binary that is volume mounted to container at runtime
CMD ["gdbserver", ":1234", "./user_code"]

# in wsl (to create elf instead of PE): gcc -no-pie -g -O0 test.c -o test

# in container: gdbserver :1234 ./user_code
# on host: gdb test
# on host: (gdb) set osabi none
# on host: (gdb) set disassembly-flavor intel
# on host: (gdb) target remote :1234
# Process is already running (no passing run command)



