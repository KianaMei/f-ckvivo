#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Run the repository's trusted DEX xref scanner under Python -I.

Usage: python -I xhs/tools/run_xref.py base.apk string|method|field needle
"""
import importlib.util
import sys
from pathlib import Path


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    tools = Path(__file__).resolve().parents[2] / "fq-fold-pad" / "tools"
    sys.modules["dexscan"] = load("dexscan", tools / "dexscan.py")
    sys.modules["xref_string"] = load("xref_string", tools / "xref_string.py")
    xref = load("quietbox_xref", tools / "xref.py")
    xref.main()


if __name__ == "__main__":
    main()
