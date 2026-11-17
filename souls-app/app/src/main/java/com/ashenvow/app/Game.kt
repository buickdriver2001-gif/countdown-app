package com.ashenvow.app

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Touch input, written by the UI thread and read by the game thread. */
class Input {
    @Volatile var moveX = 0f   // -1..1, right
    @Volatile var moveY = 0f   // -1..1, forward (up on the stick)
    val camDrag = java.util.concurrent.atomic.AtomicReference(0f)
    val attack = AtomicInteger()
    val heavy = AtomicInteger()
    val roll = AtomicInteger()
    val heal = AtomicInteger()
    val lock = AtomicInteger()
    /** Tap on the title / death / victory screen. */
    val start = AtomicInteger()

    fun addDrag(d: Float) { while (true) { val v = camDrag.get(); if (camDrag.compareAndSet(v, v + d)) return } }
    fun takeDrag(): Float = camDrag.getAndSet(0f)
}

enum class PState { IDLE, ATTACK, HEAVY, ROLL, HEAL, STAGGER, DEAD }
enum class BState { INTRO, IDLE, SLAM, SWEEP, LUNGE, LEAP, COMBO, STAGGER, PHASE, DEAD }
enum class Screen { TITLE, FIGHT, DIED, WON }

class Spark(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val ember: Boolean)

/** An expanding ring of force from the boss's leap: roll through it! */
class Shockwave(val x: Float, val z: Float) { var r = 0.5f; var hitDone = false }

fun wrapAngle(a: Float): Float {
    var v = a
    while (v > PI) v -= (2 * PI).toFloat()
    while (v < -PI) v += (2 * PI).toFloat()
    return v
}

class Player {
    var x = 0f; var z = -9f
    var yaw = 0f
    var vx = 0f; var vz = 0f
    var hp = MAX_HP; var stamina = MAX_STAMINA
    var staminaDelay = 0f
    var flasks = MAX_FLASKS
    var state = PState.IDLE
    var t = 0f            // time in current state
    var combo = 0         // 0,1,2 for the light chain
    var hitDone = false
    var healed = false
    var rollX = 0f; var rollZ = 1f
    var walkPhase = 0f
    var speedNow = 0f
    var hurtFlash = 0f

    companion object {
        const val MAX_HP = 120f
        const val MAX_STAMINA = 100f
        const val MAX_FLASKS = 4
        const val RADIUS = 0.45f
    }
}

class Boss {
    var x = 0f; var z = 6f
    var yaw = PI.toFloat()
    var hp = MAX_HP
    var poise = MAX_POISE
    var state = BState.INTRO
    var t = 0f
    var cooldown = 1.2f
    var phase2 = false
    var hitDone = false
    var lungeDX = 0f; var lungeDZ = 0f
    var leapFromX = 0f; var leapFromZ = 0f; var leapToX = 0f; var leapToZ = 0f
    var y = 0f
    var walkPhase = 0f
    var moving = 0f
    var hurtFlash = 0f
    var comboStep = 0

    companion object {
        const val MAX_HP = 900f
        const val MAX_POISE = 100f
        const val RADIUS = 1.35f
        const val NAME = "The Hollow Warden"
    }
}

/** The whole fight: one knight, one boss, a ruined arena. No Android code here, so it can be tested anywhere. */
class Game(seed: Int = 1) {
    val rnd = Random(seed)
    val input = Input()
    var player = Player()
    var boss = Boss()
    var screen = Screen.TITLE
    var screenT = 0f
    var time = 0f
    val sparks = ArrayList<Spark>()
    val waves = ArrayList<Shockwave>()

    // Camera (yaw around the player; forward = (sin yaw, cos yaw))
    var camYaw = 0f
    var locked = false
    var camX = 0f; var camY = 3f; var camZ = -15f
    var lookX = 0f; var lookY = 1f; var lookZ = 0f
    var shake = 0f
    @Volatile var paused = false

    // Events for the UI (vibration, messages), counted up
    val evPlayerHit = AtomicInteger()
    val evBossHit = AtomicInteger()
    val evPhase = AtomicInteger()
    val evDied = AtomicInteger()
    val evWon = AtomicInteger()
    val evBlocked = AtomicInteger()

