current_dir = $(shell pwd)
image_name = gdb_image
container_name = gdb_container
# Default executable if none specified
exe ?= test

build:
	docker build --progress=plain -f Dockerfile -t ${image_name} .

run: build
# --cap-add=SYS_PTRACE --security-opt seccomp=unconfined are needed to run gdb in container
# -v volume mounts the user exe to the container at runtime
# Usage: make run exe=<executable_name> (interactive mode)
	docker run --name ${container_name} -it --cap-add=SYS_PTRACE --security-opt seccomp=unconfined --rm -v ${current_dir}/${exe}:/stack_knack/user_code -p 1234:1234 ${image_name}

run-bg: build
# Same as run but in background mode (terminal stays free)
# Usage: make run-bg exe=<executable_name>
	docker run --name ${container_name} -it --cap-add=SYS_PTRACE --security-opt seccomp=unconfined --rm -v ${current_dir}/${exe}:/stack_knack/user_code -p 1234:1234 ${image_name} &
