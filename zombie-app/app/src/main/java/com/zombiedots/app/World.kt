package com.zombiedots.app

import kotlin.math.abs
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
    var attackCd = 0f
}

enum class Role { PLAYER, HUNTER, STRAY }

class Human(var x: Float, var y: Float, var role: Role) {
    var vx = 0f
    var vy = 0f
    var cooldown = 0f
    var dead = false
    /** 0 = healthy, 1 = turns into a zombie. */
    var infection = 0f
    var slot = 0
    var anchorX = 0f
    var anchorY = 0f
    var hurtFlash = 0f
    val speed: Float
        get() = when (role) {
            Role.PLAYER -> 46f
            Role.HUNTER -> 45f
            Role.STRAY -> 42f
        } * (1f - infection * 0.25f) // sick dots slow down
}

/** What the squad is currently trying to do. */
enum class Tactic(val label: String) {
    SQUAD_UP("Grouping up"),
    HOLD("Standing their ground"),
    ADVANCE("Attacking!"),
    RUN("Running away!"),
    SPLIT("Spreading out")
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
    var nukeReady = true
    var turned = 0

    var tactic = Tactic.SQUAD_UP
        private set
    private var tacticTimer = 0f
    private var facing = 0f
    private var safeX = 0f
    private var safeY = 0f
    private var safeTimer = 0f

    companion object {
        const val MAX_ZOMBIES = 800
        const val WAVE_TIME = 22f
        const val MAX_HUNTERS = 8
        const val ZOMBIE_R = 3.2f
        const val HUMAN_R = 4.5f
        const val NUKE_BREATHER = 10f
        const val AIRSTRIKE_COOLDOWN = 20f
        const val BOMB_RADIUS = 24f
        const val BOMB_SPACING = 16f
        const val PLANE_SPEED = 300f
        const val MIN_STRIKE_LENGTH = 50f
    }

    val nextWaveIn: Float get() = WAVE_TIME - waveTimer

    // ------------------------------------------------------------------ airstrikes

    class Bomb(val x: Float, val y: Float, val along: Float, var dropped: Boolean = false)

    /** A plane flying a straight line, dropping bombs along the swiped stretch. */
    class Airstrike(
        val sx: Float, val sy: Float, val dx: Float, val dy: Float,
        val length: Float, val bombs: List<Bomb>
    ) {
        var travelled = 0f
        val planeX get() = sx + dx * travelled
        val planeY get() = sy + dy * travelled
        val done get() = travelled > length
    }

    class Blast(val x: Float, val y: Float, var age: Float)

    val airstrikes = ArrayList<Airstrike>()
    val blasts = ArrayList<Blast>()
    var airstrikeCooldown = 0f
    val airstrikeReady get() = airstrikeCooldown <= 0f