    companion object {
        const val ARENA_R = 17f
        /** Pillars around the arena: x, z, radius. */
        val PILLARS = run {
            val list = ArrayList<FloatArray>()
            for (i in 0 until 8) {
                val a = (i / 8.0 * 2 * PI + PI / 8).toFloat()
                list.add(floatArrayOf(sin(a) * 12.5f, cos(a) * 12.5f, 0.85f))
            }
            list
        }
    }

    fun newFight() {
        player = Player()
        boss = Boss()
        sparks.clear(); waves.clear()
        camYaw = 0f
        locked = true
        screen = Screen.FIGHT
        screenT = 0f
        input.attack.set(0); input.heavy.set(0); input.roll.set(0); input.heal.set(0); input.lock.set(0); input.takeDrag()
    }

    // ------------------------------------------------------------------ main step

    fun update(dtIn: Float) {
        val dt = min(dtIn, 1f / 20f)
        if (input.start.getAndSet(0) > 0 && screen != Screen.FIGHT) { newFight(); return }
        if (paused && screen == Screen.FIGHT) return
        time += dt
        screenT += dt
        if (screen == Screen.TITLE) {
            // Slow orbit around the arena behind the title
            camYaw += dt * 0.15f
            updateCamera(dt, title = true)
            updateSparks(dt)
            return
        }
        updatePlayer(dt)
        updateBoss(dt)
        collide()
        updateWaves(dt)
        updateSparks(dt)
        updateCamera(dt, title = false)
        if (screen == Screen.FIGHT) {
            if (player.state == PState.DEAD && player.t > 1.6f) { screen = Screen.DIED; screenT = 0f }
            if (boss.state == BState.DEAD && boss.t > 2.2f) { screen = Screen.WON; screenT = 0f }
        }
    }

    // ------------------------------------------------------------------ player

    private var bufAttack = 0f
    private var bufHeavy = 0f
    private var bufRoll = 0f
    private var bufHeal = 0f

    private fun camForward() = sin(camYaw) to cos(camYaw)

    private fun useStamina(p: Player, amount: Float): Boolean {
        if (p.stamina <= 0f) return false
        p.stamina = max(-20f, p.stamina - amount)
        p.staminaDelay = 0.6f
        return true
    }

