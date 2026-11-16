package com.zombiedots.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The simulation. Everything is in dp units and seconds, with no Android code,
 * so it can be tested on its own.
 */

class Zombie(var x: Float, var y: Float, val speed: Float) {
    var vx = 0f
    var vy = 0f
    var target: Human? = null
    var retarget = 0f
    var entered = false
    var dead = false
    var wanderAngle = 0f
}

enum class Role { PLAYER, HUNTER, STRAY }

class Human(var x: Float, var y: Float, var role: Role) {
    var vx = 0f
    var vy = 0f
    var cooldown = 0f
    var slotAngle = 0f
    var wanderAngle = 0f
    var dead = false
    val speed: Float
        get() = when (role) {
            Role.PLAYER -> 46f
            Role.HUNTER -> 44f
            Role.STRAY -> 42f
        }
}

class Tracer(val x1: Float, val y1: Float, val x2: Float, val y2: Float, var life: Float)
class Splat(val x: Float, val y: Float, var life: Float, val size: Float)
class Nuke(val x: Float, val y: Float, var age: Float)
class FloatText(val x: Float, var y: Float, val text: String, var life: Float)

enum class Phase { SETUP, RUNNING, OVER }

class World(private val rnd: Random = Random.Default) {
    var width = 400f
    var height = 700f

    val zombies = ArrayList<Zombie>()
    val humans = ArrayList<Human>()
    val tracers = ArrayList<Tracer>()
    val splats = ArrayList<Splat>()
    val nukes = ArrayList<Nuke>()
    val texts = ArrayList<FloatText>()

    var phase = Phase.SETUP
    var time = 0f
    var kills = 0
    var wave = 0
    var waveTimer = 0f
    var strayTimer = 0f
    var nukeCharges = 2
    var nukeTimer = 0f
    var lost = 0

    companion object {
        const val MAX_ZOMBIES = 700
        const val NUKE_RADIUS = 150f
        const val NUKE_RECHARGE = 30f
        const val MAX_NUKES = 3
        const val WAVE_TIME = 25f
        const val MAX_HUNTERS = 8
        const val ZOMBIE_R = 3.2f
        const val HUMAN_R = 4.5f
    }

    // ------------------------------------------------------------------ setup

    fun reset() {
        zombies.clear(); humans.clear(); tracers.clear(); splats.clear(); nukes.clear(); texts.clear()
        phase = Phase.SETUP
        time = 0f; kills = 0; wave = 0; waveTimer = 0f; strayTimer = 15f // first stray after ~15s
        nukeCharges = 2; nukeTimer = 0f; lost = 0
    }

    /** Drops the survivor and one hunter at (x, y) and starts the outbreak. */
    fun start(x: Float, y: Float) {
        reset()
        val cx = x.coerceIn(30f, width - 30f)
        val cy = y.coerceIn(30f, height - 30f)
        humans.add(Human(cx, cy, Role.PLAYER))
        // Start with a single hunter; more join as strays are found
        val a = rnd.nextFloat() * 6.283f
        humans.add(Human(cx + cos(a) * 22f, cy + sin(a) * 22f, Role.HUNTER).also { it.slotAngle = a })
        phase = Phase.RUNNING
        spawnWave(first = true)
    }

    private fun spawnWave(first: Boolean = false) {
        wave++
        val count = if (first) 30 else 25 + wave * 12
        val edges = if (rnd.nextFloat() < 0.5f) 1 else 2
        val chosen = (0 until 4).shuffled(rnd).take(edges)
        repeat(count) { i ->
            if (zombies.size >= MAX_ZOMBIES) return
            val (x, y) = edgePoint(chosen[i % chosen.size])
            addZombie(x, y)
        }
        if (!first) texts.add(FloatText(width / 2f, height / 2f, "WAVE $wave", 2.2f))
    }

    private fun edgePoint(edge: Int): Pair<Float, Float> {
        val m = 12f + rnd.nextFloat() * 30f
        return when (edge) {
            0 -> rnd.nextFloat() * width to -m
            1 -> width + m to rnd.nextFloat() * height
            2 -> rnd.nextFloat() * width to height + m
            else -> -m to rnd.nextFloat() * height
        }
    }

    private fun zombieBaseSpeed() = min(26f + wave * 2.5f, 50f)

    private fun addZombie(x: Float, y: Float, inside: Boolean = false) {
        if (zombies.size >= MAX_ZOMBIES) return
        val z = Zombie(x, y, zombieBaseSpeed() * (0.75f + rnd.nextFloat() * 0.6f))
        z.entered = inside
        z.wanderAngle = rnd.nextFloat() * 6.283f
        z.retarget = rnd.nextFloat() * 0.3f
        zombies.add(z)
    }

