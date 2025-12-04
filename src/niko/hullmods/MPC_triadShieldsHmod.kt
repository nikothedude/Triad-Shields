package niko.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipAPI.HullSize
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.coreui.v
import niko.shipsystems.MPC_triadShields
import niko.subsystems.MPC_triadShieldSubsystem
import org.magiclib.subsystems.addSubsystem
import java.awt.Color

class MPC_triadShieldsHmod: BaseHullMod() {

    companion object {
        const val BEAM_MULT = 0.25f
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize?, stats: MutableShipStatsAPI?, id: String?) {
        super.applyEffectsBeforeShipCreation(hullSize, stats, id)

        if (stats == null || id == null) return
        //stats.beamDamageTakenMult.modifyMult(id, BEAM_MULT.toFloat())
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI?, id: String?) {
        super.applyEffectsAfterShipCreation(ship, id)

        if (ship == null || id == null) return

        ship.addSubsystem(MPC_triadShieldSubsystem(ship))
        return
    }

    override fun shouldAddDescriptionToTooltip(hullSize: HullSize?, ship: ShipAPI?, isForModSpec: Boolean): Boolean {
        return false
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: HullSize?,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {

        tooltip.addPara(
            "A highly advanced system of nodular shield emitters creates a thick %s around the ship's perimeter, acting much like regenerating armor. Adds the %s system as a subsystem.",
            5f,
            Misc.getHighlightColor(),
            "skinshield", "Triad Shields",
        )

        tooltip.addPara(
            "This shield is comprised of %s, each with its own integrity. Segments close by or far away from the ship are weaker - while the ones in the middle can withstand " +
            "modest punishment before breaking.",
            5f,
            Misc.getHighlightColor(),
            "individual segments"
        )

        tooltip.addPara(
            "The skinshield is ineffective against %s and %s, with %s being able to cripple entire regions of the shield generator, and %s being able to %s.",
            5f,
            Misc.getHighlightColor(),
            "explosives", "beams", "explosives", "beams", "partially penetrate"
        ).setHighlightColors(
            Misc.getNegativeHighlightColor(),
            Misc.getNegativeHighlightColor(),
            Misc.getHighlightColor(),
            Misc.getHighlightColor(),
            Misc.getNegativeHighlightColor()
        )

        tooltip.addSectionHeading("Stats", Alignment.MID, 5f)

        tooltip.addPara(
            "The %s of each segment is dependent on the efficiency of the parent shield, with a flux efficiency of %s being used in leu.",
            5f,
            Misc.getHighlightColor(),
            "shield efficiency", "one"
        )

        if (!isForModSpec && !Global.getSettings().isShowingCodex) {
            val shield = ship?.shield
            if (shield != null) {
                tooltip.setBulletedListMode(BaseIntelPlugin.BULLET)
                tooltip.addPara(
                    "This ship's shield efficiency is %s.",
                    0f,
                    Misc.getHighlightColor(),
                    "${shield.fluxPerPointOfDamage}"
                )
                tooltip.setBulletedListMode(null)
            }
        }

        tooltip.addPara(
            "The base capacity and regeneration rate of each segment depends on the tonnage of the parent ship.",
            5f
        )

        tooltip.beginTable(
            Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
            20f, true, true,
            *arrayOf<Any>("Hull Size", 100f, "Capacity", 90f, "Regen. Rate", 100f)
        )
        for (size in HullSize.entries) {
            val str = MPC_triadShields.sizesToStrength[size] ?: continue
            val regen = MPC_triadShields.sizesToDiss[size] ?: continue

            tooltip.addRow(
                Alignment.MID, Color.WHITE, Misc.getHullSizeStr(size),
                Alignment.MID, Misc.getHighlightColor(), str.toInt().toString(),
                Alignment.MID, Misc.getHighlightColor(), regen.toInt().toString()
            )
        }
        tooltip.addTable(
            "", 0, 5f
        )

        if (!isForModSpec && !Global.getSettings().isShowingCodex) {
            if (ship != null) {
                val strength = MPC_triadShields.sizesToStrength[hullSize] ?: 10f
                val diss = MPC_triadShields.sizesToDiss[hullSize] ?: 10f
                tooltip.setBulletedListMode(BaseIntelPlugin.BULLET)
                tooltip.addPara(
                    "This ship's segment strength and dissipation are %s and %s.",
                    5f,
                    Misc.getHighlightColor(),
                    "${strength.toInt()}", "${diss.toInt()}"
                )
                tooltip.setBulletedListMode(null)
            }
        }

    }
}