FROM ubuntu:22.04

# Set the working directory within container (where . defaults to)
WORKDIR /stack_knack

# RUN apk add gdb build-base nasm gcc
RUN apt-get update && \
    apt-get install -y gdb && \
    apt-get install -y gdbserver && \
    apt-get clean

# Build an image with gdbserver and your binary
COPY ./test.exe /usr/local/bin/user_code
CMD ["gdbserver", ":1234", "/usr/local/bin/user_code"]


# While running, must use --cap-add=SYS_PTRACE --security-opt seccomp=unconfined
# docker run -it --rm -p 1234:1234 my-debug-image

# docker run -it --rm \
#   -v test.exe:./user_code \
#   -p 1234:1234 \
#   my-debug-image \
#   gdbserver :1234 ./user_code


# in container: gdbserver :1234 /path/to/myprog arg1 arg2
# on host: gdb /path/to/myprog
# on host: (gdb) set osabi none
# on host: (gdb) set disassembly-flavor intel
# on host: (gdb) target remote localhost:1234
# Process is already running (no passing run command)