    private fun updatePlayer(dt: Float) {
        val p = player
        p.t += dt
        p.hurtFlash = max(0f, p.hurtFlash - dt * 3f)

        // Read buttons into short buffers (so a press slightly early still counts)
        if (input.attack.getAndSet(0) > 0) bufAttack = 0.35f
        if (input.heavy.getAndSet(0) > 0) bufHeavy = 0.35f
        if (input.roll.getAndSet(0) > 0) bufRoll = 0.3f
        if (input.heal.getAndSet(0) > 0) bufHeal = 0.3f
        if (input.lock.getAndSet(0) > 0) locked = !locked && boss.state != BState.DEAD
        bufAttack -= dt; bufHeavy -= dt; bufRoll -= dt; bufHeal -= dt
        if (boss.state == BState.DEAD) locked = false

        // Stamina comes back after a short pause
        if (p.staminaDelay > 0f) p.staminaDelay -= dt
        else if (p.state != PState.ROLL && p.state != PState.ATTACK && p.state != PState.HEAVY)
            p.stamina = min(Player.MAX_STAMINA, p.stamina + (if (p.state == PState.HEAL) 15f else 38f) * dt)

        // Stick to world direction (relative to the camera)
        val (fx, fz) = camForward()
        val rx = -fz; val rz = fx
        var mx = input.moveX; var my = input.moveY
        val ml = sqrt(mx * mx + my * my)
        if (ml > 1f) { mx /= ml; my /= ml }
        val wx = rx * mx + fx * my
        val wz = rz * mx + fz * my
        val stick = min(1f, ml)

        val toBX = boss.x - p.x; val toBZ = boss.z - p.z
        val distB = sqrt(toBX * toBX + toBZ * toBZ)
        val bossYaw = atan2(toBX, toBZ)

        if (p.state == PState.DEAD) { p.vx *= 0.9f; p.vz *= 0.9f; p.x += p.vx * dt; p.z += p.vz * dt; return }

        val free = p.state == PState.IDLE
        // Starting actions
        if (free) {
            when {
                bufRoll > 0f && useStamina(p, 20f) -> startRoll(p, wx, wz, stick)
                bufHeavy > 0f && useStamina(p, 32f) -> { bufHeavy = 0f; p.state = PState.HEAVY; p.t = 0f; p.hitDone = false; faceForAttack(p, bossYaw, distB, wx, wz, stick) }
                bufAttack > 0f && useStamina(p, 17f) -> { bufAttack = 0f; p.state = PState.ATTACK; p.t = 0f; p.combo = 0; p.hitDone = false; faceForAttack(p, bossYaw, distB, wx, wz, stick) }
                bufHeal > 0f && p.flasks > 0 -> { bufHeal = 0f; p.state = PState.HEAL; p.t = 0f; p.healed = false }
            }
        }

        var moveSpeed = 0f
        when (p.state) {
            PState.IDLE -> {
                moveSpeed = 5.2f * stick
                if (stick > 0.05f) {
                    // Locked on: strafe while facing the boss. Free: turn to where you walk.
                    val want = if (locked) bossYaw else atan2(wx, wz)
                    p.yaw += wrapAngle(want - p.yaw) * min(1f, dt * 14f)
                } else if (locked) p.yaw += wrapAngle(bossYaw - p.yaw) * min(1f, dt * 10f)
            }
            PState.ATTACK -> {
                val len = if (p.combo == 2) 0.72f else 0.52f
                val h0 = if (p.combo == 2) 0.3f else 0.16f
                val h1 = if (p.combo == 2) 0.42f else 0.28f
                if (p.t < h0) stepForward(p, 2.2f, dt)
                if (!p.hitDone && p.t in h0..h1) tryHitBoss(p, reach = 2.3f, arc = 1.1f, dmg = if (p.combo == 2) 21f else 14f, poiseDmg = 12f)
                // Chain into the next swing, or roll out of it
                if (p.t > h1 + 0.05f) {
                    if (bufRoll > 0f && useStamina(p, 20f)) startRoll(p, wx, wz, stick)
                    else if (bufAttack > 0f && p.combo < 2 && useStamina(p, 17f)) {
                        bufAttack = 0f; p.combo++; p.t = 0f; p.hitDone = false; faceForAttack(p, bossYaw, distB, wx, wz, stick)
                    }
                }
                if (p.state == PState.ATTACK && p.t >= len) { p.state = PState.IDLE; p.t = 0f }
            }
            PState.HEAVY -> {
                if (p.t in 0.35f..0.55f) stepForward(p, 3.5f, dt)
                if (!p.hitDone && p.t in 0.5f..0.66f) tryHitBoss(p, reach = 2.6f, arc = 0.9f, dmg = 36f, poiseDmg = 42f)
                if (p.t > 0.8f && bufRoll > 0f && useStamina(p, 20f)) startRoll(p, wx, wz, stick)
                else if (p.t >= 1.05f) { p.state = PState.IDLE; p.t = 0f }
            }
            PState.ROLL -> {
                val k = (1f - p.t / ROLL_TIME).coerceAtLeast(0f)
                val sp = 9.5f * sqrt(k)
                p.vx = p.rollX * sp; p.vz = p.rollZ * sp
                if (p.t >= ROLL_TIME) { p.state = PState.IDLE; p.t = 0f }
            }
            PState.HEAL -> {
                moveSpeed = 1.3f * stick
                if (!p.healed && p.t > 0.65f) {
                    p.healed = true; p.flasks--
                    p.hp = min(Player.MAX_HP, p.hp + 55f)
                    repeat(14) { sparks.add(Spark(p.x, 1f, p.z, rnd.nextFloat() - 0.5f, 1.5f + rnd.nextFloat() * 2f, rnd.nextFloat() - 0.5f, 0.9f, true)) }
                }
                if (p.t >= 1.15f) { p.state = PState.IDLE; p.t = 0f }
            }
            PState.STAGGER -> {
                p.vx *= (1f - min(1f, dt * 6f)); p.vz *= (1f - min(1f, dt * 6f))
                if (p.t >= 0.5f) { p.state = PState.IDLE; p.t = 0f }
            }
            PState.DEAD -> {}
        }

        if (p.state == PState.IDLE || p.state == PState.HEAL) {
            val tx = if (stick > 0.05f) wx / max(stick, 0.001f) * moveSpeed else 0f
            val tz = if (stick > 0.05f) wz / max(stick, 0.001f) * moveSpeed else 0f
            val k = min(1f, dt * 12f)
            p.vx += (tx - p.vx) * k; p.vz += (tz - p.vz) * k
        } else if (p.state == PState.ATTACK || p.state == PState.HEAVY) {
            p.vx *= (1f - min(1f, dt * 10f)); p.vz *= (1f - min(1f, dt * 10f))
        }
        p.x += p.vx * dt; p.z += p.vz * dt
        p.speedNow = sqrt(p.vx * p.vx + p.vz * p.vz)
        p.walkPhase += p.speedNow * dt * 2.2f
    }

