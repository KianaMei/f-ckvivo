package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedCityLookupTest {
    @Test public void knownCountyCodesResolveLocallyAndUnknownSubregionsRemainLabeled() {
        assertEquals("北京市东城区", UnifiedCityLookup.resolve("110101"));
        assertEquals("广东省深圳市南山区", UnifiedCityLookup.resolve("440305"));
        assertTrue(UnifiedCityLookup.resolve("440399").contains("区县未识别"));
        assertEquals("", UnifiedCityLookup.resolve("not-a-code"));
        assertEquals("", UnifiedCityLookup.resolve("999999"));
    }
}
