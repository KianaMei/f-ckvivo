# Evidence provenance — r59 / PD2545D

## `r59-vuln-check.json`

- Tool: `.upstream/feiguang-ai_plz110-6.12.69-cve-2026-64560-ksu/repro/fgroot.py vuln-check`
- Image: `.upstream/gki-r59/boot-6.12.img.kernel` — the **certified production**
  GKI `android16-6.12-2025-12_r59` bare ARM64 Image
  (`image_size=0x2820000`, SHA-256
  `fe3ef36476044dcbdb0f281143793199edbbd75acd1d0fa401f35d99005bda6c`).
- Result: **`status=VULNERABLE`**:
  - `timer_lock_sighand` is absent from the Image's kallsyms;
  - `posix_cpu_timer_del/set/rearm` call `pid_task → __lock_task_sighand`
    directly, with no retry edge over `pid_task`.

### Caveat on the JSON fields

The `kernelRelease` / `kernelBuild` fields in the JSON are the **context string
passed on the command line** (the device's `dd6fd5c98918`), not the source
commit of the Image. The authoritative Image identity is `imageSha256`
(`fe3ef364…`). Do not cite `kernelBuild` as an Image fact.

### Why the debug all-syms Image was discarded

The debug artifact `boot-6.12-allsyms.img` has `image_size=0x36a0000` and is a
**different build/layout** from the certified production Image
(`0x2820000`). Its 151,293 extracted symbols do not apply to the device: even
`posix_cpu_timer_del` differs (`0xffffffc0805cfbcc` in all-syms vs
`0xffffffc080519334` in certified). The PD2545D port uses only the certified
Image's 128,886-symbol table and its BTF.

### Remaining uncertainty

The device's private August respin (`dd6fd5c98918`) is
neither Image above. A device-side `timer_lock_sighand` /
`available_filter_functions` check or the exact current-build `boot.img` is
required before treating the device as vulnerable. The `--preflight` script
performs the read-only environment checks; it does not prove the vulnerability
by itself.
