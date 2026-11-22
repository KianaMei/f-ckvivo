/*
 * memdump — 从运行中的进程内存里捞 dex。
 *
 * 目的：酷安 16.7.3 是易盾（com.netease.nis.wrapper）抽取壳，APK 里的 classes.dex
 * 只有 30 个壳类，真实 dex 由 native 在运行时解密后直接喂给 ART。要在静态侧拿到代码，
 * 只能把内存里的 dex 抠出来。
 *
 * 做法：解析 /proc/<pid>/maps，对所有 rw 匿名/共享映射逐段 pread 读出来，
 * 在缓冲里搜 dex 魔数（dex\n035/037/038/039）与 compact dex（cdex001），
 * 校验 header_size/file_size 合理性后落盘。
 *
 * 用法：memdump <pid> <outdir> [--scan-only]
 * 依赖：root（能 open /proc/<pid>/mem）
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdint.h>
#include <sys/types.h>

#define MIN_REGION (256 * 1024)
#define MAX_KEEP (1024u * 1024u * 1024u)

struct region {
    unsigned long start;
    unsigned long end;
    char perms[8];
    char name[256];
};

static int write_file(const char *outdir, const char *prefix, int idx,
                      const unsigned char *buf, size_t len)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/%s-%03d.dex", outdir, prefix, idx);
    FILE *f = fopen(path, "wb");
    if (!f) {
        fprintf(stderr, "open %s: %s\n", path, strerror(errno));
        return -1;
    }
    size_t n = fwrite(buf, 1, len, f);
    fclose(f);
    if (n != len) {
        fprintf(stderr, "short write %s\n", path);
        return -1;
    }
    printf("    wrote %s (%zu bytes)\n", path, len);
    return 0;
}

static int dex_header_ok(const unsigned char *p, size_t avail, size_t *out_size)
{
    /* magic: dex\n035\0 / 037 / 038 / 039 */
    if (avail < 112) return 0;
    if (memcmp(p, "dex\n", 4) != 0) return 0;
    if (p[7] != 0) return 0;
    uint32_t file_size, header_size, endian;
    memcpy(&file_size, p + 32, 4);
    memcpy(&header_size, p + 36, 4);
    memcpy(&endian, p + 40, 4);
    if (header_size != 0x70) return 0;
    if (endian != 0x12345678) return 0;
    if (file_size < 0x70 || file_size > MAX_KEEP) return 0;
    if (file_size > avail) {
        /* 可能是被截断的拷贝，也先记下来 */
        *out_size = avail;
        return 2;
    }
    *out_size = file_size;
    return 1;
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: memdump <pid> <outdir> [--scan-only]\n");
        return 2;
    }
    int pid = atoi(argv[1]);
    const char *outdir = argv[2];
    int scan_only = (argc > 3 && strcmp(argv[3], "--scan-only") == 0);

    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *maps = fopen(maps_path, "r");
    if (!maps) {
        fprintf(stderr, "open %s: %s\n", maps_path, strerror(errno));
        return 1;
    }

    char mem_path[64];
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);
    int mem = open(mem_path, O_RDONLY);
    if (mem < 0) {
        fprintf(stderr, "open %s: %s (需要 root / CAP_SYS_PTRACE)\n", mem_path, strerror(errno));
        return 1;
    }

    unsigned char *buf = malloc(MAX_KEEP);
    if (!buf) {
        fprintf(stderr, "oom\n");
        return 1;
    }

    char line[1024];
    int regions = 0, dumped = 0, found = 0;
    while (fgets(line, sizeof(line), maps)) {
        struct region r;
        memset(&r, 0, sizeof(r));
        r.name[0] = 0;
        int n = sscanf(line, "%lx-%lx %7s %*s %*s %*s %255[^\n]",
                       &r.start, &r.end, r.perms, r.name);
        if (n < 3) continue;
        if (r.perms[0] != 'r') continue;           /* 所有可读映射都扫（dalvik-DEX 是 r--p） */
        unsigned long size = r.end - r.start;
        if (size < MIN_REGION) continue;
        if (size > MAX_KEEP) size = MAX_KEEP;
        if (size > MAX_KEEP) size = MAX_KEEP;
        regions++;

        ssize_t got = pread(mem, buf, size, (off_t) r.start);
        if (got <= 0) continue;

        /* 在缓冲里逐字节找 dex 魔数 */
        for (ssize_t i = 0; i + 112 < got; ) {
            unsigned char *hit = memmem(buf + i, got - i, "dex\n", 4);
            if (!hit) break;
            size_t off = hit - buf;
            size_t dsize = 0;
            int ok = dex_header_ok(buf + off, got - off, &dsize);
            if (ok) {
                found++;
                printf("[+] dex @ %#lx (region %#lx-%#lx %s %s) version=%c%c%c size=%zu%s\n",
                       r.start + off, r.start, r.end, r.perms,
                       r.name[0] ? r.name : "-",
                       hit[4], hit[5], hit[6], dsize,
                       ok == 2 ? " (truncated)" : "");
                if (!scan_only) {
                    char prefix[128];
                    snprintf(prefix, sizeof(prefix), "pid%d-%08lx", pid, r.start + off);
                    if (write_file(outdir, prefix, dumped, buf + off, dsize) == 0) dumped++;
                }
            }
            i = off + 4;
        }

        /* compact dex：ART 在内存里可能已经把 dex 压成 cdex001 */
        for (ssize_t i = 0; i + 8 < got; ) {
            unsigned char *hit = memmem(buf + i, got - i, "cdex", 4);
            if (!hit) break;
            size_t off = hit - buf;
            printf("[*] cdex @ %#lx (region %#lx %s)\n",
                   r.start + off, r.start, r.name[0] ? r.name : "-");
            i = off + 4;
        }
    }

    printf("regions scanned=%d dex found=%d dumped=%d\n", regions, found, dumped);
    fclose(maps);
    close(mem);
    free(buf);
    return 0;
}
