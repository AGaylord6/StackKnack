#include <stdio.h>

void level2(int n) {
    printf("Level 2: %d\n", n);
}

void level1(int n) {
    level2(n + 10);
}

int main() {
    level1(5);
    return 0;
}