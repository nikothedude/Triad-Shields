package niko.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.StatusData
import com.fs.starfarer.api.util.FaderUtil
import com.fs.starfarer.api.util.FlickerUtilV2
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.combat.entities.DamagingExplosion
import com.fs.starfarer.combat.entities.MovingRay
import niko.ReflectionUtilsTwo
import niko.TS_modPlugin
import niko.niko_MPC_reflectionUtils
import org.dark.shaders.distortion.DistortionShader
import org.dark.shaders.distortion.RippleDistortion
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.rotateAroundPivot
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.*
import kotlin.math.*

class MPC_triadShields: BaseShipSystemScript(), DamageTakenModifier {

    companion object {
        var SIDE_LENGTH: Float = 20f
        val DAMAGE_COLOR = Color.RED
        val BASE_COLOR = Color(100, 165, 255, 255)
        val FLOATY_COLOR = Color(100, 100, 255, 220)
        const val SHIELD_RADIUS = 20f
        const val SHIELD_SIZE_INVERSE = 1f
        const val BEAM_DAMAGE_TAKEN_MULT = 0.75f
        const val EXPLOSION_DAMAGE_TAKEN_MULT = 0.4f
        const val OFFLINE_REGEN_MULT = 3f

        const val BASE_SHIELD_DOWNTIME = 5f
        const val SHIELD_BREAK_DURATION_DAMAGE_DIVISOR = 250f

        const val RAYCAST_FAIL_TIMES_TIL_END = 4

        val sizesToStrength = hashMapOf<ShipAPI.HullSize, Float>(
            Pair(ShipAPI.HullSize.FIGHTER, 20f),
            Pair(ShipAPI.HullSize.FRIGATE, 100f),
            Pair(ShipAPI.HullSize.DESTROYER, 150f),
            Pair(ShipAPI.HullSize.CRUISER, 200f),
            Pair(ShipAPI.HullSize.CAPITAL_SHIP, 350f),
        )
        val sizesToDiss = hashMapOf<ShipAPI.HullSize, Float>(
            Pair(ShipAPI.HullSize.FIGHTER, 5f),
            Pair(ShipAPI.HullSize.FRIGATE, 10f),
            Pair(ShipAPI.HullSize.DESTROYER, 20f),
            Pair(ShipAPI.HullSize.CRUISER, 40f),
            Pair(ShipAPI.HullSize.CAPITAL_SHIP, 60f),
        )
    }

    class ShieldPieceConnection(var from: ShieldPiece, var to: ShieldPiece) {
        var baseLength: Float = 0f

        init {
            baseLength = Misc.getDistance(from.offset, to.offset)
            baseLength *= 0.9f + Math.random().toFloat() * 0.2f
        }

        fun advance(amount: Float) {
            val fLoc = from.adjustedOffset
            val tLoc = to.adjustedOffset
            val length = Misc.getDistance(fLoc, tLoc)
            val diff = length - baseLength

            val k = 1f
            val accel = diff * k

            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(fLoc, tLoc))

            dir.scale(accel * amount)
            Vector2f.add(from.vel, dir, from.vel)
            dir.negate()
            Vector2f.add(to.vel, dir, to.vel)


            val maxOff = 20f
            from.off.x += from.vel.x * amount
            from.off.y += from.vel.y * amount
            if (from.off.length() > maxOff) from.off.scale(maxOff / from.off.length())