    private fun startRoll(p: Player, wx: Float, wz: Float, stick: Float) {
        bufRoll = 0f
        if (stick > 0.15f) { val l = sqrt(wx * wx + wz * wz); p.rollX = wx / l; p.rollZ = wz / l }
        else { p.rollX = sin(p.yaw); p.rollZ = cos(p.yaw) }
        p.yaw = atan2(p.rollX, p.rollZ)
        p.state = PState.ROLL; p.t = 0f
    }

    /** Swings turn toward the boss when locked on (or when it's close), otherwise toward the stick. */
    private fun faceForAttack(p: Player, bossYaw: Float, distB: Float, wx: Float, wz: Float, stick: Float) {
        p.yaw = when {
            locked || distB < 5.5f -> bossYaw
            stick > 0.2f -> atan2(wx, wz)
            else -> p.yaw
        }
    }

    private fun stepForward(p: Player, speed: Float, dt: Float) {
        p.x += sin(p.yaw) * speed * dt; p.z += cos(p.yaw) * speed * dt
    }

    val playerInvincible get() = player.state == PState.ROLL && player.t in 0.03f..0.4f

    private fun tryHitBoss(p: Player, reach: Float, arc: Float, dmg: Float, poiseDmg: Float) {
        val b = boss
        if (b.state == BState.DEAD || b.state == BState.INTRO || b.y > 1.2f) return
        val dx = b.x - p.x; val dz = b.z - p.z
        val d = sqrt(dx * dx + dz * dz)
        if (d > reach + Boss.RADIUS) return
        if (abs(wrapAngle(atan2(dx, dz) - p.yaw)) > arc) return
        p.hitDone = true
        val crit = b.state == BState.STAGGER
        hurtBoss(dmg * (if (crit) 1.7f else 1f), poiseDmg)
        // Sparks where the blade bites
        val hx = p.x + dx / d * (d - Boss.RADIUS * 0.6f); val hz = p.z + dz / d * (d - Boss.RADIUS * 0.6f)
        repeat(if (crit) 22 else 12) {
            sparks.add(Spark(hx, 1.4f + rnd.nextFloat(), hz, (rnd.nextFloat() - 0.5f) * 6f, rnd.nextFloat() * 4f, (rnd.nextFloat() - 0.5f) * 6f, 0.35f + rnd.nextFloat() * 0.3f, false))
        }
        shake = max(shake, if (crit) 0.35f else 0.15f)
    }

    // ------------------------------------------------------------------ boss

