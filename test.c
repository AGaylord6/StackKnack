#include <stdio.h>
#include <stdlib.h>

void recurse(int n) {
    printf("Recursing: %d\n", n);
}

int hi(int a) {
    int c = a * 2;
    for (int i = 0; i < 5; i++) {
        recurse(i);
    }
    return c;
}

int main() {
    hi(5);
    printf("Hello, World!\n");
    return 0;
}