    // ------------------------------------------------------------------ player actions

    /** Tap: a small horde shambles out of the ground. */
    fun dropZombies(x: Float, y: Float) {
        if (phase != Phase.RUNNING) return
        repeat(8) {
            val a = rnd.nextFloat() * 6.283f
            val r = rnd.nextFloat() * 18f
            addZombie((x + cos(a) * r).coerceIn(2f, width - 2f), (y + sin(a) * r).coerceIn(2f, height - 2f), inside = true)
        }
    }

    /** Double-tap: nuke. Returns false if no charges are left. */
    fun nuke(x: Float, y: Float): Boolean {
        if (phase != Phase.RUNNING || nukeCharges <= 0) return false
        nukeCharges--
        nukes.add(Nuke(x, y, 0f))
        var n = 0
        val r2 = NUKE_RADIUS * NUKE_RADIUS
        for (z in zombies) {
            val dx = z.x - x; val dy = z.y - y
            if (dx * dx + dy * dy < r2) { killZombie(z, quiet = true); n++ }
        }
        if (n > 0) texts.add(FloatText(x, y - 20f, "+$n", 1.4f))
        return true
    }

    private fun killZombie(z: Zombie, quiet: Boolean = false) {
        if (z.dead) return
        z.dead = true
        kills++
        if (splats.size < 500) splats.add(Splat(z.x, z.y, if (quiet) 3f else 5f, 2.5f + rnd.nextFloat() * 3f))
    }

    // ------------------------------------------------------------------ simulation

    val leader: Human?
        get() = humans.firstOrNull { it.role == Role.PLAYER && !it.dead }
            ?: humans.firstOrNull { it.role == Role.HUNTER && !it.dead }

    fun update(totalDt: Float) {
        if (phase != Phase.RUNNING) {
            ageEffects(totalDt)
            return
        }
        var left = totalDt
        while (left > 0f) {
            val dt = min(left, 1f / 60f)
            step(dt)
            left -= dt
            if (phase != Phase.RUNNING) break
        }
    }

    private fun ageEffects(dt: Float) {
        for (t in tracers) t.life -= dt
        tracers.removeAll { it.life <= 0f }
        for (s in splats) s.life -= dt * 0.25f
        splats.removeAll { it.life <= 0f }
        for (n in nukes) n.age += dt
        nukes.removeAll { it.age > 6f }
        for (t in texts) { t.life -= dt; t.y -= dt * 14f }
        texts.removeAll { it.life <= 0f }
    }

    private fun step(dt: Float) {
        time += dt
        ageEffects(dt)

        waveTimer += dt
        if (waveTimer >= WAVE_TIME) { waveTimer = 0f; spawnWave() }

        if (nukeCharges < MAX_NUKES) {
            nukeTimer += dt
            if (nukeTimer >= NUKE_RECHARGE) { nukeTimer = 0f; nukeCharges++ }
        } else nukeTimer = 0f

        strayTimer += dt
        if (strayTimer >= 30f) { strayTimer = 0f; maybeSpawnStray() }

        buildGrid()
        updateZombies(dt)
        updateHumans(dt)
        infect()

        zombies.removeAll { it.dead }
        humans.removeAll { it.dead }

        if (humans.isEmpty()) phase = Phase.OVER
    }

    private fun maybeSpawnStray() {
        val hunters = humans.count { it.role == Role.HUNTER }
        if (hunters >= MAX_HUNTERS) return
        // Pick the spot farthest from zombies out of a few tries
        var best: Pair<Float, Float>? = null
        var bestD = -1f
        repeat(12) {
            val x = 20f + rnd.nextFloat() * (width - 40f)
            val y = 20f + rnd.nextFloat() * (height - 40f)
            var d = Float.MAX_VALUE
            for (z in zombies) {
                val dx = z.x - x; val dy = z.y - y
                d = min(d, dx * dx + dy * dy)
            }
            if (d > bestD) { bestD = d; best = x to y }
        }
        val (x, y) = best ?: return
        humans.add(Human(x, y, Role.STRAY))
        texts.add(FloatText(x, y - 14f, "survivor spotted", 1.6f))
    }

    // Spatial grid so zombies can spread out without checking every pair
    private val cell = 10f
    private var gridW = 1
    private var gridH = 1
    private var heads = IntArray(1)
    private var next = IntArray(1)

    private fun buildGrid() {
        gridW = max(1, (width / cell).toInt() + 3)
        gridH = max(1, (height / cell).toInt() + 3)
        if (heads.size < gridW * gridH) heads = IntArray(gridW * gridH)
        heads.fill(-1, 0, gridW * gridH)
        if (next.size < zombies.size) next = IntArray(zombies.size * 2)
        for (i in zombies.indices) {
            val c = cellOf(zombies[i].x, zombies[i].y)
            next[i] = heads[c]
            heads[c] = i
        }
    }

