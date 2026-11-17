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

enum class ZType { NORMAL, RUNNER, FAT, ARMORED, BEHEMOTH }

class Zombie(var x: Float, var y: Float, val speed: Float, val type: ZType = ZType.NORMAL, var hp: Float = 1f) {
    var armor = 0f
    var armorMax = 0f
    var maxHp = hp
    val runner get() = type == ZType.RUNNER
    val radius get() = when (type) {
        ZType.BEHEMOTH -> 16f
        ZType.FAT -> 6f
        ZType.ARMORED -> 3.8f
        else -> 3.2f
    }
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
    SPLIT("Spreading out"),
    HUNT("Hunting zombies"),
    PATROL("Patrolling")
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
        const val NUKE_BREATHER = 8f
        const val ROUND_BREAK = 8f
        const val HEALTH_COOLDOWN = 30f
        const val STRAY_TIME = 18f
        const val CURE_COOLDOWN = 15f
        const val MAX_HEALTH_LEVEL = Int.MAX_VALUE
        const val ROUND_MAX = 90f
        const val AIRSTRIKE_COOLDOWN = 20f
        const val BOMB_RADIUS = 24f
        const val BOMB_SPACING = 16f
        const val PLANE_SPEED = 300f
        const val MIN_STRIKE_LENGTH = 50f
    }

    /** Seconds until the next round during a break, or 0 while a round is going. */
    val nextWaveIn: Float get() = intermission

    private fun updateRounds(dt: Float) {
        waveAge += dt
        if (spawnQueue > 0) {
            spawnTimer -= dt
            val rate = min(2.5f + wave * 0.35f, 7f) // zombies per second
            while (spawnTimer <= 0f && spawnQueue > 0) {
                val (x, y) = edgePoint(waveEdges[spawnIndex++ % waveEdges.size])
                if (spawnQueue <= behemothsPending) {
                    // The boss arrives at the end of the round
                    addBehemoth(x, y, armored = wave >= 10)
                    behemothsPending--
                } else addZombie(x, y)
                spawnQueue--
                spawnTimer += 1f / rate
            }
        } else if (zombies.none { it.type != ZType.BEHEMOTH }) {
            // Behemoths don't hold up the round: they keep roaming into the next one
            if (intermission <= 0f) {
                intermission = ROUND_BREAK
                texts.add(FloatText(width / 2f, height / 2f, "ROUND $wave CLEARED!", 2.4f))
            }
        }
        if (intermission > 0f) {
            intermission -= dt
            if (intermission <= 0f) spawnWave()
        } else if (waveAge > ROUND_MAX && spawnQueue == 0) {
            spawnWave() // stragglers can't stall the game forever
        }
    }

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
                    val reach = BOMB_RADIUS + z.radius
                    if (ex * ex + ey * ey < reach * reach) {
                        if (z.type == ZType.BEHEMOTH) hurt(z, 12f) else { killZombie(z, quiet = true); n++ }
                    }
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
        strayTimer = STRAY_TIME - 10f // first stray after ~10s
        nukeReady = true; turned = 0
        waveSize = 0; waveGrowth = 0; speedBase = 25f
        spawnQueue = 0; intermission = 0f; waveAge = 0f; spawnIndex = 0
        healthLevel = 0; healthCooldown = 0f; cureCooldown = 0f; behemothsPending = 0
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

    /** Zombies in the current round and how many more than last round. */
    var waveSize = 0
    var waveGrowth = 0
    private var speedBase = 25f

    /**
     * Round scaling: steady growth for rounds 1-5 (like Black Ops), then diminishing returns
     * (like Souls levelling). Each later round usually adds a bit less than the one before;
     * some add just one, and now and then one gives an extra jump.
     */
    private fun growRound() {
        if (wave == 1) {
            waveSize = 25; waveGrowth = 0; speedBase = 27f
            return
        }
        var inc: Float
        var spInc: Float
        if (wave <= 5) {
            inc = 6f
            spInc = 2.6f
        } else {
            val fade = Math.pow(0.88, (wave - 5).toDouble()).toFloat()
            inc = 6f * fade
            spInc = 2.6f * fade
            val roll = rnd.nextFloat()
            when {
                roll < 0.18f -> { inc = 1f; spInc *= 0.2f }       // a "dud" round: just one more
                roll < 0.33f -> { inc *= 1.7f; spInc *= 1.5f }    // a bonus jump
                else -> inc *= 0.8f + rnd.nextFloat() * 0.4f
            }
        }
        waveGrowth = max(1, Math.round(inc))
        waveSize += waveGrowth
        speedBase = min(speedBase + spInc, 52f)
    }

    // Black Ops-style rounds: zombies trickle in; the next round starts once this one is cleared
    var spawnQueue = 0
    private var behemothsPending = 0
    private var spawnTimer = 0f
    private var waveEdges = listOf(0)
    private var spawnIndex = 0
    var intermission = 0f
    private var waveAge = 0f

    /** Zombies still to come this round, including those on the map. */
    val zombiesLeft get() = zombies.count { it.type != ZType.BEHEMOTH } + spawnQueue - behemothsPending

    private fun spawnWave(first: Boolean = false) {
        wave++
        growRound()
        val edges = 1 + rnd.nextInt(if (wave > 3) 3 else 2)
        waveEdges = (0 until 4).shuffled(rnd).take(edges)
        val alive = zombies.count { it.type == ZType.BEHEMOTH }
        behemothsPending = (when {
            wave >= 13 -> 2
            wave >= 6 -> 1
            else -> 0
        }).coerceAtMost(max(0, 2 - alive)) // never more than 2 roaming at once
        spawnQueue = waveSize + behemothsPending
        spawnTimer = 0f
        waveAge = 0f
        intermission = 0f
        if (!first) {
            texts.add(FloatText(width / 2f, height / 2f, "WAVE $wave", 2.2f))
            texts.add(FloatText(width / 2f, height / 2f + 26f, "+$waveGrowth zombies", 2.2f))
        }
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

    private fun zombieBaseSpeed() = speedBase

    /**
     * Zombie toughness: +30% per round up to round 9, then it keeps rising with diminishing
     * returns and levels off, so a well-played squad can keep going indefinitely.
     */
    fun zombieHp(): Float =
        if (wave <= 9) 1f + 0.3f * (wave - 1)
        else 3.4f + 2.6f * (1f - Math.pow(0.9, (wave - 9).toDouble()).toFloat())

    /** Deals damage; returns true if the zombie went down. */
    private fun hurt(z: Zombie, dmg: Float): Boolean {
        if (z.dead) return false
        if (z.armor > 0f) {
            // Armor soaks bullets (at 60%) until it breaks; leftover damage goes through
            z.armor -= dmg * 0.6f
            if (z.armor > 0f) return false
            val overflow = -z.armor / 0.6f
            z.armor = 0f
            texts.add(FloatText(z.x, z.y - 8f, "armor broke", 0.9f))
            z.hp -= overflow
            if (z.hp <= 0f) { killZombie(z); return true }
            return false
        }
        z.hp -= dmg
        if (z.hp <= 0f) { killZombie(z); return true }
        return false
    }

    private fun addZombie(x: Float, y: Float, inside: Boolean = false) {
        if (zombies.size >= MAX_ZOMBIES) return
        // A mix: slow shamblers, normal walkers, fast runners, and (later) fat and armored zombies
        val fatChance = if (wave >= 3) min(0.06f + wave * 0.01f, 0.14f) else 0f
        val armorChance = if (wave >= 4) min(0.06f + wave * 0.012f, 0.18f) else 0f
        val roll = rnd.nextFloat()
        val base = zombieHp()
        val z = when {
            roll < fatChance -> Zombie(x, y, zombieBaseSpeed() * (0.5f + rnd.nextFloat() * 0.15f), ZType.FAT, base * 3.2f).also { it.maxHp = it.hp }
            roll < fatChance + armorChance ->
                Zombie(x, y, zombieBaseSpeed() * (0.75f + rnd.nextFloat() * 0.2f), ZType.ARMORED, base).also {
                    it.armorMax = base * 2.2f; it.armor = it.armorMax
                }
            else -> {
                val r2 = rnd.nextFloat()
                when {
                    r2 < 0.25f -> Zombie(x, y, zombieBaseSpeed() * (0.45f + rnd.nextFloat() * 0.25f), ZType.NORMAL, base)
                    r2 < 0.87f -> Zombie(x, y, zombieBaseSpeed() * (0.8f + rnd.nextFloat() * 0.35f), ZType.NORMAL, base)
                    else -> Zombie(x, y, zombieBaseSpeed() * (1.4f + rnd.nextFloat() * 0.4f), ZType.RUNNER, base * 0.7f)
                }
            }
        }
        z.entered = inside
        z.wanderAngle = rnd.nextFloat() * 6.283f
        z.retarget = rnd.nextFloat() * 0.3f
        z.attackCd = 0.5f
        zombies.add(z)
    }

    /** Huge, painfully slow, with an enormous amount of health (and armor from round 10). */
    private fun addBehemoth(x0: Float, y0: Float, armored: Boolean) {
        // Start a bit further out so it lumbers in from off-screen
        val x = if (x0 < 0f) x0 - 20f else if (x0 > width) x0 + 20f else x0
        val y = if (y0 < 0f) y0 - 20f else if (y0 > height) y0 + 20f else y0
        val base = zombieHp()
        val z = Zombie(x, y, max(5f, zombieBaseSpeed() * 0.17f), ZType.BEHEMOTH, base * 80f)
        z.maxHp = z.hp
        if (armored) { z.armorMax = base * 50f; z.armor = z.armorMax }
        z.retarget = 0f
        z.attackCd = 1f
        zombies.add(z)
        texts.add(FloatText(width / 2f, height / 2f - 30f, if (armored) "⚠ ARMORED BEHEMOTH!" else "⚠ BEHEMOTH!", 2.6f))
        behemothArrived++
    }

    // ------------------------------------------------------------------ support abilities

    var healthLevel = 0
    var healthCooldown = 0f
    var cureCooldown = 0f
    val healthReady get() = healthCooldown <= 0f
    val cureReady get() = cureCooldown <= 0f
    /** Each health level makes bites infect less (diminishing, never fully immune). */
    val biteResist get() = 0.3f + 0.7f * Math.pow(0.9, healthLevel.toDouble()).toFloat()
    /** And makes hunters hit harder, up to +50%. */
    val damageBonus get() = 1f + 0.5f * (1f - Math.pow(0.9, healthLevel.toDouble()).toFloat())
    /** Each health level also makes survivors faster, up to about +45%. */
    val speedBonus get() = 1f + 0.45f * (1f - Math.pow(0.88, healthLevel.toDouble()).toFloat())

    fun upgradeHealth(): Boolean {
        if (phase != Phase.RUNNING || !healthReady) return false
        healthLevel++
        healthCooldown = HEALTH_COOLDOWN
        for (h in humans) texts.add(FloatText(h.x, h.y - 12f, "+HP", 1.2f))
        return true
    }

    /** The survivor closest to turning, if anyone is infected. */
    fun mostInfected(): Human? = humans.filter { !it.dead && it.infection > 0.01f }.maxByOrNull { it.infection }

    /** Cures one survivor completely. */
    fun cureOne(h: Human): Boolean {
        if (phase != Phase.RUNNING || !cureReady || h.dead) return false
        cureCooldown = CURE_COOLDOWN
        h.infection = 0f
        h.hurtFlash = 0f
        texts.add(FloatText(h.x, h.y - 12f, "cured!", 1.4f))
        return true
    }

    /** Counts behemoth arrivals (for announcements). */
    var behemothArrived = 0

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
        spawnQueue = 0 // the rest of this round never arrives
        texts.add(FloatText(width / 2f, height / 2f + 80f, "next wave in ${ROUND_BREAK.toInt()}s", 2.5f))
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

        updateRounds(dt)
        if (healthCooldown > 0f) healthCooldown -= dt
        if (cureCooldown > 0f) cureCooldown -= dt

        strayTimer += dt
        if (strayTimer >= STRAY_TIME) { strayTimer = 0f; maybeSpawnStray() }

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
                        val gap = z.radius + o.radius + 0.6f
                        if (d2 < gap * gap && d2 > 0.0001f) {
                            val d = sqrt(d2)
                            sx += ex / d * (gap - d) / gap
                            sy += ey / d * (gap - d) / gap
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
        // Nearest zombie anywhere on the map: the squad goes looking for it when nothing is close
        huntTarget = null
        var hd = Float.MAX_VALUE
        for (z in zombies) {
            if (z.dead || !z.entered) continue
            val dx = z.x - lead.x; val dy = z.y - lead.y
            val dd = dx * dx + dy * dy
            if (dd < hd) { hd = dd; huntTarget = z }
        }
        // A behemoth anywhere close counts as danger the squad must deal with
        val bigOne = zombies.firstOrNull { it.type == ZType.BEHEMOTH && !it.dead &&
            (it.x - lead.x) * (it.x - lead.x) + (it.y - lead.y) * (it.y - lead.y) < 300f * 300f }
        if (mid == 0) {
            val ht = bigOne ?: huntTarget
            if (ht != null) { tx = ht.x - lead.x; ty = ht.y - lead.y }
            else if (tactic == Tactic.PATROL) { tx = patrolX - lead.x; ty = patrolY - lead.y }
        }
        if (mid > 0 || tx != 0f || ty != 0f) {
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
                mid == 0 && (huntTarget != null || bigOne != null) -> Tactic.HUNT
                mid == 0 -> Tactic.PATROL
                squad.size >= 2 && mid < 3 * size && rnd.nextFloat() < 0.3f -> Tactic.SPLIT
                mid in 1..(4 * size) -> Tactic.ADVANCE
                else -> Tactic.HOLD
            }
            setTactic(next, lead, squad)
        }

        if (tactic == Tactic.RUN) {
            safeTimer -= dt
            if (safeTimer <= 0f) pickSafeSpot(lead)
        }
        if (tactic == Tactic.PATROL) {
            patrolTimer -= dt
            val pdx = patrolX - lead.x; val pdy = patrolY - lead.y
            if (patrolTimer <= 0f || pdx * pdx + pdy * pdy < 20f * 20f) pickPatrolPoint()
            // Zombies showed up: stop patrolling and go get them
            if (huntTarget != null) tacticTimer = 0f
        }
        if (tactic == Tactic.HUNT && huntTarget == null && bigOne == null) tacticTimer = 0f
        huntFocus = bigOne ?: huntTarget
    }

    private var huntTarget: Zombie? = null
    /** What the squad is moving toward while hunting. */
    private var huntFocus: Zombie? = null
    private var patrolX = 0f
    private var patrolY = 0f
    private var patrolTimer = 0f

    private fun pickPatrolPoint() {
        patrolTimer = 6f + rnd.nextFloat() * 4f
        patrolX = width * (0.2f + rnd.nextFloat() * 0.6f)
        patrolY = height * (0.2f + rnd.nextFloat() * 0.6f)
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
        if (t == Tactic.PATROL) pickPatrolPoint()
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
                val extra = z.radius - ZOMBIE_R // big zombies count as closer
                val lim = (dodgeR + extra) * 2f
                if (abs(dx) > lim || abs(dy) > lim) continue
                val raw2 = dx * dx + dy * dy
                if (raw2 < 0.0001f) continue
                val raw = sqrt(raw2)
                val d = max(0.5f, raw - extra)
                val d2 = d * d
                if (d2 < nearest2) nearest2 = d2
                if (d >= dodgeR) continue
                val w = 1f - d / dodgeR
                ex += dx / raw * w * w
                ey += dy / raw * w * w
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
                    Tactic.HUNT -> {
                        val f = huntFocus
                        if (f != null) {
                            // Close in to shooting range, but stay out of reach (behemoths: keep well back)
                            val keep = if (f.type == ZType.BEHEMOTH) 85f else 70f
                            val dx = f.x - h.x; val dy = f.y - h.y
                            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                            goalX = f.x - dx / dist * keep
                            goalY = f.y - dy / dist * keep
                            urgency = if (dist > keep + 20f) 0.8f else 0.6f
                        }
                    }
                    Tactic.PATROL -> { goalX = patrolX; goalY = patrolY; urgency = 0.45f }
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
                    Tactic.HUNT -> { goalX = lead.x + cos(a) * 30f; goalY = lead.y + sin(a) * 30f; urgency = 0.85f }
                    Tactic.PATROL -> {
                        val ra = facing + 6.283f * h.slot / n
                        goalX = lead.x + cos(ra) * 24f; goalY = lead.y + sin(ra) * 24f; urgency = 0.8f
                    }
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
            val spd = h.speed * speedBonus * min(1f, al)
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
        h.cooldown = (if (h.role == Role.HUNTER) 0.32f + rnd.nextFloat() * 0.2f else 0.8f + rnd.nextFloat() * 0.3f) *
            (if (running) 1.6f else 1f)
        val hitChance = (if (h.role == Role.HUNTER) 0.88f else 0.65f) * (if (running) 0.65f else 1f)
        if (rnd.nextFloat() < hitChance) {
            // Hunters hit hard, with the odd critical hit
            val dmg = (if (h.role == Role.HUNTER) 1f else 0.7f) * (if (rnd.nextFloat() < 0.15f) 2f else 1f) * damageBonus
            val killed = hurt(z, dmg)
            var endX = z.x
            var endY = z.y
            // Hunters' shots can punch through into the zombie behind
            if (killed && h.role == Role.HUNTER && rnd.nextFloat() < 0.4f) {
                val dx = z.x - h.x; val dy = z.y - h.y
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                val ux = dx / len; val uy = dy / len
                var second: Zombie? = null
                var bestAlong = 40f
                for (o in zombies) {
                    if (o.dead) continue
                    val ox = o.x - z.x; val oy = o.y - z.y
                    val along = ox * ux + oy * uy
                    if (along <= 0f || along >= bestAlong) continue
                    val side = abs(ox * uy - oy * ux)
                    if (side < 6f) { bestAlong = along; second = o }
                }
                second?.let { hurt(it, dmg * 0.7f); endX = it.x; endY = it.y }
            }
            tracers.add(Tracer(h.x, h.y, endX, endY, 0.12f))
        } else {
            val miss = (rnd.nextFloat() - 0.5f) * 20f
            tracers.add(Tracer(h.x, h.y, z.x + miss, z.y - miss, 0.12f))
        }
    }

    /** Zombies bite; every bite adds infection. Fully infected dots turn. */
    private fun attacks() {
        for (h in humans) {
            if (h.dead) continue
            for (z in zombies) {
                if (z.dead || z.attackCd > 0f) continue
                val dx = h.x - z.x; val dy = h.y - z.y
                val d2 = dx * dx + dy * dy
                val reach = z.radius + HUMAN_R + 1f
                if (d2 >= reach * reach) continue
                z.attackCd = if (z.type == ZType.BEHEMOTH) 1.6f else 1.1f
                val bite = when (z.type) { ZType.BEHEMOTH -> 3f; ZType.FAT -> 1.5f; else -> 1f }
                h.infection += bite * biteResist * when (h.role) {
                    Role.PLAYER -> 0.12f + rnd.nextFloat() * 0.06f
                    Role.HUNTER -> 0.14f + rnd.nextFloat() * 0.08f
                    Role.STRAY -> 0.45f
                }
                h.hurtFlash = 1f
                val d = sqrt(d2).coerceAtLeast(0.5f)
                val knock = if (z.type == ZType.BEHEMOTH) 160f else 70f
                h.vx += dx / d * knock
                h.vy += dy / d * knock
                val fight = when (h.role) { Role.HUNTER -> 0.35f; Role.PLAYER -> 0.2f; Role.STRAY -> 0.05f }
                if (z.type != ZType.BEHEMOTH && rnd.nextFloat() < fight) hurt(z, 1f)
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