    private fun hurtBoss(dmg: Float, poiseDmg: Float) {
        val b = boss
        b.hp -= dmg
        b.hurtFlash = 1f
        evBossHit.incrementAndGet()
        if (b.hp <= 0f) {
            b.hp = 0f; b.state = BState.DEAD; b.t = 0f
            evWon.incrementAndGet()
            repeat(60) { sparks.add(Spark(b.x, 2f + rnd.nextFloat() * 2f, b.z, (rnd.nextFloat() - 0.5f) * 4f, rnd.nextFloat() * 5f, (rnd.nextFloat() - 0.5f) * 4f, 1.5f + rnd.nextFloat(), true)) }
            return
        }
        if (b.state != BState.STAGGER && b.state != BState.PHASE && b.state != BState.LEAP) {
            b.poise -= poiseDmg
            if (b.poise <= 0f) { b.state = BState.STAGGER; b.t = 0f; b.poise = Boss.MAX_POISE; b.y = 0f }
        }
    }

    private fun bossSpeed() = if (boss.phase2) 1.2f else 1f

    private fun updateBoss(dt: Float) {
        val b = boss
        val p = player
        b.hurtFlash = max(0f, b.hurtFlash - dt * 4f)
        b.t += dt * (if (b.state == BState.IDLE || b.state == BState.DEAD || b.state == BState.STAGGER) 1f else bossSpeed())
        val dx = p.x - b.x; val dz = p.z - b.z
        val dist = sqrt(dx * dx + dz * dz)
        val toP = atan2(dx, dz)
        b.moving = 0f

        fun turn(rate: Float) { b.yaw += wrapAngle(toP - b.yaw).coerceIn(-rate * dt, rate * dt) }
        fun fwd(speed: Float) { b.x += sin(b.yaw) * speed * dt; b.z += cos(b.yaw) * speed * dt }
        fun done(recover: Float) { if (b.t >= recover) { b.state = BState.IDLE; b.t = 0f; b.cooldown = (0.5f + rnd.nextFloat() * 0.8f) / bossSpeed() } }

        // Phase two at half health
        if (!b.phase2 && b.hp < Boss.MAX_HP * 0.5f && b.state == BState.IDLE) {
            b.phase2 = true; b.state = BState.PHASE; b.t = 0f
            evPhase.incrementAndGet()
        }

        when (b.state) {
            BState.INTRO -> { turn(2f); if (b.t > 1.6f) { b.state = BState.IDLE; b.t = 0f } }
            BState.IDLE -> {
                if (p.state == PState.DEAD) { b.walkPhase += dt * 0.5f; return }
                turn(2.4f * bossSpeed())
                b.cooldown -= dt
                val range = if (b.phase2) 4.6f else 4.2f
                if (dist > range) { fwd(2.7f * bossSpeed()); b.moving = 1f; b.walkPhase += dt * 2.7f }
                if (b.cooldown <= 0f) chooseAttack(dist)
            }
            BState.SLAM -> {
                // Overhead smash: long wind-up, then a heavy blow in front
                if (b.t < 0.75f) { turn(2.2f); if (dist > 3f) fwd(1.6f) }
                if (!b.hitDone && b.t >= 0.95f) {
                    b.hitDone = true
                    val hx = b.x + sin(b.yaw) * 3.2f; val hz = b.z + cos(b.yaw) * 3.2f
                    impact(hx, hz, 0.8f)
                    hitPlayerCircle(hx, hz, 2.0f, 36f)
                }
                done(1.95f)
            }
            BState.SWEEP -> {
                if (b.t < 0.6f) turn(2.6f)
                if (!b.hitDone && b.t in 0.72f..0.95f) {
                    if (dist < 4.9f + Player.RADIUS && abs(wrapAngle(toP - b.yaw)) < 1.9f) { b.hitDone = true; hurtPlayer(28f, b.x, b.z) }
                }
                if (b.t > 0.95f) b.hitDone = true
                done(1.7f)
            }
            BState.LUNGE -> {
                if (b.t < 0.6f) { turn(3f); b.lungeDX = sin(b.yaw); b.lungeDZ = cos(b.yaw) }
                else if (b.t < 1.08f) {
                    b.x += b.lungeDX * 13f * dt; b.z += b.lungeDZ * 13f * dt
                    b.moving = 1f; b.walkPhase += dt * 8f
                    if (!b.hitDone && dist < Boss.RADIUS + 1.1f) { b.hitDone = true; hurtPlayer(30f, b.x, b.z) }
                }
                done(2.1f)
            }
            BState.LEAP -> {
                // Jumps high and crashes down where the player was standing
                if (b.t < 0.7f) { turn(3f); b.leapFromX = b.x; b.leapFromZ = b.z; b.leapToX = p.x; b.leapToZ = p.z }
                else if (b.t < 1.5f) {
                    val k = (b.t - 0.7f) / 0.8f
                    b.x = b.leapFromX + (b.leapToX - b.leapFromX) * k
                    b.z = b.leapFromZ + (b.leapToZ - b.leapFromZ) * k
                    b.y = sin(k * PI.toFloat()) * 6f
                } else if (!b.hitDone) {
                    b.hitDone = true; b.y = 0f
                    impact(b.x, b.z, 1.2f)
                    hitPlayerCircle(b.x, b.z, 2.4f, 40f)
                    waves.add(Shockwave(b.x, b.z))
                }
                done(2.8f)
            }
            BState.COMBO -> {
                // Phase two: sweep, then straight into a slam
                if (b.comboStep == 0) {
                    if (b.t < 0.5f) turn(2.8f)
                    if (!b.hitDone && b.t in 0.6f..0.82f && dist < 5f + Player.RADIUS && abs(wrapAngle(toP - b.yaw)) < 1.9f) { b.hitDone = true; hurtPlayer(26f, b.x, b.z) }
                    if (b.t >= 1.05f) { b.comboStep = 1; b.t = 0f; b.hitDone = false }
                } else {
                    if (b.t < 0.55f) { turn(2.4f); if (dist > 3f) fwd(2.2f) }
                    if (!b.hitDone && b.t >= 0.75f) {
                        b.hitDone = true
                        val hx = b.x + sin(b.yaw) * 3.2f; val hz = b.z + cos(b.yaw) * 3.2f
                        impact(hx, hz, 0.9f)
                        hitPlayerCircle(hx, hz, 2.1f, 38f)
                    }
                    done(1.8f)
                }
            }
            BState.STAGGER -> {
                // Knocked off balance: free hits (and critical damage) for a moment
                if (b.t >= 2.2f) { b.state = BState.IDLE; b.t = 0f; b.cooldown = 0.3f }
            }
            BState.PHASE -> {
                // Roars and bursts into flame, pushing the player back
                if (!b.hitDone && b.t > 1.0f) {
                    b.hitDone = true
                    waves.add(Shockwave(b.x, b.z))
                    shake = 0.6f
                    repeat(50) { sparks.add(Spark(b.x, 2.5f, b.z, (rnd.nextFloat() - 0.5f) * 10f, rnd.nextFloat() * 6f, (rnd.nextFloat() - 0.5f) * 10f, 1.2f, true)) }
                }
                if (b.t >= 2.4f) { b.state = BState.IDLE; b.t = 0f; b.cooldown = 0.4f; b.hitDone = false }
            }
            BState.DEAD -> { b.y = 0f }
        }
        if (b.phase2 && b.state != BState.DEAD && rnd.nextFloat() < dt * 14f) {
            sparks.add(Spark(b.x + (rnd.nextFloat() - 0.5f) * 1.6f, b.y + 1.5f + rnd.nextFloat() * 3f, b.z + (rnd.nextFloat() - 0.5f) * 1.6f, 0f, 1.2f + rnd.nextFloat(), 0f, 1f, true))
        }
    }

