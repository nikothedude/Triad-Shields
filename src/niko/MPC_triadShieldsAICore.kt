package niko

import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lazywizard.lazylib.combat.CombatUtils

class MPC_triadShieldsAICore(val ship: ShipAPI) {

    companion object {
        const val EVAL_RANGE = 5000f
        const val ACTIVATE_THRESH = 100f
    }

    enum class Command {
        ACTIVATE,
        DEACTIVATE,
        STANDBY
    }

    fun getCommand(): Command {
        if (ship.fluxTracker.isOverloaded || ship.fluxTracker.isVenting) return Command.DEACTIVATE
        if (ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)) {
            return Command.ACTIVATE
        } else if (ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_USE_SHIELDS)) {
            return Command.DEACTIVATE
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
            val blockedThreatLevel = 0.1f

            if (ship.shield != null && ship.shield.isOn) {
                if (ship.shield.activeArc >= 360f) {
                    damageMultiplier *= blockedThreatLevel
                } else {
                    val arc = ship.shield.activeArc
                    val facing = ship.shield.facing
                    val left = (facing - (arc / 2))
                    val right = (facing + (arc / 2))
                    val angle = VectorUtils.getAngle(ship.shield.location, proj.location)

                    if (angle >= left || angle <= right) {
                        damageMultiplier *= blockedThreatLevel
                    }
                }
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

            if (ship.hullLevel > 0.1f) {
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_USE_SHIELDS, 20f)
            }
        }
        if (ship.hullLevel <= 0.2f) threat *= 2f // uh oh

        if (threat >= ACTIVATE_THRESH) {
            return Command.ACTIVATE
        } else {
            return Command.DEACTIVATE
        }

        //return Command.STANDBY
    }
}