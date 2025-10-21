#include <stdio.h>

int level_2(int n) {
    return n + 1;
}

int level_1(int a) {
    int c = a * 2;
    return c + level_2(a);
}

int main() {
    level_1(5);
    printf("Hello, World!\n");
    return 0;
}