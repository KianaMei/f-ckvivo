package com.douai.module.config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static com.douai.module.config.ConfigurationCatalog.Feature.*;
import static org.junit.Assert.*;

/** A direct-save toggle must refresh the existing native click and gesture option snapshots. */
public final class ConfigurationDownloadPublicationTest {
    @Test public void changingOnlyDirectSaveRefreshesBothDownloadEntryPoints() {
        assertConsumers(ConfigurationChanges.between(
                Map.of(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false),
                Map.of(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true)));
    }

    @Test public void clearingDirectSaveRefreshesTheSameEntryPointsAndKeepsItsFalseDefault() {
        String key = LocalOptions.OFFICIAL_DOWNLOAD_DIRECT;
        assertEquals("boolean", ConfigKeys.TYPES.get(key));
        assertEquals(false, ConfigurationCatalog.settings().get(key).defaultValue);
        assertConsumers(ConfigurationChanges.between(Map.of(key, true), Collections.emptyMap()));
    }

    private static void assertConsumers(ConfigurationChanges changes) {
        Set<ConfigurationCatalog.Feature> actual = EnumSet.noneOf(ConfigurationCatalog.Feature.class);
        for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values())
            if (changes.affects(feature)) actual.add(feature);
        assertEquals("No unrelated feature should restart when a download routing option changes",
                EnumSet.of(DOWNLOAD, GESTURE), actual);
        assertEquals(EnumSet.of(DOWNLOAD, GESTURE),
                ConfigurationCatalog.settings().get(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT).consumers);
    }
}
