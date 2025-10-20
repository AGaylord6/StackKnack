#include <stdio.h>
#include <stdlib.h>

int recurse(int n) {
    return n + 1;
}

int level_1(int a) {
    int c = a * 2;
    for (int i = 0; i < 3; i++) {
        c = c + recurse(i);
    }
    return c;
}

int main() {
    level_1(5);
    printf("Hello, World!\n");
    return 0;
}