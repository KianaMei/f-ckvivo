"""Generate a compact, offline lookup from the reviewed public pca-code snapshot.

Source: https://github.com/modood/Administrative-divisions-of-China (MIT).
This is a packaged snapshot, not a claim of current boundary completeness.
"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "analysis/host412/administrative-pca-code.json"
groups = []
for province in json.loads(source.read_text(encoding="utf-8")):
    items = [(province["code"].ljust(6, "0"), province["name"])]
    for city in province.get("children", []):
        city_name = province["name"] + (city["name"] if city["name"] not in ("市辖区", "县") else "")
        items.append((city["code"].ljust(6, "0"), city_name))
        for district in city.get("children", []):
            items.append((district["code"].ljust(6, "0"), city_name + district["name"]))
    groups.append(";".join(code + "=" + name for code, name in items))
java = '''package com.douai.module.feature;

/** Offline MIT-licensed administrative-name snapshot; never sends the work's location code. */
public final class UnifiedCityLookup {
    private UnifiedCityLookup() { }
    private static final java.util.Map<String, String> NAMES = names();
    private static java.util.Map<String, String> names() {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        String[] provinces = {
'''
java += ",\n".join("            " + json.dumps(group, ensure_ascii=False) for group in groups)
java += '''
        };
        for (String province : provinces) for (String pair : province.split(";")) {
            int equal = pair.indexOf('='); result.put(pair.substring(0, equal), pair.substring(equal + 1));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    public static String resolve(String code) {
        if (code == null || !code.matches("[0-9]{2,6}")) return "";
        String full = (code + "000000").substring(0, 6);
        String name = NAMES.get(full);
        if (name != null) return name;
        name = NAMES.get(full.substring(0, 4) + "00");
        if (name != null) return name + "（区县未识别）";
        name = NAMES.get(full.substring(0, 2) + "0000");
        return name == null ? "" : name + "（下级地区未识别）";
    }
}
'''
out = root / "repos/DouAi/app/src/main/java/com/douai/module/feature/UnifiedCityLookup.java"
out.write_text(java, encoding="utf-8")
print(json.dumps({"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "province_groups": len(groups), "output": str(out)}, ensure_ascii=False))
