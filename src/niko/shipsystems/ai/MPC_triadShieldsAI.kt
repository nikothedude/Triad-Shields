package niko.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

class MPC_triadShieldsAI: ShipSystemAIScript {

    companion object {
        const val EVAL_RANGE = 5000f
        const val ACTIVATE_THRESH = 500f
    }

    protected var system: ShipSystemAPI? = null
    protected var ship: ShipAPI? = null
    val engine = Global.getCombatEngine()
    val interval: IntervalUtil = IntervalUtil(0.1f, 0.2f)

    override fun init(ship: ShipAPI?, system: ShipSystemAPI?, flags: ShipwideAIFlags?, engine: CombatEngineAPI?) {
        this.ship = ship
        this.system = system
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?
    ) {
        if (ship == null) return
        val ship = ship!!
        if (ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)) {
            tryActivate()
            return
        } else if (ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_USE_SHIELDS)) {
            tryDeactivate()
            return
        }

        var threat = 0f
        val projectiles = CombatUtils.getProjectilesWithinRange(
            ship.location,
            EVAL_RANGE
        )
        for (proj in projectiles) {
            if (proj.owner == ship.owner || proj.isFading || proj.didDamage()) continue
            var damageMultiplier = 1f
            when (proj.damageType) {
                DamageType.HIGH_EXPLOSIVE -> damageMultiplier = 1.5f
                DamageType.KINETIC -> damageMultiplier = 0.5f
                DamageType.FRAGMENTATION -> damageMultiplier = 0.25f
                DamageType.ENERGY -> damageMultiplier *= 1.0f
                DamageType.OTHER -> 1.0f
            }
            threat += (proj.damageAmount + proj.empAmount) * damageMultiplier
        }

        val enemies = AIUtils.getNearbyEnemies(ship, EVAL_RANGE)
        for (enemy in enemies) {
            val dist: Float = 1f - (MathUtils.getDistance(ship, enemy) / EVAL_RANGE)
            val dp = enemy.hullSpec.fleetPoints
            var hullSizeThreat = 0f
            when (enemy.hullSize) {
                ShipAPI.HullSize.FIGHTER -> hullSizeThreat = 3f
                ShipAPI.HullSize.FRIGATE -> hullSizeThreat = 5f
                ShipAPI.HullSize.DESTROYER -> hullSizeThreat = 10f
                ShipAPI.HullSize.CRUISER -> hullSizeThreat = 20f
                ShipAPI.HullSize.CAPITAL_SHIP -> hullSizeThreat = 40f
                ShipAPI.HullSize.DEFAULT -> 10f
            }
            threat += (dp + hullSizeThreat) * dist * 6f
        }

        val flags = ship.aiFlags
        if (flags != null) {
            if (flags.hasFlag(ShipwideAIFlags.AIFlags.NEEDS_HELP)) threat += 200f
            if (flags.hasFlag(ShipwideAIFlags.AIFlags.PURSUING) || flags.hasFlag(ShipwideAIFlags.AIFlags.MANEUVER_TARGET)) {
                threat *= 0.6f
            }
        }

        if (ship.fluxLevel >= 0.8f) threat *= 0.5f // we want to drop shields...
        if (ship.fluxLevel >= 0.98f) {
            threat *= 0.15f

            if (ship.hullLevel > 0.05f) {
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_USE_SHIELDS, 20f)
            }
        }
        if (ship.hullLevel <= 0.2f) threat *= 2f // uh oh

        if (threat >= ACTIVATE_THRESH) {
            tryActivate()
        } else {
            tryDeactivate()
        }
    }

    private fun tryDeactivate() {
        if (!ship!!.phaseCloak.isActive) return

        ship!!.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }

    private fun tryActivate() {
        if (ship!!.phaseCloak.isActive) return
        if (ship!!.fluxTracker.isOverloaded) return
        if (ship!!.fluxTracker.isVenting) return

        ship!!.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }
}