package niko

import com.fs.starfarer.api.campaign.BaseCampaignEventListener
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin
import com.fs.starfarer.api.impl.campaign.ids.Factions

class TS_lootListener: BaseCampaignEventListener(false) {
    override fun reportEncounterLootGenerated(plugin: FleetEncounterContextPlugin?, loot: CargoAPI?) {
        super.reportEncounterLootGenerated(plugin, loot)

        if (plugin == null || loot == null) return

        if (plugin.loserData.fleet.faction.id == Factions.OMEGA && plugin.loserData.ownCasualties.any { it.member.hullSpec.builtInMods.contains("MPC_triadShieldsHullmod") }) {
            loot.addHullmods("MPC_triadShieldsHullmod", 1)
        }
    }
}