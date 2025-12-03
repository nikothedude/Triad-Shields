package niko.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.combat.ai.BasicShipAI
import com.fs.starfarer.combat.ai.attack.AttackAIModule
import com.fs.starfarer.combat.ai.ooOO
import com.fs.starfarer.combat.entities.Ship
import niko.niko_MPC_reflectionUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.createDefaultShipAI

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

        ship.setShield(ShieldAPI.ShieldType.NONE, 0f, 1f, 0f)
        return

        ship.setShield(ShieldAPI.ShieldType.PHASE, 0f, 1f, 0f)

        val shipAI = ship.ai ?: ship.createDefaultShipAI(ShipAIConfig())
        if (shipAI is BasicShipAI) { // no compatability for custom ais, sorry
            try {
                val damperSpec = Global.getSettings().getShipSystemSpec("MPC_triadShields") as com.fs.starfarer.loading.specs.`do`
                niko_MPC_reflectionUtils.set(
                    "phaseCloak",
                    ship,
                    damperSpec.createSystem(ship as Ship?)
                )

                val threatEvalAI = niko_MPC_reflectionUtils.get("threatEvalAI", ship.ai, BasicShipAI::class.java)
                val attackAI = niko_MPC_reflectionUtils.get("attackAI", ship.ai, BasicShipAI::class.java)
                val flockingAI = niko_MPC_reflectionUtils.get("flockingAI", ship.ai, BasicShipAI::class.java)

                val newSystemAI = damperSpec.createSystemAI(
                    ship, shipAI.aiFlags,
                    threatEvalAI as? com.fs.starfarer.combat.ai.D,
                    attackAI as? AttackAIModule,
                    flockingAI as? com.fs.starfarer.combat.ai.movement.A,
                    shipAI as? (com.fs.starfarer.combat.ai.movement.maneuvers.M.o) //ShipAI obf class
                )
                // v mimics a anonymous wrapper the convinces the game to laod a systemai as a shieldai. see basicshipai for more, its in its constructor
                val testValTwo = object : com.fs.starfarer.combat.ai.F {
                    override fun o00000(
                        p0: Float,
                        p1: com.fs.starfarer.combat.ai.D?,
                        p2: Vector2f?,
                        p3: Vector2f?,
                        p4: Ship?
                    ) {
                        newSystemAI.o00000(p0, p2, p3, p4)
                    }

                    override fun Ó00000(): Boolean {
                        return if (newSystemAI is com.fs.starfarer.combat.ai.system.V) { // phase ai
                            val var1: com.fs.starfarer.combat.ai.system.V = newSystemAI
                            var1.ôo0000().new().Õ00000()
                        } else {
                            false
                        }
                    } // ABSOLUTELY FUCKING INSANE CODE
                    override fun o00000(): Boolean {
                        return false
                    }

                    override fun new(): ooOO? {
                        return if (newSystemAI is com.fs.starfarer.combat.ai.system.V) { // phase ai
                            val var1: com.fs.starfarer.combat.ai.system.V = newSystemAI
                            var1.ôo0000().new()
                        } else {
                            null
                        }
                    }
                }
                niko_MPC_reflectionUtils.set("shieldAI", shipAI, testValTwo, BasicShipAI::class.java)

            } catch (e: Exception) {
                //SA_debugUtils.log.error("$e")
                return
            }


        }
    }

}