    private fun chooseAttack(dist: Float) {
        val b = boss
        val r = rnd.nextFloat()
        b.state = when {
            dist > 8f -> if (b.phase2 && r < 0.55f) BState.LEAP else BState.LUNGE
            dist > 4.8f -> if (b.phase2 && r < 0.3f) BState.LEAP else if (r < 0.55f) BState.SLAM else BState.LUNGE
            else -> if (b.phase2 && r < 0.35f) BState.COMBO else if (r < 0.5f) BState.SWEEP else BState.SLAM
        }
        b.t = 0f; b.hitDone = false; b.comboStep = 0
    }

    private fun impact(x: Float, z: Float, power: Float) {
        shake = max(shake, 0.5f * power)
        repeat((24 * power).toInt()) {
            val a = rnd.nextFloat() * 6.283f
            sparks.add(Spark(x, 0.2f, z, cos(a) * 5f * power, 2f + rnd.nextFloat() * 4f, sin(a) * 5f * power, 0.6f + rnd.nextFloat() * 0.4f, false))
        }
    }

    private fun hitPlayerCircle(x: Float, z: Float, r: Float, dmg: Float) {
        val dx = player.x - x; val dz = player.z - z
        if (dx * dx + dz * dz < (r + Player.RADIUS) * (r + Player.RADIUS)) hurtPlayer(dmg, x, z)
    }