    private fun cellOf(x: Float, y: Float): Int {
        val cx = ((x / cell).toInt() + 1).coerceIn(0, gridW - 1)
        val cy = ((y / cell).toInt() + 1).coerceIn(0, gridH - 1)
        return cy * gridW + cx
    }

    private fun updateZombies(dt: Float) {
        val alive = humans.filter { !it.dead }
        for (i in zombies.indices) {
            val z = zombies[i]
            z.retarget -= dt
            if (z.retarget <= 0f || z.target?.dead != false) {
                z.retarget = 0.25f + rnd.nextFloat() * 0.2f
                var best: Human? = null
                var bd = Float.MAX_VALUE
                for (h in alive) {
                    val dx = h.x - z.x; val dy = h.y - z.y
                    val d = dx * dx + dy * dy
                    if (d < bd) { bd = d; best = h }
                }
                z.target = best
            }

            var dx: Float
            var dy: Float
            val t = z.target
            if (t != null && !t.dead) {
                dx = t.x - z.x; dy = t.y - z.y
            } else {
                z.wanderAngle += (rnd.nextFloat() - 0.5f) * 2f * dt
                dx = cos(z.wanderAngle); dy = sin(z.wanderAngle)
            }
            val len = sqrt(dx * dx + dy * dy)
            if (len > 0.001f) { dx /= len; dy /= len }

            // Separation from neighbours
            var sx = 0f
            var sy = 0f
            val gx = ((z.x / cell).toInt() + 1).coerceIn(0, gridW - 1)
            val gy = ((z.y / cell).toInt() + 1).coerceIn(0, gridH - 1)
            for (oy in -1..1) for (ox in -1..1) {
                val cx = gx + ox; val cy = gy + oy
                if (cx < 0 || cy < 0 || cx >= gridW || cy >= gridH) continue
                var j = heads[cy * gridW + cx]
                var guard = 0
                while (j >= 0 && guard < 40) {
                    if (j != i) {
                        val o = zombies[j]
                        val ex = z.x - o.x; val ey = z.y - o.y
                        val d2 = ex * ex + ey * ey
                        if (d2 < 49f && d2 > 0.0001f) {
                            val d = sqrt(d2)
                            sx += ex / d * (7f - d) / 7f
                            sy += ey / d * (7f - d) / 7f
                        }
                    }
                    j = next[j]
                    guard++
                }
            }

            val wantX = (dx + sx * 1.4f) * z.speed
            val wantY = (dy + sy * 1.4f) * z.speed
            val k = min(1f, dt * 5f)
            z.vx += (wantX - z.vx) * k
            z.vy += (wantY - z.vy) * k
            z.x += z.vx * dt
            z.y += z.vy * dt

            if (!z.entered && z.x in 0f..width && z.y in 0f..height) z.entered = true
            if (z.entered) {
                z.x = z.x.coerceIn(1f, width - 1f)
                z.y = z.y.coerceIn(1f, height - 1f)
            }
        }
    }

