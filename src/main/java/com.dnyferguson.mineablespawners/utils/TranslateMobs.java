package com.dnyferguson.mineablespawners.utils;

import com.dnyferguson.mineablespawners.MineableSpawners;
import org.bukkit.configuration.file.FileConfiguration;

public class TranslateMobs {

    public static String getTranslatedName(String englishName) {
        MineableSpawners plugin = MineableSpawners.getPlugin(MineableSpawners.class);
        FileConfiguration translationsConfig = plugin.getTranslationsConfig();

        return translationsConfig.getString(englishName, englishName);
    }
}