            to.off.x += to.vel.x * amount
            to.off.y += to.vel.y * amount
            if (to.off.length() > maxOff) to.off.scale(maxOff / to.off.length())
        }
    }

    class ShieldPiece(var ship: ShipAPI, val system: MPC_triadShields, upsideDown: Boolean, x: Float, y: Float, var side: Float) {
        val interval = IntervalUtil(1f, 1f)

        var offset: Vector2f = Vector2f()

        var off: Vector2f = Vector2f() // secondary offset due to movement of individual triangles
        var vel: Vector2f = Vector2f()

        var sprite: SpriteAPI
        var upsideDown: Boolean = false

        var p1: Vector2f? = null
        var p2: Vector2f? = null
        var p3: Vector2f? = null
        var baseAlphaMult: Float = 1f
        var p1Alpha: Float = 1f
        var p2Alpha: Float = 1f
        var p3Alpha: Float = 1f

        var fader: FaderUtil
        var flicker: FlickerUtilV2?
        var forceAdvance = false

        var maxHealth = sizesToStrength[ship.hullSize] ?: 100f
        var health = maxHealth

        var fluxEfficiency = 1f

        // if above 0, means the shield is broken
        var downtime = 0f

        var diss = sizesToDiss[ship.hullSize] ?: 25f

        var drone: ShipAPI? = null // only used for returning damage apply results

        init {

            offset.set(x, y)
            this.upsideDown = upsideDown

            fader = FaderUtil(0f, 0.25f, 0.25f)
            fader.brightness = Math.random().toFloat() * 1f
            fader.setBounce(true, true)
            fader.fadeIn()

            flicker = FlickerUtilV2()


            sprite = Global.getSettings().getSprite("graphics/hud/line8x8.png")


            // updside down means the flat side is on the left
            // p1 is always the lone point, p2->p3 the flat side on the left/right
            // triangles are arranged as if ship is pointed at a 0 degree angle, i.e. facing right
            val height = (side * sqrt(3.0) / 2f).toFloat()
            if (upsideDown) {
                p1 = Vector2f(x + height / 2f, y)
                p2 = Vector2f(x - height / 2f - 1, y - side / 2f)
                p3 = Vector2f(x - height / 2f - 1, y + side / 2f)
            } else {
                p1 = Vector2f(x - height / 2f, y)
                p2 = Vector2f(x + height / 2f, y - side / 2f)
                p3 = Vector2f(x + height / 2f, y + side / 2f)
            }

            updatePointAlpha()

            if (!isUseless()) {
                drone = Global.getCombatEngine().createFXDrone(Global.getSettings().getVariant("MPC_triadDrone_shield"))
                val notNullDrone = drone!!
                //drone = fleetManager.spawnShipOrWing("MPC_triadDrone_shield", Vector2f(999999f, 999999f), 0f)
                //drone.addTag(Tags.VARIANT_FX_DRONE)
                //ReflectionUtilsTwo.set("id", drone, "")
                notNullDrone.setShield(ShieldAPI.ShieldType.OMNI, 0f, 1f, 360f)
                notNullDrone.collisionClass = CollisionClass.FIGHTER
                notNullDrone.shipAI = null
                notNullDrone.aiFlags.setFlag(ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)
                notNullDrone.location.set(999999f, 999999f)
                notNullDrone.collisionRadius = SHIELD_RADIUS * 1.1f
                notNullDrone.shield.radius = SHIELD_RADIUS
                ship.isAlly = ship.isAlly
                ship.isHoldFire = true

                notNullDrone.mutableStats.fluxCapacity.modifyFlat("MPC_triadShield_${this}", 9999999f)
                notNullDrone.mutableStats.fluxDissipation.modifyFlat("MPC_triadShield_${this}", 9999999f)
                notNullDrone.mutableStats.overloadTimeMod.modifyMult("MPC_triadShield_${this}", 1.5f)
                notNullDrone.mutableStats.hardFluxDissipationFraction.modifyFlat("MPC_triadShield_${this}", 1f)
                notNullDrone.mutableStats.engineDamageTakenMult.modifyMult("MPC_triadShield_${this}", 0f)
                notNullDrone.mutableStats.dynamic.getStat(Stats.SHIELD_PIERCED_MULT).modifyMult("MPC_triadShield_${this}", 0f)
                notNullDrone.shieldCenterEvenIfNoShield.set(0f, 0f)
                notNullDrone.hullSize = ShipAPI.HullSize.FIGHTER
                niko_MPC_reflectionUtils.invoke("updateFluxValuesFromStats", notNullDrone.fluxTracker)
                for (layer in notNullDrone.activeLayers.toMutableSet()) {
                    notNullDrone.activeLayers.remove(layer)
                }
                notNullDrone.extraAlphaMult2 = 0f
            }
        }

        fun checkCollision(loc: Vector2f, entity: DamagingProjectileAPI? = null): Boolean {
            if (entity?.source == ship || entity?.owner == ship.owner && (entity.collisionClass == CollisionClass.MISSILE_NO_FF || entity.collisionClass == CollisionClass.PROJECTILE_NO_FF)) {
                return false
            }

            var loc = loc
            val realLoc = getRealLoc()

            if (offline()) return false

            if (entity is DamagingExplosion) {
                val radius = entity.explosionSpecIfExplosion.radius
                val dist = MathUtils.getDistance(realLoc, loc)
                if (dist <= radius) {
                    return true
                }
            }

            val colRadius = entity?.collisionRadius ?: 0f
            var dist = MathUtils.getDistance(realLoc, loc) - colRadius
            if (entity?.tailEnd != null && entity is MovingRay) {
                // todo fix

                val rayDist = (MathUtils.getDistance(entity.tailEnd, loc)) / 4f
                loc = MathUtils.getPointOnCircumference(entity.location, rayDist, Misc.normalizeAngle(entity.facing))
                /*Global.getCombatEngine().addHitParticle(
                    loc,
                    Misc.ZERO,
                    25f,
                    1f,
                    1f,
                    Color.GREEN
                )*/
            }
            //val checkLoc = MathUtils.getPointOnCircumference(check.location, check.collisionRadius, VectorUtils.getAngle(check.location, realLoc))
            //if (!CollisionUtils.isPointWithinCollisionCircle(checkLoc, drone)) continue
            val realP1 = Vector2f(ship.location).translate(p1!!.x, p1!!.y).rotateAroundPivot(ship.location, ship.facing)
            val realP2 = Vector2f(ship.location).translate(p2!!.x, p2!!.y).rotateAroundPivot(ship.location, ship.facing)
            val realP3 = Vector2f(ship.location).translate(p3!!.x, p3!!.y).rotateAroundPivot(ship.location, ship.facing)
            if (!Misc.isPointInBounds(loc, listOf(realP1, realP2, realP3))) {
                return false
            }
            return true
        }

        fun isUseless(): Boolean {
            return (p1Alpha == 0f && p2Alpha == 0f && p3Alpha == 3f)
        }

        fun updatePointAlpha() {
            //if (true) return;
            val bounds = ship.exactBounds
            bounds.update(Vector2f(0f, 0f), 0f)
            p1Alpha = getPointAlpha(p1!!)
            p2Alpha = getPointAlpha(p2!!)
            p3Alpha = getPointAlpha(p3!!)

            baseAlphaMult = max(p1Alpha, p2Alpha)
            baseAlphaMult = max(baseAlphaMult, p3Alpha)
        }

        fun getPointAlpha(p: Vector2f): Float {
            val bounds = ship.exactBounds

            var minDist = Float.MAX_VALUE
            val boundsPoints: MutableList<Vector2f?> = ArrayList<Vector2f?>()
            for (segment in bounds.segments) {
                val n = Misc.closestPointOnSegmentToPoint(segment.p1, segment.p2, p)
                val dist = Misc.getDistance(n, p) * SHIELD_SIZE_INVERSE
                if (dist < minDist) minDist = dist

                boundsPoints.add(segment.p1)
            }
            boundsPoints.add(bounds.segments[bounds.segments.size - 1].p2)

            var minAlphaAt: Float = SIDE_LENGTH * 1f
            val minAlpha = 0f
            val inBounds = Misc.isPointInBounds(p, boundsPoints)
            if (inBounds) {
                minAlphaAt = 0f
            }

            if (minDist > minAlphaAt) {
                return minAlpha
            }

            return max(minAlpha, 1f - min(1f, minDist / (minAlphaAt * 2f)))
        }

        val adjustedOffset: Vector2f
            get() = Vector2f.add(offset, off, Vector2f())

        val center: Vector2f
            get() {
                val result = Vector2f(offset)
                Misc.rotateAroundOrigin(result, ship.facing)
                Vector2f.add(ship.location, result, result)
                return result
            }

        fun advance(amount: Float) {

            fader.advance(amount * (0.5f + 0.5f * Math.random().toFloat()))
            if (drone == null) return
            val notNullDrone = drone!!
            notNullDrone.fluxTracker.currFlux = 0f
            if (!notNullDrone.shield.isOn && !notNullDrone.fluxTracker.isOverloaded) {
                notNullDrone.shield.toggleOn()
            }
            repositionDrone()
            notNullDrone.facing = ship.facing

            adjustShieldParams()
            regen(amount)

            interval.advance(amount)

            val engine = Global.getCombatEngine()

            if (getShieldStrength() > 0f && !ship.isPhased) {
                val length = SHIELD_RADIUS
                var realLoc = getRealLoc()
                val objs = engine.projectiles.filter { (MathUtils.getDistance(realLoc, it.location) - it.collisionRadius) <= length }.iterator()
                while (objs.hasNext()) {
                    val check = objs.next()
                    if (check is DamagingProjectileAPI) {
                        if (check.source == ship) continue
                        if (check.customData["MPC_triadShieldsNOCHECK"] == true) continue

                        val point = MathUtils.getPointOnCircumference(check.location, check.collisionRadius, VectorUtils.getAngle(check.location, ship.location))
                        if (!checkCollision(point, check)) continue

                        takeDamage(check.damage, check)
                        break
                    }
                }
            }
        }

        private fun adjustShieldParams() {
            fluxEfficiency = (ship.shield?.fluxPerPointOfDamage ?: 1f) * (getCoherencyMult())
        }

        private fun getCoherencyMult(): Float {
            var incoherency = 1f
            if (p1Alpha <= 0.05f) incoherency += 1f
            if (p2Alpha <= 0.05f) incoherency += 1f
            if (p3Alpha <= 0.05f) incoherency += 1f

            return incoherency
        }

        private fun regen(amount: Float) {
            if (broken()) {
                downtime -= amount
                downtime = downtime.coerceAtLeast(0f)
                return
            }
            var base = diss
            if (offline()) {
                base *= OFFLINE_REGEN_MULT
            }

            base *= amount

            adjustHealth(base)
        }

        fun takeDamage(damage: DamageAPI?, source: DamagingProjectileAPI? = null) {
            var damage = damage
            if (damage == null && source != null) damage = source.damage ?: return
            var realDamage = damage!!.damage
            realDamage *= when (damage.type) {
                DamageType.KINETIC -> 2f
                DamageType.HIGH_EXPLOSIVE -> 0.5f
                DamageType.FRAGMENTATION -> 0.25f
                DamageType.ENERGY -> 1f
                DamageType.OTHER -> 1f
            }
            if (damage.isDps) realDamage *= 0.1f
            val realLoc = getRealLoc()
            realDamage *= fluxEfficiency
            adjustHealth(-realDamage)
            realDamage /= fluxEfficiency
            val result = ReflectionUtilsTwo.invoke("applyDamage", drone!!, realLoc, damage, true, realDamage, Any()) as ApplyDamageResultAPI
            source?.let { ReflectionUtilsTwo.invoke("notifyDealtDamage", it, realLoc, result, drone, ignoreParams = true) }

            val type = if (realDamage >= 600f) "heavy" else if (realDamage >= 250f) "solid" else "light"

            Global.getSoundPlayer().playSound(
                "hit_shield_${type}_gun",
                1.5f,
                1f,
                realLoc,
                Misc.ZERO
            )

            fun createHitRipple(location: Vector2f, velocity: Vector2f, dmg: Float, direction: Float,
                                shieldRadius: Float, dweller: Boolean) {
                if (dmg < 25f) {
                    return
                }

                var fadeTime = dmg.toDouble().pow(0.25).toFloat() * 0.1f
                var size = dmg.toDouble().pow(0.3333333).toFloat() * 15f

                val ratio = min(size / shieldRadius, 1f)
                val arc = 90f - ratio * 14.54136f // Don't question the magic number

                var start1: Float = direction - arc
                if (start1 < 0f) {
                    start1 += 360f
                }
                var end1: Float = direction + arc
                if (end1 >= 360f) {
                    end1 -= 360f
                }

                var start2: Float = direction + arc
                if (start2 < 0f) {
                    start2 += 360f
                }
                var end2: Float = direction - arc
                if (end2 >= 360f) {
                    end2 -= 360f
                }

                if (dweller) {
                    fadeTime *= 1.5f
                    size *= 1.5f

                    val ripple = RippleDistortion(location, velocity)
                    ripple.setSize(size)
                    ripple.setIntensity(size * 0.1f)
                    ripple.setFrameRate(60f / fadeTime)
                    ripple.fadeInSize(fadeTime * 1.2f)
                    ripple.fadeOutIntensity(fadeTime)
                    ripple.setSize(size * 0.2f)
                    ripple.setArc(start1, end1)
                    DistortionShader.addDistortion(ripple)
                } else {
                    var ripple = RippleDistortion(location, velocity)
                    ripple.setSize(size)
                    ripple.setIntensity(size * 0.3f)
                    ripple.setFrameRate(60f / fadeTime)
                    ripple.fadeInSize(fadeTime * 1.2f)
                    ripple.fadeOutIntensity(fadeTime)
                    ripple.setSize(size * 0.2f)
                    ripple.setArc(start1, end1)
                    DistortionShader.addDistortion(ripple)

                    ripple = RippleDistortion(location, velocity)
                    ripple.setSize(size)
                    ripple.setIntensity(size * 0.075f)
                    ripple.setFrameRate(60f / fadeTime)
                    ripple.fadeInSize(fadeTime * 1.2f)
                    ripple.fadeOutIntensity(fadeTime)
                    ripple.setSize(size * 0.2f)
                    ripple.setArc(start2, end2)
                    DistortionShader.addDistortion(ripple)
                }
            }

            val angle = source?.let { VectorUtils.getFacing(VectorUtils.getDirectionalVector(ship.location, it.location)) } ?: 0f
            if (TS_modPlugin.graphicsLibEnabled) {
                createHitRipple(realLoc, ship.velocity, realDamage * 0.5f, angle, SIDE_LENGTH, false)
            }
            Global.getCombatEngine().addFloatingDamageText(
                realLoc,
                realDamage,
                FLOATY_COLOR,
                ship,
                source
            )

            source?.setCustomData("MPC_triadShieldsNOCHECK", true)
            source?.let { Global.getCombatEngine().removeEntity(it) }
        }

        private fun repositionDrone() {
            drone?.location?.set(getRealLoc())
            drone?.variant?.removeTag(Tags.VARIANT_FX_DRONE)
            forceAdvance = true
        }

        fun getRealLoc(): Vector2f {
            return Vector2f(ship.location).translate(offset.x, offset.y).rotateAroundPivot(ship.location,  Misc.normalizeAngle(ship.facing))
        }

        fun getShieldStrength(): Float {
            return (health / maxHealth)
        }

        fun offline(): Boolean {
            return (broken() || system.effectLevel < 0.1f)
        }

        fun broken(): Boolean {
            return !ship.isAlive || downtime > 0f
        }

        fun adjustHealth(amount: Float) {
            if (broken()) return // sorry cant regen
            health += amount
            health = health.coerceAtMost(maxHealth)
            if (health <= 0f) {
                health = 0f
                shieldBroken(amount)
                return
            }

        }

        private fun shieldBroken(amount: Float) {
            downtime = (BASE_SHIELD_DOWNTIME * (amount / SHIELD_BREAK_DURATION_DAMAGE_DIVISOR)).coerceAtLeast(BASE_SHIELD_DOWNTIME)
        }

        /**
         * Assumes translated to ship location and rotated, i.e. offset is the actual location to render at.
         * @param alphaMult
         */
        fun render(alphaMult: Float) {

            if (isUseless() || offline()) return

            var alphaMult = alphaMult
            var color = BASE_COLOR

            GL11.glPushMatrix()
            GL11.glTranslatef(off.x, off.y, 0f)

            for (i in 0..1) {
                val origW = 8f
                val origH = 8f

                GL11.glEnable(GL11.GL_TEXTURE_2D)
                sprite.bindTexture()
                sprite.setSize(origW * (20f / SIDE_LENGTH), origH * (20f / SIDE_LENGTH))
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)

                var t = 9f
                var a = 0.5f * getSystemStatusAlphaMult()
                if (i == 1) {
                    t = 4f
                    a = 1f
                }

                val strength = getShieldStrength()
                color = Misc.interpolateColor(color, DAMAGE_COLOR, 1 - strength)

                if (upsideDown) {
                    GL11.glBegin(GL11.GL_QUAD_STRIP)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x - t, p1!!.y)

                    Misc.setColor(color, alphaMult * p2Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p2!!.x, p2!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p2!!.x + t * 0.5f, p2!!.y + t)

                    Misc.setColor(color, alphaMult * p3Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p3!!.x, p3!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p3!!.x + t * 0.5f, p3!!.y - t)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x - t, p1!!.y)

                    GL11.glEnd()
                } else {
                    GL11.glBegin(GL11.GL_QUAD_STRIP)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x + t, p1!!.y)

                    Misc.setColor(color, alphaMult * p2Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p2!!.x, p2!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p2!!.x - t * 0.5f, p2!!.y + t)

                    Misc.setColor(color, alphaMult * p3Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p3!!.x, p3!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p3!!.x - t * 0.5f, p3!!.y - t)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x + t, p1!!.y)

                    GL11.glEnd()
                }
            }

            GL11.glPopMatrix()
            return
        }

        private fun getSystemStatusAlphaMult(): Float {
            return (system.effectLevel)
        }
    }

    class TriadShieldVisuals(var ship: ShipAPI, var script: MPC_triadShields): BaseCombatLayeredRenderingPlugin() {
        var pieces: MutableList<ShieldPiece> = ArrayList<ShieldPiece>()
        var connections: MutableList<ShieldPieceConnection> = ArrayList<ShieldPieceConnection>()

        init {
            addShieldPieces()
        }

        fun addShieldPieces() {

            SIDE_LENGTH = 20f

            pieces.clear()
            //SIDE_LENGTH = 10f;
            //SIDE_LENGTH = 120f;
            val side: Float = SIDE_LENGTH
            val height = (side * sqrt(3.0) / 2f).toFloat()
            val centerFromBottom = (sin(Math.toRadians(30.0)) * height).toFloat()
            //centerFromBottom = side/2f;
            var gridHeight = (ship.collisionRadius / side).toInt() * 2
            if (gridHeight / 2 != 0) gridHeight++
            if (gridHeight < 6) gridHeight = 6
            var gridWidth = (ship.collisionRadius / height).toInt() * 2
            if (gridWidth / 2 != 0) gridWidth++
            if (gridWidth < 6) gridWidth = 6
            for (i in -gridWidth / 2..<gridWidth / 2) {
                for (j in -gridHeight / 2..<gridHeight / 2) {

                    val lowX = i * height + height / 2f
                    val highX = (i + 1) * height + height / 2f
                    var centerY = j * side + side / 2f
                    var piece = ShieldPiece(ship, script,true, lowX + centerFromBottom, centerY, side - 2f)
                    if (piece.baseAlphaMult > 0) {
                        pieces.add(piece)
                    }

                    if (j != gridHeight / 2 - 1) {
                        centerY += side / 2f
                        piece = ShieldPiece(ship, script,false, highX - centerFromBottom, centerY, side - 2f)
                        if (piece.baseAlphaMult > 0) {
                            pieces.add(piece)
                        }
                    }
                }
            }

            val maxDist: Float = SIDE_LENGTH * 1.2f
            for (i in 0..<pieces.size - 1) {
                val curr = pieces.get(i)
                for (j in i + 1..<pieces.size) {
                    val other = pieces.get(j)
                    if (curr === other) continue
                    if (Misc.getDistance(curr.offset, other.offset) > maxDist) continue

                    val conn = ShieldPieceConnection(curr, other)
                    connections.add(conn)
                }
            }
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers?> {
            return EnumSet.of<CombatEngineLayers?>(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        }

        override fun isExpired(): Boolean {
            return false
        }

        override fun getRenderRadius(): Float {
            return ship.collisionRadius + 100f
        }

        override fun advance(amount: Float) {
            var amount = amount
            amount *= ship.mutableStats.timeMult.modified
            entity.location.set(ship.location)

            if (Global.getCombatEngine().playerShip == ship) {
                val strength = script.approximateShieldStrength()
                val amount = "${(strength * 100f).toInt()}%"
                val warn = (strength < 0.4f)
                val warnString = if (warn) " (LOW INTEGRITY)" else ""

                Global.getCombatEngine().maintainStatusForPlayerShip(
                    "MPC_triadShieldsOne",
                    "graphics/hullsys/MPC_triadShields.png",
                    "Triad Shields$warnString",
                    "SHIELD STRENGTH: $amount",
                    warn
                )
                if (script.effectLevel <= 0.1f) {
                    Global.getCombatEngine().maintainStatusForPlayerShip(
                        "MPC_triadShieldsTwo",
                        "graphics/hullsys/MPC_triadShields.png",
                        "Triad Shields Offline",
                        "RAPIDLY REGENERATING",
                        false
                    )
                }
            }

            if (Global.getCombatEngine().isPaused) return

            for (piece in pieces) {
                piece.advance(amount)
            }

            for (conn in connections) {
                conn.advance(amount)
            }
        }

        override fun render(layer: CombatEngineLayers?, viewport: ViewportAPI) {
            var alphaMult = viewport.alphaMult

            if (ship.isDoNotRenderShield) return
            alphaMult *= script.effectLevel

            alphaMult *= ship.alphaMult
            alphaMult *= ship.extraAlphaMult2
            alphaMult *= ship.extraAlphaMult

            if (alphaMult <= 0f) return

            GL11.glPushMatrix()
            GL11.glTranslatef(ship.location.x, ship.location.y, 0f)
            GL11.glRotatef(ship.facing, 0f, 0f, 1f)

            for (piece in pieces) {
                piece.render(alphaMult)
            }
            GL11.glPopMatrix()
        }
    }

    protected var visuals: TriadShieldVisuals? = null
    var effectLevel = 1f

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String?,
        state: ShipSystemStatsScript.State?,
        effectLevel: Float
    ) {
        this.effectLevel = effectLevel

        var id = id
        var ship: ShipAPI? = null
        var player = false
        if (stats.entity is ShipAPI) {
            ship = stats.entity as ShipAPI?
            player = ship == Global.getCombatEngine().playerShip
            id = id + "_" + ship!!.id
        } else {
            return
        }

        if (!ship.hasListener(this)) {
            ship.addListener(this)
        }

        if (visuals == null) {
            visuals = TriadShieldVisuals(ship, this)
            Global.getCombatEngine().addLayeredRenderingPlugin(visuals)
            ship.addListener(this)
        }

        if (Global.getCombatEngine().isPaused) {
            return
        }

        if (state == ShipSystemStatsScript.State.COOLDOWN || state == ShipSystemStatsScript.State.IDLE) {
            unapply(stats, id)
            return
        }
    }


    override fun unapply(stats: MutableShipStatsAPI, id: String?) {
        var id = id
        var ship: ShipAPI? = null
        var player = false
        if (stats.entity is ShipAPI) {
            ship = stats.entity as ShipAPI?
            player = ship === Global.getCombatEngine().playerShip
            id = id + "_" + ship!!.id
        } else {
            return
        }
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State?, effectLevel: Float): StatusData? {
        /*val strength = approximateShieldStrength()
        val amount = "${(strength * 100f).toInt()}%"
        val data = StatusData("Shield Strength: $amount", strength <= 0.4f)
        return data*/
        // inconsistant. we do it in advance
        return null
    }

    override fun modifyDamageTaken(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean
    ): String? {

        if (point == null) return null
        if (damage == null) return null
        if (shieldHit) return null
        if (param is DamagingExplosion) return blockExplosion(param, point, damage)
        if (param is BeamAPI) return blockBeam(param, point, damage)

        return null
    }

    private fun getBlockersInDirection(point: Vector2f, dir: Vector2f): MutableSet<ShieldPiece> {
        val blockers = HashSet<ShieldPiece>()

        val visuals = visuals ?: return blockers
        var point = Vector2f(point) as Vector2f

        var fails = 0f
        while (fails < RAYCAST_FAIL_TIMES_TIL_END) {

            val newBlockers = visuals.pieces.filter { it.checkCollision(point) }
            if (newBlockers.isEmpty()) {
                fails++
            }

            blockers += newBlockers

            point = (point.translate(dir.x, dir.y))
        }

         return blockers
    }

    private fun blockExplosion(
        exploson: DamagingExplosion,
        point: Vector2f,
        damage: DamageAPI
    ): String? {

        val visuals = visuals ?: return null
        val oldDmg = damage.baseDamage

        var blocked = getBlockersInDirection(point, VectorUtils.getDirectionalVector(point, visuals.ship.location)).isNotEmpty()

        for (piece in visuals.pieces) {
            if (!piece.checkCollision(point, exploson)) continue

            val loc = piece.getRealLoc()
            val rad = exploson.explosionSpecIfExplosion.radius
            val core = exploson.explosionSpecIfExplosion.coreRadius
            val maxDmg = exploson.explosionSpecIfExplosion.maxDamage
            val minDmg = exploson.explosionSpecIfExplosion.minDamage

            var dist = MathUtils.getDistance(loc, point)
            if (dist <= core) {
                damage.damage = damage.damage.coerceAtLeast(maxDmg)
            } else {
                val diff = rad - core
                dist -= core

                // todo test
                val newDamage = (maxDmg * (dist / diff)).coerceAtLeast(minDmg)
                damage.damage = newDamage
            }

            damage.modifier.modifyMult("MPC_triadShieldsExplosionResist", EXPLOSION_DAMAGE_TAKEN_MULT)
            piece.takeDamage(damage)
            damage.modifier.unmodify("MPC_triadShieldsExplosionResist")
            damage.damage = oldDmg
        }

        if (blocked) {
            damage.modifier?.modifyMult("MPC_triadBlocked", 0f)
            return "MPC_triadBlocked"
        }
        return null
    }

    private fun blockBeam(
        beam: BeamAPI,
        point: Vector2f,
        damage: DamageAPI
    ): String? {

        var blocked = false
        val visuals = visuals ?: return null
        val to = beam.to
        val origDamage = damage.damage

        val blockers = getBlockersInDirection(point, VectorUtils.getDirectionalVector(visuals.ship.location, point))

        for (piece in blockers) {
            damage.modifier?.modifyMult("MPC_triadBlockedShield", 1 - BEAM_DAMAGE_TAKEN_MULT)
            piece.takeDamage(damage)
            damage.modifier?.unmodify("MPC_triadBlockedShield")

            blocked = true
        }

        if (blocked) {
            val damageTaken = (BEAM_DAMAGE_TAKEN_MULT / blockers.size)

            damage.modifier?.modifyMult("MPC_triadBlocked", damageTaken)
            return "MPC_triadBlocked"
        }

        return null
    }

    fun approximateShieldStrength(): Float {
        var base = 0f
        val visuals = visuals ?: return 0f
        for (piece in visuals.pieces) {
            if (piece.isUseless()) {
                continue
            }
            val ratio = (piece.health / piece.maxHealth)
            base += ratio
        }
        base /= visuals.pieces.size

        return base
    }

}