    /** Swipe from (x1,y1) to (x2,y2): carpet-bomb that line. Returns false if not ready. */
    fun airstrike(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (phase != Phase.RUNNING || !airstrikeReady) return false
        var dx = x2 - x1
        var dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < MIN_STRIKE_LENGTH) return false
        dx /= len; dy /= len
        // Plane comes in from off-screen behind the start point and leaves past the end
        val lead = max(width, height) * 0.6f + 40f
        val sx = x1 - dx * lead
        val sy = y1 - dy * lead
        val bombs = ArrayList<Bomb>()
        var t = 0f
        while (t <= len) {
            bombs.add(Bomb(x1 + dx * t, y1 + dy * t, lead + t))
            t += BOMB_SPACING
        }
        airstrikes.add(Airstrike(sx, sy, dx, dy, lead + len + lead, bombs))
        airstrikeCooldown = AIRSTRIKE_COOLDOWN
        return true
    }

    private fun updateAirstrikes(dt: Float) {
        if (airstrikeCooldown > 0f) airstrikeCooldown -= dt
        for (a in airstrikes) {
            a.travelled += PLANE_SPEED * dt
            for (b in a.bombs) {
                if (b.dropped || b.along > a.travelled) continue
                b.dropped = true
                blasts.add(Blast(b.x, b.y, 0f))
                val r2 = BOMB_RADIUS * BOMB_RADIUS
                var n = 0
                for (z in zombies) {
                    if (z.dead) continue
                    val ex = z.x - b.x; val ey = z.y - b.y
                    if (ex * ex + ey * ey < r2) { killZombie(z, quiet = true); n++ }
                }
            }
        }
        airstrikes.removeAll { it.done }
    }

    // ------------------------------------------------------------------ setup

    fun reset() {
        zombies.clear(); humans.clear(); tracers.clear(); splats.clear(); nukes.clear(); texts.clear()
        airstrikes.clear(); blasts.clear(); airstrikeCooldown = 0f
        phase = Phase.SETUP
        time = 0f; kills = 0; wave = 0; waveTimer = 0f
        strayTimer = 15f // first stray after ~15s
        nukeReady = true; turned = 0
        tactic = Tactic.SQUAD_UP; tacticTimer = 0f
    }

    /** Drops the survivor and one hunter at (x, y) and starts the outbreak. */
    fun start(x: Float, y: Float) {
        reset()
        val cx = x.coerceIn(40f, width - 40f)
        val cy = y.coerceIn(40f, height - 40f)
        humans.add(Human(cx, cy, Role.PLAYER))
        val a = rnd.nextFloat() * 6.283f
        humans.add(Human(cx + cos(a) * 20f, cy + sin(a) * 20f, Role.HUNTER))
        safeX = cx; safeY = cy
        phase = Phase.RUNNING
        spawnWave(first = true)
    }

    private fun spawnWave(first: Boolean = false) {
        wave++
        val count = if (first) 30 else 22 + wave * 11
        val edges = 1 + rnd.nextInt(if (wave > 3) 3 else 2)
        val chosen = (0 until 4).shuffled(rnd).take(edges)
        repeat(count) { i ->
            if (zombies.size >= MAX_ZOMBIES) return@repeat
            val (x, y) = edgePoint(chosen[i % chosen.size])
            addZombie(x, y)
        }
        if (!first) texts.add(FloatText(width / 2f, height / 2f, "WAVE $wave", 2.2f))
    }

    private fun edgePoint(edge: Int): Pair<Float, Float> {
        val m = 12f + rnd.nextFloat() * 40f
        return when (edge) {
            0 -> rnd.nextFloat() * width to -m
            1 -> width + m to rnd.nextFloat() * height
            2 -> rnd.nextFloat() * width to height + m
            else -> -m to rnd.nextFloat() * height
        }
    }

    private fun zombieBaseSpeed() = min(25f + wave * 2.4f, 52f)

    private fun addZombie(x: Float, y: Float, inside: Boolean = false) {
        if (zombies.size >= MAX_ZOMBIES) return
        val z = Zombie(x, y, zombieBaseSpeed() * (0.75f + rnd.nextFloat() * 0.6f))
        z.entered = inside
        z.wanderAngle = rnd.nextFloat() * 6.283f
        z.retarget = rnd.nextFloat() * 0.3f
        z.attackCd = 0.5f
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

    /** Double-tap: the one and only nuke. Wipes out every zombie on the map. */
    fun nuke(x: Float, y: Float): Boolean {
        if (phase != Phase.RUNNING || !nukeReady) return false
        nukeReady = false
        nukes.add(Nuke(x, y, 0f))
        var n = 0
        for (z in zombies) { if (!z.dead) { killZombie(z, quiet = true); n++ } }
        waveTimer = WAVE_TIME - NUKE_BREATHER // short breather, then the next wave
        texts.add(FloatText(width / 2f, height / 2f + 80f, "next wave in ${NUKE_BREATHER.toInt()}s", 2.5f))
        texts.add(FloatText(width / 2f, height / 2f + 40f, "+$n", 2f))
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
        for (b in blasts) b.age += dt
        blasts.removeAll { it.age > 0.7f }
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

        strayTimer += dt
        if (strayTimer >= 30f) { strayTimer = 0f; maybeSpawnStray() }

        updateAirstrikes(dt)
        buildGrid()
        updateZombies(dt)
        thinkSquad(dt)
        updateHumans(dt)
        attacks()

        zombies.removeAll { it.dead }
        humans.removeAll { it.dead }

        if (humans.isEmpty()) phase = Phase.OVER
    }

    private fun maybeSpawnStray() {
        val hunters = humans.count { it.role == Role.HUNTER }
        if (hunters >= MAX_HUNTERS) return
        var best: Pair<Float, Float>? = null
        var bestD = -1f
        repeat(12) {
            val x = 25f + rnd.nextFloat() * (width - 50f)
            val y = 25f + rnd.nextFloat() * (height - 50f)
            val d = nearestZombieDist2(x, y)
            if (d > bestD) { bestD = d; best = x to y }
        }
        val (x, y) = best ?: return
        humans.add(Human(x, y, Role.STRAY))
        texts.add(FloatText(x, y - 14f, "survivor spotted", 1.6f))
    }

    private fun nearestZombieDist2(x: Float, y: Float): Float {
        var d = Float.MAX_VALUE
        for (z in zombies) {
            if (z.dead) continue
            val dx = z.x - x; val dy = z.y - y
            val dd = dx * dx + dy * dy
            if (dd < d) d = dd
        }
        return d
    }

    // ------------------------------------------------------------------ spatial grid

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

    // ------------------------------------------------------------------ zombies

    private fun updateZombies(dt: Float) {
        val alive = humans.filter { !it.dead }
        for (i in zombies.indices) {
            val z = zombies[i]
            z.attackCd -= dt
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

    // ------------------------------------------------------------------ squad brain

    private fun thinkSquad(dt: Float) {
        val lead = leader ?: return
        val squad = humans.filter { it.role == Role.HUNTER && !it.dead && it !== lead }

        // Where is the danger coming from?
        var near = 0
        var mid = 0
        var tx = 0f
        var ty = 0f
        for (z in zombies) {
            if (z.dead) continue
            val dx = z.x - lead.x; val dy = z.y - lead.y
            val d2 = dx * dx + dy * dy
            if (d2 < 220f * 220f) {
                mid++
                val w = 1f / (sqrt(d2) + 20f)
                tx += dx * w; ty += dy * w
                if (d2 < 110f * 110f) near++
            }
        }
        if (mid > 0) {
            val target = atan2(ty, tx)
            var diff = target - facing
            while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
            while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
            facing += diff * min(1f, dt * 2f)
        }

        var spread = 0f
        for (h in squad) {
            val dx = h.x - lead.x; val dy = h.y - lead.y
            spread += sqrt(dx * dx + dy * dy)
        }
        if (squad.isNotEmpty()) spread /= squad.size

        val size = squad.size + 1
        tacticTimer -= dt
        val panic = near > 8 + 4 * size
        if (panic && tactic != Tactic.RUN) setTactic(Tactic.RUN, lead, squad)
        if (tacticTimer <= 0f) {
            val next = when {
                panic -> Tactic.RUN
                squad.isNotEmpty() && spread > 85f && tactic != Tactic.SPLIT -> Tactic.SQUAD_UP
                squad.size >= 2 && mid < 3 * size && rnd.nextFloat() < 0.35f -> Tactic.SPLIT
                mid in 1..(4 * size) -> Tactic.ADVANCE
                mid == 0 && squad.isNotEmpty() && spread > 40f -> Tactic.SQUAD_UP
                else -> Tactic.HOLD
            }
            setTactic(next, lead, squad)
        }

        if (tactic == Tactic.RUN) {
            safeTimer -= dt
            if (safeTimer <= 0f) pickSafeSpot(lead)
        }
    }

    private fun setTactic(t: Tactic, lead: Human, squad: List<Human>) {
        tactic = t
        tacticTimer = when (t) {
            Tactic.RUN -> 2.5f
            Tactic.SPLIT -> 5f + rnd.nextFloat() * 3f
            else -> 2.5f + rnd.nextFloat() * 2f
        }
        squad.forEachIndexed { i, h -> h.slot = i }
        if (t == Tactic.RUN) pickSafeSpot(lead)
        if (t == Tactic.SPLIT && squad.isNotEmpty()) {
            val base = rnd.nextFloat() * 6.283f
            squad.forEachIndexed { i, h ->
                val a = base + 6.283f * i / squad.size
                h.anchorX = (lead.x + cos(a) * 95f).coerceIn(50f, max(50f, width - 50f))
                h.anchorY = (lead.y + sin(a) * 95f).coerceIn(50f, max(50f, height - 50f))
            }
        }
    }

    /** Looks around for the open spot farthest from zombies and walls. */
    private fun pickSafeSpot(lead: Human) {
        safeTimer = 1.2f
        var bestScore = -Float.MAX_VALUE
        val maxX = max(45f, width - 45f)
        val maxY = max(45f, height - 45f)
        for (i in 0 until 18) {
            val a = 6.283f * i / 18f
            for (r in floatArrayOf(90f, 170f)) {
                val x = (lead.x + cos(a) * r).coerceIn(45f, maxX)
                val y = (lead.y + sin(a) * r).coerceIn(45f, maxY)
                var score = sqrt(nearestZombieDist2(x, y))
                // Don't run *through* the horde
                val mx = (x + lead.x) / 2f; val my = (y + lead.y) / 2f
                score += sqrt(nearestZombieDist2(mx, my)) * 0.8f
                val wall = min(min(x, width - x), min(y, height - y))
                score += min(wall, 120f) * 0.4f
                if (score > bestScore) { bestScore = score; safeX = x; safeY = y }
            }
        }
    }

    // ------------------------------------------------------------------ humans

    private fun updateHumans(dt: Float) {
        val lead = leader
        val fx0 = cos(facing)
        val fy0 = sin(facing)
        val squadSize = humans.count { it.role == Role.HUNTER && !it.dead && it !== lead }

        for (h in humans) {
            if (h.dead) continue
            if (h.infection > 0f) h.infection += dt * 0.006f // the infection keeps spreading
            h.hurtFlash = max(0f, h.hurtFlash - dt * 3f)

            // Emergency: dodge zombies that are right on top of us
            var ex = 0f
            var ey = 0f
            val dodgeR = if (h.role == Role.STRAY) 90f else 38f
            var nearest2 = Float.MAX_VALUE
            for (z in zombies) {
                val dx = h.x - z.x; val dy = h.y - z.y
                if (abs(dx) > dodgeR * 2f || abs(dy) > dodgeR * 2f) continue
                val d2 = dx * dx + dy * dy
                if (d2 < nearest2) nearest2 = d2
                if (d2 >= dodgeR * dodgeR || d2 < 0.0001f) continue
                val d = sqrt(d2)
                val w = 1f - d / dodgeR
                ex += dx / d * w * w
                ey += dy / d * w * w
            }

            // Walls and corners are deadly
            val m = 55f
            var wx = 0f
            var wy = 0f
            if (h.x < m) wx += (m - h.x) / m
            if (h.x > width - m) wx -= (h.x - (width - m)) / m
            if (h.y < m) wy += (m - h.y) / m
            if (h.y > height - m) wy -= (h.y - (height - m)) / m

            // Where this dot wants to be right now
            var goalX = h.x
            var goalY = h.y
            var urgency = 0.5f
            if (h.role == Role.STRAY) {
                if (lead != null) {
                    goalX = lead.x; goalY = lead.y; urgency = 1f
                    val dx = lead.x - h.x; val dy = lead.y - h.y
                    if (dx * dx + dy * dy < 26f * 26f) {
                        h.role = Role.HUNTER
                        h.slot = squadSize
                        texts.add(FloatText(h.x, h.y - 14f, "Survivor Joined!", 2.0f))
                    }
                }
            } else if (h === lead) {
                when (tactic) {
                    Tactic.RUN -> { goalX = safeX; goalY = safeY; urgency = 1f }
                    Tactic.ADVANCE -> {
                        if (nearest2 > 75f * 75f) { goalX = h.x + fx0 * 30f; goalY = h.y + fy0 * 30f; urgency = 0.45f }
                        else { goalX = h.x - fx0 * 30f; goalY = h.y - fy0 * 30f; urgency = 0.6f }
                    }
                    Tactic.HOLD -> if (nearest2 < 55f * 55f) { goalX = h.x - fx0 * 30f; goalY = h.y - fy0 * 30f; urgency = 0.7f }
                    Tactic.SQUAD_UP -> { goalX = h.x - fx0 * 10f; goalY = h.y - fy0 * 10f; urgency = 0.3f }
                    Tactic.SPLIT -> { goalX = width / 2f; goalY = height / 2f; urgency = 0.25f }
                }
            } else if (lead != null) {
                val n = max(1, squadSize)
                // Spread across an arc facing the danger (fixed slots, no spinning)
                val spreadAngle = if (n == 1) 0f else (-1f + 2f * h.slot.coerceAtMost(n - 1) / (n - 1)) * 1.1f
                val a = facing + spreadAngle
                when (tactic) {
                    Tactic.SQUAD_UP -> {
                        val ra = facing + 6.283f * h.slot / n
                        goalX = lead.x + cos(ra) * 16f; goalY = lead.y + sin(ra) * 16f; urgency = 0.9f
                    }
                    Tactic.HOLD -> { goalX = lead.x + cos(a) * 26f; goalY = lead.y + sin(a) * 26f; urgency = 0.7f }
                    Tactic.ADVANCE -> { goalX = lead.x + cos(a) * 55f; goalY = lead.y + sin(a) * 55f; urgency = 0.8f }
                    Tactic.RUN -> {
                        val side = if (h.slot % 2 == 0) 1f else -1f
                        val lat = (h.slot / 2 + 1) * 10f * side
                        goalX = lead.x - fx0 * 14f - fy0 * lat
                        goalY = lead.y - fy0 * 14f + fx0 * lat
                        urgency = 1f
                    }
                    Tactic.SPLIT -> { goalX = h.anchorX; goalY = h.anchorY; urgency = 0.75f }
                }
            }

            var gx = goalX - h.x
            var gy = goalY - h.y
            val gl = sqrt(gx * gx + gy * gy)
            if (gl > 0.01f) {
                val arrive = min(1f, gl / 25f)
                gx = gx / gl * arrive; gy = gy / gl * arrive
            }
            val dodgeW = if (h.role == Role.STRAY) 2.2f else 3f
            val ax = gx * urgency + ex * dodgeW + wx * 2.2f
            val ay = gy * urgency + ey * dodgeW + wy * 2.2f
            val al = sqrt(ax * ax + ay * ay)
            val spd = h.speed * min(1f, al)
            val wantX = if (al > 0.001f) ax / al * spd else 0f
            val wantY = if (al > 0.001f) ay / al * spd else 0f
            val k = min(1f, dt * 7f)
            h.vx += (wantX - h.vx) * k
            h.vy += (wantY - h.vy) * k
            h.x = (h.x + h.vx * dt).coerceIn(3f, max(3f, width - 3f))
            h.y = (h.y + h.vy * dt).coerceIn(3f, max(3f, height - 3f))

            shoot(h, dt)
        }
    }

    private fun shoot(h: Human, dt: Float) {
        if (h.role == Role.STRAY) return
        h.cooldown -= dt
        if (h.cooldown > 0f) return
        val range = if (h.role == Role.HUNTER) 115f else 80f
        var best: Zombie? = null
        var bd = range * range
        for (z in zombies) {
            if (z.dead || !z.entered) continue
            val dx = z.x - h.x; val dy = z.y - h.y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = z }
        }
        val z = best ?: return
        val running = tactic == Tactic.RUN
        h.cooldown = (if (h.role == Role.HUNTER) 0.5f + rnd.nextFloat() * 0.3f else 1f + rnd.nextFloat() * 0.4f) *
            (if (running) 1.6f else 1f)
        val hitChance = (if (h.role == Role.HUNTER) 0.75f else 0.55f) * (if (running) 0.6f else 1f)
        if (rnd.nextFloat() < hitChance) {
            tracers.add(Tracer(h.x, h.y, z.x, z.y, 0.12f))
            killZombie(z)
        } else {
            val miss = (rnd.nextFloat() - 0.5f) * 20f
            tracers.add(Tracer(h.x, h.y, z.x + miss, z.y - miss, 0.12f))
        }
    }

    /** Zombies bite; every bite adds infection. Fully infected dots turn. */
    private fun attacks() {
        val reach = ZOMBIE_R + HUMAN_R + 1f
        val r2 = reach * reach
        for (h in humans) {
            if (h.dead) continue
            for (z in zombies) {
                if (z.dead || z.attackCd > 0f) continue
                val dx = h.x - z.x; val dy = h.y - z.y
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                z.attackCd = 1.1f
                h.infection += when (h.role) {
                    Role.PLAYER -> 0.12f + rnd.nextFloat() * 0.06f
                    Role.HUNTER -> 0.14f + rnd.nextFloat() * 0.08f
                    Role.STRAY -> 0.45f
                }
                h.hurtFlash = 1f
                val d = sqrt(d2).coerceAtLeast(0.5f)
                h.vx += dx / d * 70f
                h.vy += dy / d * 70f
                val fight = when (h.role) { Role.HUNTER -> 0.35f; Role.PLAYER -> 0.2f; Role.STRAY -> 0.05f }
                if (rnd.nextFloat() < fight) killZombie(z)
            }
            if (h.infection >= 1f) {
                h.dead = true
                turned++
                texts.add(FloatText(h.x, h.y - 12f, when (h.role) {
                    Role.PLAYER -> "survivor turned!"
                    Role.HUNTER -> "hunter turned!"
                    Role.STRAY -> "turned"
                }, 1.8f))
                // Added after the loop finishes to avoid changing the list mid-scan
                pendingTurns.add(h.x to h.y)
            }
        }
        for ((x, y) in pendingTurns) addZombie(x, y, inside = true)
        pendingTurns.clear()
    }

    private val pendingTurns = ArrayList<Pair<Float, Float>>()
}
