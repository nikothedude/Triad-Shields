package niko_TS.shipsystems.ai

import com.fs.starfarer.api.combat.*
import niko_TS.TS_triadShieldsAICore
import niko_TS.shipsystems.TS_triadShields
import org.lwjgl.util.vector.Vector2f

class TS_triadShieldsAI: ShipSystemAIScript {

    protected var ship: ShipAPI? = null
    protected var system: ShipSystemAPI? = null

    lateinit var ai: TS_triadShieldsAICore

    override fun init(ship: ShipAPI?, system: ShipSystemAPI?, flags: ShipwideAIFlags?, engine: CombatEngineAPI?) {
        this.ship = ship
        this.system = if (ship!!.phaseCloak.specAPI.id == "TS_triadShields") ship.phaseCloak else system
        ai = TS_triadShieldsAICore(ship, this.system!!.script as TS_triadShields)
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?
    ) {
        val command = ai.getCommand()
        if (command == TS_triadShieldsAICore.Command.DEACTIVATE) {
            tryDeactivate()
        } else if (command == TS_triadShieldsAICore.Command.ACTIVATE) {
            tryActivate()
        }
    }

    private fun tryDeactivate() {
        if (!system!!.isActive) return

        if (ship!!.phaseCloak.specAPI.id == system!!.specAPI.id) {
            ship!!.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
        } else {
            ship!!.useSystem()
        }
    }

    private fun tryActivate() {
        if (system!!.isActive) return
        if (ship!!.fluxTracker.isOverloaded) return
        if (ship!!.fluxTracker.isVenting) return

        if (ship!!.phaseCloak.specAPI.id == system!!.specAPI.id) {
            ship!!.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
        } else {
            ship!!.useSystem()
        }
    }
}