    private fun hurtPlayer(dmg: Float, fromX: Float, fromZ: Float) {
        val p = player
        if (p.state == PState.DEAD) return
        if (playerInvincible) { evBlocked.incrementAndGet(); return }
        p.hp -= dmg
        p.hurtFlash = 1f
        evPlayerHit.incrementAndGet()
        shake = max(shake, 0.35f)
        val dx = p.x - fromX; val dz = p.z - fromZ
        val l = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
        p.vx = dx / l * 7f; p.vz = dz / l * 7f
        repeat(10) { sparks.add(Spark(p.x, 1.2f, p.z, (rnd.nextFloat() - 0.5f) * 4f, rnd.nextFloat() * 3f, (rnd.nextFloat() - 0.5f) * 4f, 0.5f, false)) }
        if (p.hp <= 0f) {
            p.hp = 0f; p.state = PState.DEAD; p.t = 0f
            locked = false
            evDied.incrementAndGet()
        } else { p.state = PState.STAGGER; p.t = 0f }
    }

    private fun updateWaves(dt: Float) {
        val it = waves.iterator()
        while (it.hasNext()) {
            val w = it.next()
            w.r += 9f * dt
            val dx = player.x - w.x; val dz = player.z - w.z
            val d = sqrt(dx * dx + dz * dz)
            if (!w.hitDone && abs(d - w.r) < 0.55f) { w.hitDone = true; hurtPlayer(if (boss.state == BState.PHASE) 15f else 22f, w.x, w.z) }
            if (w.r > 11f) it.remove()
        }
    }

    // ------------------------------------------------------------------ world

    private fun collide() {
        val p = player; val b = boss
        // Player vs boss
        if (b.state != BState.DEAD && b.y < 1.5f) {
            val dx = p.x - b.x; val dz = p.z - b.z
            val d = sqrt(dx * dx + dz * dz)
            val min = Player.RADIUS + Boss.RADIUS
            if (d < min && d > 0.001f) { p.x = b.x + dx / d * min; p.z = b.z + dz / d * min }
        }
        pushOut(p, Player.RADIUS)
        // Boss stays in the arena and out of the pillars
        for (pl in PILLARS) {
            val dx = b.x - pl[0]; val dz = b.z - pl[1]
            val d = sqrt(dx * dx + dz * dz); val m = pl[2] + Boss.RADIUS
            if (d < m && d > 0.001f && b.y < 2f) { b.x = pl[0] + dx / d * m; b.z = pl[1] + dz / d * m }
        }
        val bd = sqrt(b.x * b.x + b.z * b.z)
        if (bd > ARENA_R - Boss.RADIUS) { b.x *= (ARENA_R - Boss.RADIUS) / bd; b.z *= (ARENA_R - Boss.RADIUS) / bd }
    }

    private fun pushOut(p: Player, r: Float) {
        for (pl in PILLARS) {
            val dx = p.x - pl[0]; val dz = p.z - pl[1]
            val d = sqrt(dx * dx + dz * dz); val m = pl[2] + r
            if (d < m && d > 0.001f) { p.x = pl[0] + dx / d * m; p.z = pl[1] + dz / d * m }
        }
        val d = sqrt(p.x * p.x + p.z * p.z)
        if (d > ARENA_R - r) { p.x *= (ARENA_R - r) / d; p.z *= (ARENA_R - r) / d }
    }

