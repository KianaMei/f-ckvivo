#define _GNU_SOURCE
#include <sys/mman.h>
#include <string.h>
#include <unistd.h>
int main(void) {
    const size_t sz = 32UL * 1024 * 1024;
    for (int r = 0; r < 4; r++) {
        char *p = mmap(0, sz, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) return 1;
        for (size_t i = 0; i < sz; i += 4096) p[i] = (char)i;
        munmap(p, sz);
    }
    return 0;
}
