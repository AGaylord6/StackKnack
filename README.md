# StackKnack

Call stack visualization app written in clojure. 

Hosted here: http://3.148.228.98:3000/

Very much WIP/proof of concept

## AWS Info

Setting up Ubuntu on our AWS EC2 instance: https://linux.how2shout.com/how-to-install-docker-on-aws-ec2-ubuntu-22-04-or-20-04-linux/

Setting up clojure on cloud: https://clojure.org/guides/install_clojure

Also installed `gcc`, java jre (https://ubuntu.com/tutorials/install-jre#2-installing-openjdk-jre), `gdb` (which prob isn't required)

## Clojure utilization
Our project uses Clojure to create an interactive visualization tool that allows users to write, compile, and step through C programs directly within their browser. 
It takes advantage of Clojure’s strong interoperability with system processes to communicate with compilers and debuggers like gcc and lldb in real time, althought this did prove somewhat difficult to implement for the stepping feature.
By leveraging functional programming and lightweight concurrency, we have the server live stepping and variable tracking efficiently.
This usage of Clojure shows how a non-traditional language like Clojure can bridge systems-level programming with interactive web-based visualization.

## TODO

* Dockerize the user code (oops)
* Add reset button
* Find domain name?
* Don't refresh assembly for every step
* Add proper color blocks for each function's frame
    * Lable local vars + params correctly