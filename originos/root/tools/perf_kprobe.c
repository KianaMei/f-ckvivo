/*
 * perf_kprobe.c - no-boot-image kernel address probe for the PD2545D port.
 *
 * Uses perf_event_paranoid=-1 and the kprobe PMU to attach to kernel symbols
 * by name and read the sample IP (the probed instruction's virtual address).
 * The device kernel resolves the symbol name from its own kallsyms; no
 * /proc/kallsyms access and no boot image are needed.
 *
 * Also supports a cycles:k fallback that samples kernel IPs without kprobes.
 *
 * Build (NDK r29):
 *   aarch64-linux-android35-clang -O2 -static -o perf_kprobe perf_kprobe.c
 *
 * Run (device): perf_kprobe [symbol ...]
 * Default symbols: __traceiter_sys_exit commit_creds posix_cpu_timer_del
 *                  avc_has_perm security_file_permission do_sys_openat2
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#ifndef SYS_perf_event_open
#define SYS_perf_event_open 241
#endif

#define PAGE_SIZE 4096
#define RING_PAGES 2

static int read_int(const char *path)
{
    char buf[64] = {0};
    int fd = open(path, O_RDONLY);
    int value = -1;

    if (fd < 0)
        return -1;
    if (read(fd, buf, sizeof(buf) - 1) > 0)
        value = atoi(buf);
    close(fd);
    return value;
}

static void trigger_symbol(const char *sym)
{
    int fd;
    char buf[64];
    timer_t timer;
    struct itimerspec ts = {0};

    for (int i = 0; i < 200; i++) {
        syscall(SYS_getpid);
        fd = open("/proc/self/status", O_RDONLY);
        if (fd >= 0) {
            read(fd, buf, sizeof(buf));
            close(fd);
        }
        if (!timer_create(CLOCK_MONOTONIC, NULL, &timer)) {
            ts.it_value.tv_nsec = 1000000;
            timer_settime(timer, 0, &ts, NULL);
            timer_delete(timer);
        }
        if (!strcmp(sym, "commit_creds") || !strcmp(sym, "prepare_creds"))
            setuid(getuid());
        if (!strcmp(sym, "prepare_kernel_cred")) {
            int s = socket(AF_INET, SOCK_STREAM, 0);
            if (s >= 0)
                close(s);
            s = socket(AF_INET, SOCK_RAW, 253);
            if (s >= 0)
                close(s);
        }
        if (!strcmp(sym, "pipe_write")) {
            int pfd[2];
            if (!pipe(pfd)) {
                write(pfd[1], "x", 1);
                close(pfd[0]);
                close(pfd[1]);
            }
        }
        if (!strcmp(sym, "misc_open") || !strcmp(sym, "uhid_char_write")) {
            int u = open("/dev/uhid", O_RDWR);
            if (u >= 0) {
                char ev[8] = {0};
                write(u, ev, sizeof(ev));
                close(u);
            }
        }
        if (!strcmp(sym, "virt_to_phys")) {
            void *m = mmap(NULL, PAGE_SIZE, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (m != MAP_FAILED)
                munmap(m, PAGE_SIZE);
        }
        if (!strcmp(sym, "avc_has_perm") || !strcmp(sym, "security_file_permission")) {
            fd = open("/system/build.prop", O_RDONLY);
            if (fd >= 0) {
                read(fd, buf, sizeof(buf));
                close(fd);
            }
        }
        usleep(1000);
    }
}

static int probe_symbol(const char *sym, int kprobe_type)
{
    struct perf_event_attr attr;
    uint64_t *ring;
    struct perf_event_mmap_page *meta;
    int fd;
    int pages = RING_PAGES + 1;
    size_t ring_len = (size_t)pages * PAGE_SIZE;
    uint64_t samples = 0;

    memset(&attr, 0, sizeof(attr));
    attr.type = kprobe_type;
    attr.size = sizeof(attr);
    attr.config = 0;
    attr.config1 = (uint64_t)(uintptr_t)sym;
    attr.config2 = 0;
    attr.sample_period = 1;
    attr.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_TIME;
    attr.disabled = 1;
    attr.exclude_kernel = 0;
    attr.wakeup_events = 1;

    fd = (int)syscall(SYS_perf_event_open, &attr, -1, 0, -1, 0);
    if (fd < 0) {
        printf("PROBE_FAIL sym=%s errno=%d (%s)\n", sym, errno, strerror(errno));
        return -1;
    }
    ring = mmap(NULL, ring_len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (ring == MAP_FAILED) {
        printf("PROBE_MMAP_FAIL sym=%s errno=%d\n", sym, errno);
        close(fd);
        return -1;
    }
    meta = (struct perf_event_mmap_page *)ring;

    if (ioctl(fd, PERF_EVENT_IOC_ENABLE, 0) < 0) {
        printf("PROBE_ENABLE_FAIL sym=%s errno=%d\n", sym, errno);
        munmap(ring, ring_len);
        close(fd);
        return -1;
    }
    trigger_symbol(sym);

    for (int tries = 0; tries < 50 && samples == 0; tries++) {
        struct pollfd pfd = {.fd = fd, .events = POLLIN};
        uint64_t head;

        poll(&pfd, 1, 20);
        head = meta->data_head;
        __sync_synchronize();
        while (meta->data_tail < head) {
            struct perf_event_header *hdr =
                (struct perf_event_header *)((char *)ring + PAGE_SIZE +
                                             (meta->data_tail % (RING_PAGES * PAGE_SIZE)));
            if (hdr->type == PERF_RECORD_SAMPLE) {
                uint64_t *data = (uint64_t *)(hdr + 1);
                uint64_t ip = data[0];
                uint64_t tid = data[1];
                uint64_t time_ns = data[2];

                printf("PROBE_IP sym=%s ip=0x%016llx tid=%llu time=%llu\n",
                       sym, (unsigned long long)ip, (unsigned long long)tid,
                       (unsigned long long)time_ns);
                samples++;
            }
            meta->data_tail += hdr->size;
        }
    }
    ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
    if (!samples)
        printf("PROBE_NO_SAMPLE sym=%s (kprobe attached but no hit)\n", sym);
    munmap(ring, ring_len);
    close(fd);
    return samples ? 0 : -1;
}

static int sample_cycles_kernel(void)
{
    struct perf_event_attr attr;
    uint64_t *ring;
    struct perf_event_mmap_page *meta;
    int fd;
    int pages = RING_PAGES + 1;
    size_t ring_len = (size_t)pages * PAGE_SIZE;
    int seen = 0;

    memset(&attr, 0, sizeof(attr));
    attr.type = PERF_TYPE_HARDWARE;
    attr.size = sizeof(attr);
    attr.config = PERF_COUNT_HW_CPU_CYCLES;
    attr.sample_period = 1000;
    attr.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID;
    attr.disabled = 1;
    attr.exclude_kernel = 0;
    attr.exclude_user = 1;
    attr.wakeup_events = 1;

    fd = (int)syscall(SYS_perf_event_open, &attr, -1, 0, -1, 0);
    if (fd < 0) {
        printf("CYCLES_FAIL errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }
    ring = mmap(NULL, ring_len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (ring == MAP_FAILED) {
        printf("CYCLES_MMAP_FAIL errno=%d\n", errno);
        close(fd);
        return -1;
    }
    meta = (struct perf_event_mmap_page *)ring;
    ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
    for (int i = 0; i < 4000; i++) {
        syscall(SYS_getpid);
        if ((i & 63) == 0) {
            int f = open("/proc/self/status", O_RDONLY);
            if (f >= 0) {
                char b[128];
                read(f, b, sizeof(b));
                close(f);
            }
        }
    }
    ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
    {
        uint64_t head = meta->data_head;
        __sync_synchronize();
        while (meta->data_tail < head) {
            struct perf_event_header *hdr =
                (struct perf_event_header *)((char *)ring + PAGE_SIZE +
                                             (meta->data_tail % (RING_PAGES * PAGE_SIZE)));
            if (hdr->type == PERF_RECORD_SAMPLE) {
                uint64_t *data = (uint64_t *)(hdr + 1);
                printf("CYCLES_IP ip=0x%016llx tid=%llu\n",
                       (unsigned long long)data[0], (unsigned long long)data[1]);
                seen++;
            }
            meta->data_tail += hdr->size;
        }
    }
    munmap(ring, ring_len);
    close(fd);
    printf("CYCLES_SAMPLES seen=%d\n", seen);
    return seen ? 0 : -1;
}

int main(int argc, char **argv)
{
    static const char *defaults[] = {
        "__traceiter_sys_exit",
        "prepare_kernel_cred",
        "avc_has_perm",
        "pipe_write",
        "misc_open",
        "uhid_char_write",
        "virt_to_phys",
        "commit_creds",
        "posix_cpu_timer_del",
        "security_file_permission",
        "do_sys_openat2",
    };
    const char **symbols = (const char **)defaults;
    int count = (int)(sizeof(defaults) / sizeof(defaults[0]));
    int kprobe_type;

    if (argc > 1) {
        symbols = (const char **)&argv[1];
        count = argc - 1;
    }
    kprobe_type = read_int("/sys/bus/event_source/devices/kprobe/type");
    printf("KPROBE_PMU type=%d paranoid=%d\n", kprobe_type,
           read_int("/proc/sys/kernel/perf_event_paranoid"));
    if (kprobe_type > 0) {
        for (int i = 0; i < count; i++)
            probe_symbol(symbols[i], kprobe_type);
    }
    sample_cycles_kernel();
    return 0;
}