    private fun updateHumans(dt: Float) {
        val lead = leader
        val hunterList = humans.filter { it.role == Role.HUNTER && !it.dead }
        for (h in humans) {
            if (h.dead) continue
            val fleeR = when (h.role) { Role.PLAYER -> 150f; Role.HUNTER -> 75f; Role.STRAY -> 130f }

            // Flee: push away from nearby zombies, harder the closer they are
            var fx = 0f
            var fy = 0f
            var threat = 0f
            for (z in zombies) {
                val dx = h.x - z.x; val dy = h.y - z.y
                if (dx > fleeR || dx < -fleeR || dy > fleeR || dy < -fleeR) continue
                val d2 = dx * dx + dy * dy
                if (d2 >= fleeR * fleeR || d2 < 0.0001f) continue
                val d = sqrt(d2)
                val w = (1f - d / fleeR)
                val ww = w * w
                fx += dx / d * ww
                fy += dy / d * ww
                threat += ww
            }

            // Walls and corners are deadly: steer away from them
            val m = 60f
            var wx = 0f
            var wy = 0f
            if (h.x < m) wx += (m - h.x) / m
            if (h.x > width - m) wx -= (h.x - (width - m)) / m
            if (h.y < m) wy += (m - h.y) / m
            if (h.y > height - m) wy -= (h.y - (height - m)) / m

            h.wanderAngle += (rnd.nextFloat() - 0.5f) * 3f * dt
            var ax: Float
            var ay: Float
            when (h.role) {
                Role.PLAYER -> {
                    // Drift back toward the middle when things are calm
                    val cx = (width / 2f - h.x) / width
                    val cy = (height / 2f - h.y) / height
                    var gx = 0f; var gy = 0f
                    if (hunterList.isNotEmpty()) {
                        for (o in hunterList) { gx += o.x; gy += o.y }
                        gx = (gx / hunterList.size - h.x) * 0.004f
                        gy = (gy / hunterList.size - h.y) * 0.004f
                    }
                    ax = fx * 2.2f + wx * 2.6f + cx * 0.6f + gx + cos(h.wanderAngle) * 0.15f
                    ay = fy * 2.2f + wy * 2.6f + cy * 0.6f + gy + sin(h.wanderAngle) * 0.15f
                }
                Role.HUNTER -> {
                    var tx = 0f; var ty = 0f
                    if (lead != null && lead !== h) {
                        h.slotAngle += dt * 0.4f
                        val sx = lead.x + cos(h.slotAngle) * 28f
                        val sy = lead.y + sin(h.slotAngle) * 28f
                        tx = (sx - h.x) / 40f; ty = (sy - h.y) / 40f
                        val tl = sqrt(tx * tx + ty * ty)
                        if (tl > 1.5f) { tx = tx / tl * 1.5f; ty = ty / tl * 1.5f }
                    } else {
                        tx = (width / 2f - h.x) / width; ty = (height / 2f - h.y) / height
                    }
                    ax = fx * 1.8f + wx * 2.4f + tx + cos(h.wanderAngle) * 0.1f
                    ay = fy * 1.8f + wy * 2.4f + ty + sin(h.wanderAngle) * 0.1f
                }
                Role.STRAY -> {
                    var tx = 0f; var ty = 0f
                    if (lead != null) {
                        tx = lead.x - h.x; ty = lead.y - h.y
                        val tl = sqrt(tx * tx + ty * ty)
                        if (tl < 26f) {
                            h.role = Role.HUNTER
                            h.slotAngle = atan2(-ty, -tx)
                            texts.add(FloatText(h.x, h.y - 14f, "Survivor Joined!", 2.0f))
                        }
                        if (tl > 0.01f) { tx /= tl; ty /= tl }
                    }
                    ax = fx * 2.4f + wx * 2.4f + tx
                    ay = fy * 2.4f + wy * 2.4f + ty
                }
            }

            val al = sqrt(ax * ax + ay * ay)
            val spd = h.speed * (if (threat > 0.05f || h.role == Role.STRAY) 1f else 0.55f)
            val wantX = if (al > 0.001f) ax / al * spd * min(1f, al * 2f) else 0f
            val wantY = if (al > 0.001f) ay / al * spd * min(1f, al * 2f) else 0f
            val k = min(1f, dt * 7f)
            h.vx += (wantX - h.vx) * k
            h.vy += (wantY - h.vy) * k
            h.x = (h.x + h.vx * dt).coerceIn(3f, width - 3f)
            h.y = (h.y + h.vy * dt).coerceIn(3f, height - 3f)

            shoot(h, dt)
        }
    }

    private fun shoot(h: Human, dt: Float) {
        if (h.role == Role.STRAY) return
        h.cooldown -= dt
        if (h.cooldown > 0f) return
        val range = if (h.role == Role.HUNTER) 120f else 85f
        var best: Zombie? = null
        var bd = range * range
        for (z in zombies) {
            if (z.dead || !z.entered) continue
            val dx = z.x - h.x; val dy = z.y - h.y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = z }
        }
        val z = best ?: return
        h.cooldown = if (h.role == Role.HUNTER) 0.45f + rnd.nextFloat() * 0.3f else 0.9f + rnd.nextFloat() * 0.4f
        val hit = rnd.nextFloat() < (if (h.role == Role.HUNTER) 0.8f else 0.6f)
        if (hit) {
            tracers.add(Tracer(h.x, h.y, z.x, z.y, 0.12f))
            killZombie(z)
        } else {
            val miss = (rnd.nextFloat() - 0.5f) * 20f
            tracers.add(Tracer(h.x, h.y, z.x + miss, z.y - miss, 0.12f))
        }
    }

    /** Any zombie touching a human turns them. */
    private fun infect() {
        val reach = ZOMBIE_R + HUMAN_R
        val r2 = reach * reach
        for (h in humans) {
            if (h.dead) continue
            for (z in zombies) {
                if (z.dead) continue
                val dx = z.x - h.x; val dy = z.y - h.y
                if (dx * dx + dy * dy < r2) {
                    h.dead = true
                    lost++
                    if (h.role != Role.STRAY) {
                        texts.add(FloatText(h.x, h.y - 12f, if (h.role == Role.PLAYER) "survivor down!" else "hunter down", 1.6f))
                    }
                    addZombie(h.x, h.y, inside = true)
                    break
                }
            }
        }
    }
}
