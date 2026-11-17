package de.schiessbude.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import de.schiessbude.gui.SettingsScreen;

/** Knopf "Konfigurieren" in Mod Menu (nur aktiv, wenn Mod Menu installiert ist). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
