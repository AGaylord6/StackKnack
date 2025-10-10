#include <stdio.h>
#include <stdlib.h>

int main() {
    int c = 0;
    for (int i = 0; i < 10; i++) {
        c += i;
    }
    printf("Hello, World! Sum is: %d\n", c);
    return 0;
}