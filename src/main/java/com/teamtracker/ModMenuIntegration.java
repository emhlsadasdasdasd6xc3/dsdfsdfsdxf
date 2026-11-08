package com.teamtracker;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Adds the Team Tracker settings button in Mod Menu. Only Mod Menu ever loads this class
 * (it is a "modmenu" entrypoint), so the mod still runs fine when Mod Menu is not installed.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return TeamTrackerScreen::new;
	}
}