    private fun updateSparks(dt: Float) {
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.life -= dt
            if (s.life <= 0f) { it.remove(); continue }
            s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt
            if (s.ember) { s.vy += 0.5f * dt; s.vx *= 0.98f; s.vz *= 0.98f }
            else { s.vy -= 14f * dt; if (s.y < 0.05f) { s.y = 0.05f; s.vy = -s.vy * 0.3f; s.vx *= 0.6f; s.vz *= 0.6f } }
        }
        // Ambient embers drifting through the arena
        if (rnd.nextFloat() < dt * 6f && sparks.size < 400) {
            val a = rnd.nextFloat() * 6.283f; val r = rnd.nextFloat() * 15f
            sparks.add(Spark(sin(a) * r, 0.2f, cos(a) * r, (rnd.nextFloat() - 0.5f) * 0.4f, 0.4f + rnd.nextFloat() * 0.5f, (rnd.nextFloat() - 0.5f) * 0.4f, 3f + rnd.nextFloat() * 3f, true))
        }
    }

    private fun updateCamera(dt: Float, title: Boolean) {
        val p = player; val b = boss
        if (!title) {
            val drag = input.takeDrag()
            if (locked) {
                val want = atan2(b.x - p.x, b.z - p.z)
                camYaw += wrapAngle(want - camYaw) * min(1f, dt * 6f)
            } else camYaw = wrapAngle(camYaw + drag)
        }
        val fx = sin(camYaw); val fz = cos(camYaw)
        val fy = if (title) 0f else 0f
        var dist = if (title) 22f else 6.2f
        val height = if (title) 9f else 3.1f
        val cx0 = if (title) 0f else p.x
        val cz0 = if (title) 0f else p.z
        // Keep the camera inside the arena walls
        for (i in 0 until 12) {
            val tx = cx0 - fx * dist; val tz = cz0 - fz * dist
            if (title || tx * tx + tz * tz < (ARENA_R + 1.5f) * (ARENA_R + 1.5f)) break
            dist *= 0.88f
        }
        val tx = cx0 - fx * dist; val tz = cz0 - fz * dist
        val k = min(1f, dt * 9f)
        camX += (tx - camX) * k; camZ += (tz - camZ) * k; camY += (height + fy - camY) * k
        var lx = cx0 + fx * 2f; var lz = cz0 + fz * 2f; var ly = if (title) 1.5f else 1.5f
        if (locked && !title) { lx = p.x * 0.6f + b.x * 0.4f; lz = p.z * 0.6f + b.z * 0.4f; ly = 1.4f + b.y * 0.3f }
        lookX += (lx - lookX) * k; lookY += (ly - lookY) * k; lookZ += (lz - lookZ) * k
        shake = max(0f, shake - dt * 1.6f)
    }

    // ------------------------------------------------------------------ for drawing: 0..1 progress of "danger" wind-ups
    /** How close the boss is to striking (0 = not attacking, 1 = about to hit). Used for the eye glow. */
    fun bossWindup(): Float {
        val b = boss
        // Fades out once the blow has landed (or the swing is over)
        if (b.hitDone) return 0f
        if (b.state == BState.SWEEP && b.t > 0.95f) return 0f
        if (b.state == BState.LUNGE && b.t > 1.08f) return 0f
        if (b.state == BState.COMBO && b.comboStep == 0 && b.t > 0.82f) return 0f
        return when (b.state) {
            BState.SLAM -> (b.t / 0.95f).coerceIn(0f, 1f)
            BState.SWEEP -> (b.t / 0.72f).coerceIn(0f, 1f)
            BState.LUNGE -> (b.t / 0.6f).coerceIn(0f, 1f)
            BState.LEAP -> (b.t / 0.7f).coerceIn(0f, 1f)
            BState.COMBO -> if (b.comboStep == 0) (b.t / 0.6f).coerceIn(0f, 1f) else (b.t / 0.75f).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}

const val ROLL_TIME = 0.55f
