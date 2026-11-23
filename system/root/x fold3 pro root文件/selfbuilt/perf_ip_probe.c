// Minimal unprivileged perf_event_open probe for kernel IP sampling.
// Build: aarch64-linux-android21-clang -O2 -static -o perf_ip_probe perf_ip_probe.c
#define _GNU_SOURCE
#include <errno.h>
#include <linux/perf_event.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>

#ifndef __NR_perf_event_open
#define __NR_perf_event_open 241
#endif

static long perf_open(struct perf_event_attr *attr, pid_t pid, int cpu, int group_fd, unsigned long flags) {
    return syscall(__NR_perf_event_open, attr, pid, cpu, group_fd, flags);
}

int main(void) {
    struct perf_event_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.type = PERF_TYPE_SOFTWARE;
    attr.size = sizeof(attr);
    attr.config = PERF_COUNT_SW_CPU_CLOCK;
    attr.sample_period = 1000;
    attr.sample_type = PERF_SAMPLE_IP;
    attr.disabled = 1;
    attr.exclude_user = 1;
    attr.exclude_kernel = 0;
    attr.exclude_hv = 1;
    attr.wakeup_events = 1;

    int fd = (int)perf_open(&attr, 0, -1, -1, 0);
    if (fd < 0) {
        fprintf(stderr, "perf_event_open failed: errno=%d (%s)\n", errno, strerror(errno));
        return 2;
    }
    printf("perf_event_open ok fd=%d\n", fd);

    long pg = sysconf(_SC_PAGESIZE);
    size_t data_size = (size_t)pg * 8;
    struct perf_event_mmap_page *meta = mmap(NULL, (size_t)pg + data_size,
                                             PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (meta == MAP_FAILED) {
        fprintf(stderr, "mmap failed: errno=%d (%s)\n", errno, strerror(errno));
        close(fd);
        return 3;
    }

    ioctl(fd, PERF_EVENT_IOC_RESET, 0);
    ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
    for (int i = 0; i < 200000; i++) {
        syscall(SYS_getpid);
        syscall(SYS_getuid);
        int f = syscall(SYS_openat, AT_FDCWD, "/dev/null", O_RDONLY, 0);
        if (f >= 0) syscall(SYS_close, f);
    }
    ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
    __sync_synchronize();

    uint64_t head = meta->data_head;
    uint64_t tail = meta->data_tail;
    int samples = 0;
    while (tail < head && samples < 200) {
        struct perf_event_header *h = (struct perf_event_header *)((char *)meta + pg + (tail % data_size));
        if (h->size < sizeof(*h) || h->size > data_size) {
            fprintf(stderr, "bad record size=%u at tail=%llu\n", h->size, (unsigned long long)tail);
            break;
        }
        if (h->type == PERF_RECORD_SAMPLE) {
            uint64_t ip = 0;
            memcpy(&ip, h + 1, sizeof(ip));
            printf("IP=%#llx\n", (unsigned long long)ip);
            samples++;
        }
        tail += h->size;
    }
    meta->data_tail = tail;
    printf("samples=%d head=%llu tail=%llu\n", samples, (unsigned long long)head, (unsigned long long)tail);
    munmap(meta, (size_t)pg + data_size);
    close(fd);
    return samples > 0 ? 0 : 4;
}
