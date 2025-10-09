current_dir = $(shell pwd)
image_name = gdb_image
container_name = gdb_container

build:
	docker build --progress=plain -f Dockerfile -t ${image_name} .

run: build
	docker run --name ${container_name} -it --cap-add=SYS_PTRACE --security-opt seccomp=unconfined --rm -p 1234:1234 ${image_name}
