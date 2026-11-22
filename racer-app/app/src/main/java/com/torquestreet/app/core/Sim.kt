package com.torquestreet.app.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class Role { PLAYER, RIVAL, COP, TRAFFIC }
enum class Mode(val label: String) { RACE("Race"), PURSUIT("Pursuit") }
enum class RaceState { COUNTDOWN, RUNNING, FINISHED, BUSTED, ESCAPED }

/** What the player is pressing this moment. */
class Input { var steer = 0f; var gas = 0f; var brake = 0f; var nitro = false }

/** One car on the track: where it is (s along, d across, heading psi relative to the road), its speed and state. */
class Car(val spec: CarSpec, val role: Role, var paint: FloatArray, val shape: CarShape) {
    var s = 0f; var d = 0f; var psi = 0f; var v = 0f
    var lap = 0
    /** Total distance covered in the race (laps x length + s), for positions. */
    var progress = 0f
    var steer = 0f; var throttle = 0f; var brake = 0f
    var nitroOn = false; var nitro = 0.35f
    var slide = 0f; var driftVis = 0f
    var finished = false; var finishTime = 0f; var lapStart = 0f; var bestLap = 0f; var lastLap = 0f
    var hitWall = 0f; var hitCar = 0f; var scrape = 0f
    var active = true
    var skill = 1f
    var lane = 0f
    var cruise = 0f
    /** Police: 0 waiting at the roadside, 1 chasing, 2 part of a roadblock. */
    var copState = 0
    var lightsOn = false
    var gear = 1; var rpm = 0.2f
    // Where it is in the world (for drawing)
    val pos = FloatArray(3); var yaw = 0f; var pitch = 0f; var roll = 0f
    val length get() = shape.length
    val width get() = shape.width
    var id = 0
}

/** Something to show the player for a moment ("FINAL LAP", "HEAT 3"...). */
class Message(val text: String, val sub: String, var t: Float, val color: Int)

/**
 * A race (or a pursuit): the cars, the police, the rules. Call [step] with the player's input every frame.
 * All in plain Kotlin, so whole races can be run on a computer to check the AI and police.
 */
class Race(val track: Track, val mode: Mode, val playerSpec: CarSpec, playerPaint: FloatArray, val env: Env, seed: Long = 1L) {
    val cars = ArrayList<Car>()
    val player: Car
    val laps = track.info.laps
    var state = RaceState.COUNTDOWN
    var countdown = 3.6f
    var time = 0f
    var heat = 0; var heatProgress = 0f
    var pursuit = false
    var bustedMeter = 0f; var escapeMeter = 0f
    var cooldown = 0f
    var pursuitTime = 0f
    var copsEvaded = 0
    val messages = ArrayList<Message>()
    /** Strongest bump this frame (for sound and vibration), and whether it was the player. */
    var bump = 0f
    /** How close the nearest police car is (0 far .. 1 right here), for the siren. */
    var sirenNear = 0f
    /** Nitro earned for style this frame (near misses, drifts) - for the HUD. */
    var styleText = ""; var styleT = 0f
    private val rnd = java.util.Random(seed)
    private val grip = when (env.weather) { Weather.RAIN -> 0.84f; Weather.FOG -> 0.94f; else -> 1f }
    private val racingLine = FloatArray(track.n)
    private var nextPatrol = 20f
    private var nextRoadblock = 0f
    private val playerTop get() = playerSpec.topSpeed

    init {
        // The racing line: towards the inside of each bend, judged a little ahead
        for (k in 0 until track.n) {
            var ks = 0f
            for (o in 0 until 20) ks += track.kappa[track.idx(k + o)]
            ks /= 20f
            racingLine[k] = clamp(ks * 140f, -1f, 1f) * (track.hw[k] - 2.3f)
        }
        // Grid: the player starts at the back
        val nRivals = if (mode == Mode.RACE) 3 else 0
        val paints = Cars.rivalPaints.shuffled(rnd)
        for (i in 0 until nRivals) {
            val spec = Cars.player[(i + 1 + playerSpec.id) % Cars.player.size]
            val c = Car(spec, Role.RIVAL, paints[i], spec.shape)
            c.skill = 0.9f + 0.03f * i + rnd.nextFloat() * 0.03f
            cars.add(c)
        }
        player = Car(playerSpec, Role.PLAYER, playerPaint, playerSpec.shape)
        cars.add(player)
        for ((i, c) in cars.withIndex()) {
            c.s = track.wrap(-10f - i * 8.5f); c.d = if (i % 2 == 0) -3.2f else 3.2f
            c.progress = c.s - track.length; c.lap = -1
            c.id = i
        }
        if (mode == Mode.PURSUIT) { player.s = 60f; player.d = 0f; player.progress = 60f; player.lap = 0 }
        // Traffic, spread out ahead
        val nTraffic = when (track.scene) { Scene.CITY -> 9; Scene.COAST -> 8; Scene.MOUNTAIN -> 6 }
        for (i in 0 until nTraffic) {
            val shape = Cars.trafficShapes[rnd.nextInt(Cars.trafficShapes.size)]
            val spec = CarSpec(20 + i, "Traffic", "", 26f, 4f, 9f, 9f, if (shape == Cars.TRUCK) 6000f else if (shape == Cars.VAN) 2500f else 1400f, shape, emptyList())
            val c = Car(spec, Role.TRAFFIC, Cars.trafficPaints[rnd.nextInt(Cars.trafficPaints.size)], shape)
            placeTraffic(c, 250f + i * (700f / nTraffic) + rnd.nextFloat() * 40f)
            c.id = cars.size
            cars.add(c)
        }
        if (mode == Mode.PURSUIT) {
            heat = 1; startPursuit(silent = true)
            for (k in 0 until 2) spawnCop(-120f - k * 40f, chasing = true)
            message("ESCAPE THE POLICE", "Lose them to get away", 0xFF5AB4FF.toInt())
        }
        poseAll()
    }

    private fun message(t: String, sub: String = "", color: Int = 0xFFFFFFFF.toInt()) { messages.add(Message(t, sub, 2.6f, color)) }

    // ------------------------------------------------------------------ the frame

    fun step(dtIn: Float, input: Input) {
        val dt = min(dtIn, 0.05f)
        bump = 0f
        for (m in messages) m.t -= dt
        messages.removeAll { it.t <= 0f }
        if (styleT > 0f) styleT -= dt
        when (state) {
            RaceState.COUNTDOWN -> {
                val before = countdown
                countdown -= dt
                if (before > 0.6f && countdown <= 0.6f) message("GO!", "", 0xFF66FF99.toInt())
                if (countdown <= 0.6f) { state = RaceState.RUNNING; for (c in cars) if (c.role != Role.TRAFFIC) c.lapStart = time }
                // Revving on the line, going nowhere
                player.throttle = input.gas
                for (c in cars) if (c.role == Role.TRAFFIC) driveTraffic(c, dt)
                poseAll(); return
            }
            RaceState.RUNNING -> time += dt
            else -> { time += dt }
        }
        val sub = 2
        val h = dt / sub
        for (it in 0 until sub) {
            for (c in cars) {
                if (!c.active) continue
                when (c.role) {
                    Role.PLAYER -> if (state == RaceState.RUNNING) applyInput(c, input) else { c.throttle = 0f; c.brake = 0.5f; c.steer *= 0.9f; c.nitroOn = false }
                    Role.RIVAL -> driveAi(c, h)
                    Role.COP -> driveCop(c, h)
                    Role.TRAFFIC -> driveTraffic(c, h)
                }
                physics(c, h)
            }
            collisions()
        }
        laps()
        police(dt)
        traffic()
        poseAll()
    }

    private fun applyInput(c: Car, i: Input) {
        c.steer = approach(c.steer, i.steer, 4.5f / 60f)
        c.throttle = i.gas; c.brake = i.brake
        c.nitroOn = i.nitro && c.nitro > 0.02f && i.gas > 0.1f
    }

    // ------------------------------------------------------------------ physics

    private fun maxSteer(v: Float) = lerp(0.55f, 0.07f, clamp(abs(v) / 55f, 0f, 1f))

    private fun physics(c: Car, dt: Float) {
        val spec = c.spec
        val t = track
        val k = t.kappaAt(c.s)
        // Longitudinal
        var top = spec.topSpeed
        var accel = spec.accel
        if (c.role == Role.RIVAL) {
            val gap = c.progress - player.progress
            if (gap < -120f) { top *= 1.07f; accel *= 1.12f } else if (gap > 160f) { top *= 0.95f }
        }
        if (c.role == Role.COP && c.copState == 1) {
            // Police can always catch up when they've fallen behind (less so once you've lost them)
            val gap = t.delta(c.s, player.s)
            // About as fast as you, a little faster as the heat rises; a burst to catch up only while they can see you
            top = playerTop * (0.98f + 0.016f * heat) + if (gap > 80f && escapeMeter < 0.3f) 5f else 0f
            accel *= 1f + 0.04f * heat
        }
        if (c.nitroOn) { top *= 1.13f; accel += 6.5f; c.nitro = max(0f, c.nitro - dt / 4.5f) }
        var a = 0f
        if (c.v < top) a += accel * c.throttle * (1f - (c.v / top).let { it * it }).coerceAtLeast(0.05f)
        else a -= (c.v - top) * 0.8f
        a -= 0.25f + 0.00011f * c.v * c.v
        if (c.brake > 0f) {
            if (c.v > 0.5f) a -= spec.brake * c.brake * (if (grip < 1f) 0.88f else 1f)
            else if (c.throttle < 0.1f && c.role == Role.PLAYER) a -= 5f * c.brake // reversing slowly
        }
        if (c.throttle < 0.05f && c.brake <= 0f && abs(c.v) < 0.6f) { c.v = 0f; a = 0f }
        val reversing = c.role == Role.PLAYER && c.brake > 0f && c.throttle < 0.1f && c.v <= 0.5f
        val nv = c.v + a * dt
        // Slowing down stops at standstill (only the player can reverse, by holding brake)
        c.v = if (c.v >= 0f && nv < 0f && !reversing) 0f else clamp(nv, -7f, top * 1.3f)

        // Turning, limited by grip
        val v = c.v
        val gripMax = spec.grip * grip * (if (c.role == Role.RIVAL) c.skill else 1f)
        val steerAngle = c.steer * maxSteer(v)
        var omega = v * tan(steerAngle) / WHEELBASE
        val latReq = abs(v * omega)
        if (latReq > gripMax && abs(v) > 3f) {
            val excess = (latReq - gripMax) / gripMax
            omega = sign(omega) * gripMax * (1f + 0.18f * min(excess, 1f)) / abs(v)
            c.v -= sign(v) * min(excess, 1.5f) * 4.2f * dt
            c.slide = approach(c.slide, min(1f, excess), dt * 4f)
        } else c.slide = approach(c.slide, 0f, dt * 3f)
        // A drift looks the part: the body swings out a little while the tyres slide
        c.driftVis = approach(c.driftVis, c.slide * 0.32f * sign(c.steer), dt * 2f)
        if (c.role == Role.PLAYER && c.slide > 0.35f && abs(c.v) > 15f) {
            c.nitro = min(1f, c.nitro + dt * 0.06f)
            if (styleT <= 0f) { styleText = "DRIFT"; styleT = 0.8f }
        }

        // Moving along the road
        val denom = max(0.3f, 1f - k * c.d)
        val ds = c.v * cos(c.psi) / denom
        c.s = t.wrap(c.s + ds * dt)
        c.progress += ds * dt
        c.d += c.v * sin(c.psi) * dt
        c.psi += (omega - k * ds) * dt
        // Gentle help: with the wheel straight, the car settles back in line with the road
        if (abs(c.steer) < 0.12f) c.psi *= 1f - min(1f, dt * (if (c.role == Role.PLAYER) 1.2f else 2.5f))
        c.psi = clamp(c.psi, -1.1f, 1.1f)

        // The walls (curbs, rails)
        val lim = t.wallAt(c.s) - c.width * 0.5f
        c.scrape = approach(c.scrape, 0f, dt * 4f)
        if (c.d > lim || c.d < -lim) {
            val side = if (c.d > 0) 1f else -1f
            c.d = side * lim
            val into = c.psi * side
            if (into > 0f) {
                val impact = abs(c.v) * sin(into)
                c.v *= 1f - 0.45f * min(1f, sin(into) * 1.6f)
                c.psi = -c.psi * 0.25f
                c.hitWall = max(c.hitWall, impact)
                if (c.role == Role.PLAYER) bump = max(bump, impact)
            }
            c.v -= sign(c.v) * 2.5f * dt
            c.scrape = 1f
        }
        c.hitWall = approach(c.hitWall, 0f, dt * 30f)
        c.hitCar = approach(c.hitCar, 0f, dt * 30f)

        // Gears and engine speed (for the sound and the dial)
        val f = clamp(abs(c.v) / (spec.topSpeed * 1.05f), 0f, 1.2f)
        val shifts = floatArrayOf(0f, 0.17f, 0.31f, 0.46f, 0.62f, 0.8f, 2f)
        var g = 1
        while (g < 6 && f > shifts[g]) g++
        c.gear = g
        c.rpm = clamp(0.28f + 0.72f * (f - shifts[g - 1]) / (shifts[g] - shifts[g - 1]), 0.2f, 1f)
        if (c.throttle < 0.1f && f < 0.02f) c.rpm = 0.2f
    }

    /** Drives the player's car for them (for tests, and the menu's background). [pace] 0..1 how hard. */
    fun autopilot(i: Input, pace: Float = 0.95f) {
        val c = player
        val keep = c.steer
        val target = chooseLine(c, racingLine[track.index(c.s)])
        steerToward(c, target, 10f + abs(c.v) * 0.45f)
        i.steer = c.steer; c.steer = keep
        val vt = safeSpeed(c, c.spec.grip * grip * pace, c.spec.brake * 0.8f)
        if (c.v < vt - 1f) { i.gas = 1f; i.brake = 0f } else if (c.v > vt + 1f) { i.gas = 0f; i.brake = clamp((c.v - vt) / 6f, 0f, 1f) } else { i.gas = 0.6f; i.brake = 0f }
        i.nitro = c.nitro > 0.3f && abs(track.kappaAt(c.s + 100f)) < 0.003f
    }

    // ------------------------------------------------------------------ rivals

    private fun steerToward(c: Car, dTarget: Float, look: Float) {
        val kAhead = track.kappaAt(c.s + look * 0.5f)
        val psiT = atan2(dTarget - c.d, look)
        val wantOmega = kAhead * c.v + (psiT - c.psi) * 2.6f
        val v = max(abs(c.v), 4f)
        val angle = atan(wantOmega * WHEELBASE / v)
        c.steer = clamp(angle / maxSteer(v), -1f, 1f)
    }

    /**
     * Where across the road to drive: the clearest path near [preferred], looking at the cars ahead that it's
     * catching (slower ones, and anything stopped), the closer the more it matters.
     */
    private fun chooseLine(c: Car, preferred: Float): Float {
        val hw = track.hwAt(c.s)
        val look = 25f + abs(c.v) * 1.3f
        var best = preferred; var bestCost = Float.MAX_VALUE
        var cand = -hw + 1.3f
        while (cand <= hw - 1.3f) {
            var cost = abs(cand - preferred) * 0.12f + abs(cand - c.d) * 0.04f
            for (o in cars) {
                if (o === c || !o.active) continue
                val ds = track.delta(c.s, o.s)
                if (ds <= -1f || ds > look) continue
                if (o.v > c.v + 2f && ds > 6f) continue // pulling away: not in the way
                val clear = (o.width + c.width) * 0.5f + 0.6f + (if (o.copState == 2) 1.6f else 0f)
                val dd = abs(cand - o.d)
                if (dd < clear) cost += 12f * (1f - ds / look) * (1f + (clear - dd))
            }
            if (cost < bestCost) { bestCost = cost; best = cand }
            cand += 0.6f
        }
        return best
    }

    /** The fastest it can go here and still make every bend ahead (braking in time). */
    private fun safeSpeed(c: Car, gripAi: Float, brakeAi: Float): Float {
        var best = c.spec.topSpeed * 1.2f
        var x = 0f
        val reach = c.v * c.v / (2f * brakeAi) + 30f
        while (x < reach) {
            val kk = abs(track.kappaAt(c.s + x)).coerceAtLeast(1e-4f)
            val vc = sqrt(gripAi / kk)
            best = min(best, sqrt(vc * vc + 2f * brakeAi * x))
            x += 8f
        }
        return best
    }

    private fun driveAi(c: Car, dt: Float) {
        if (c.finished) { c.throttle = 0f; c.brake = 0.4f; steerToward(c, c.d * 0.98f, 20f); return }
        // The racing line, round whatever is in the way
        val target = chooseLine(c, racingLine[track.index(c.s)])
        var blockGap = 999f
        for (o in cars) {
            if (o === c || !o.active) continue
            val ds = track.delta(c.s, o.s)
            if (ds > 0f && ds < 32f && abs(o.d - target) < (o.width + c.width) * 0.5f + 0.3f) blockGap = min(blockGap, ds)
        }
        steerToward(c, target, 10f + abs(c.v) * 0.45f)
        val g = c.spec.grip * grip * c.skill * 0.97f
        var vt = safeSpeed(c, g, c.spec.brake * 0.8f)
        if (blockGap < 14f) vt = min(vt, cars.firstOrNull { it !== c && abs(track.delta(c.s, it.s) - blockGap) < 0.1f }?.v?.plus(3f) ?: vt)
        if (c.v < vt - 1f) { c.throttle = 1f; c.brake = 0f } else if (c.v > vt + 1f) { c.throttle = 0f; c.brake = clamp((c.v - vt) / 6f, 0f, 1f) } else { c.throttle = 0.6f; c.brake = 0f }
        // Nitro on long straights now and then
        c.nitro = min(1f, c.nitro + dt * 0.02f)
        c.nitroOn = c.nitro > 0.5f && abs(track.kappaAt(c.s + 120f)) < 0.003f && c.v > 30f || (c.nitroOn && c.nitro > 0.05f && abs(track.kappaAt(c.s + 60f)) < 0.004f)
    }

    // ------------------------------------------------------------------ traffic

    private fun placeTraffic(c: Car, ahead: Float) {
        val s = track.wrap(player.sOr(0f) + ahead)
        c.s = s
        val hw = track.hwAt(s)
        val lanes = floatArrayOf(-0.75f, -0.25f, 0.25f, 0.75f)
        c.lane = lanes[rnd.nextInt(4)] * hw
        c.d = c.lane; c.psi = 0f
        c.cruise = if (c.shape == Cars.TRUCK) 14f + rnd.nextFloat() * 4f else 16f + rnd.nextFloat() * 8f
        c.v = c.cruise
        c.active = true
    }
    private fun Car?.sOr(def: Float) = this?.s ?: def

    private fun driveTraffic(c: Car, dt: Float) {
        // Keep to its lane and its speed; slow down behind whoever is ahead in the lane
        var want = c.cruise
        var blocked: Car? = null
        for (o in cars) {
            if (o === c || !o.active) continue
            val ds = track.delta(c.s, o.s)
            if (ds > 0f && ds < 26f && abs(o.d - c.lane) < 2.2f) { want = min(want, o.v - 1f + (ds - 10f) * 0.3f); blocked = o }
            if (ds > 0f && ds < 60f && abs(o.d - c.lane) < 2.4f && o.v < c.cruise - 6f) blocked = o
        }
        val hw = track.hwAt(c.s)
        // Stuck behind something slow (or stopped): move over to a clear lane
        if (blocked != null) {
            val lanes = floatArrayOf(-0.75f, -0.25f, 0.25f, 0.75f)
            var bestLane = c.lane; var bestRoom = 0f
            for (l in lanes) {
                val ld = l * hw
                if (abs(ld - c.lane) > hw * 0.6f) continue
                var room = 200f
                for (o in cars) {
                    if (o === c || !o.active) continue
                    val ds = track.delta(c.s, o.s)
                    if (ds > -12f && ds < 80f && abs(o.d - ld) < 2.4f) room = min(room, if (ds < 0f) 0f else ds)
                }
                if (room > bestRoom + 10f) { bestRoom = room; bestLane = ld }
            }
            c.lane = bestLane
        }
        c.lane = clamp(c.lane, -hw + 1.6f, hw - 1.6f)
        steerToward(c, c.lane, 18f)
        if (c.v < want) { c.throttle = 0.7f; c.brake = 0f } else { c.throttle = 0f; c.brake = clamp((c.v - want) / 5f, 0f, 1f) }
    }

    private fun traffic() {
        for (c in cars) {
            if (c.role != Role.TRAFFIC) continue
            val gap = track.delta(player.s, c.s)
            // Fell far behind or got too far ahead: comes back further up the road, out of sight
            if (gap < -180f || gap > 1100f) placeTraffic(c, 500f + rnd.nextFloat() * 450f)
        }
    }

    // ------------------------------------------------------------------ police

    private fun spawnCop(rel: Float, chasing: Boolean, d: Float = 0f): Car {
        val c = cars.firstOrNull { it.role == Role.COP && !it.active } ?: Car(Cars.COP, Role.COP, floatArrayOf(1f, 1f, 1f), Cars.INTERCEPTOR).also { it.id = cars.size; cars.add(it) }
        c.active = true
        c.s = track.wrap(player.s + rel); c.d = d; c.psi = 0f
        c.copState = if (chasing) 1 else 0
        c.v = if (chasing) max(0f, player.v * 0.9f) else 0f
        c.lightsOn = chasing
        c.progress = player.progress + rel
        return c
    }

    private fun startPursuit(silent: Boolean = false) {
        if (pursuit) return
        pursuit = true; pursuitTime = 0f
        heat = max(heat, 1)
        escapeMeter = 0f; bustedMeter = 0f
        for (c in cars) if (c.role == Role.COP && c.active && c.copState == 0) { c.copState = 1; c.lightsOn = true }
        if (!silent) message("PURSUIT!", "Heat level $heat", 0xFFFF5050.toInt())
    }

    private fun driveCop(c: Car, dt: Float) {
        when (c.copState) {
            0, 2 -> { c.throttle = 0f; c.brake = 1f; c.steer = 0f; return } // parked / roadblock
        }
        val gap = track.delta(c.s, player.s) // > 0: the player is ahead
        // Side by side with others chasing, they spread out to box the player in
        val idx = cars.filter { it.role == Role.COP && it.active && it.copState == 1 }.indexOf(c)
        val spread = when (idx % 3) { 1 -> 2.6f; 2 -> -2.6f; else -> 0f }
        val hw = track.hwAt(c.s)
        val aimD = clamp(player.d + (if (abs(gap) < 25f) spread * 0.4f else spread), -hw + 1.4f, hw - 1.4f)
        steerToward(c, if (gap > 30f) chooseLine(c, aimD) else aimD, 10f + abs(c.v) * 0.4f)
        val g = c.spec.grip * grip
        val vSafe = safeSpeed(c, g, c.spec.brake * 0.85f)
        val want = when {
            gap > 8f -> player.v + clamp(gap * 0.2f, 3f, 30f)
            gap < -10f -> if (heat >= 3) player.v - 5f else player.v - 1f // got ahead: at higher heat, slow in front to block
            else -> player.v + 1.5f // alongside: lean on them
        }
        val vt = min(want, vSafe)
        if (c.v < vt) { c.throttle = 1f; c.brake = 0f } else { c.throttle = 0f; c.brake = clamp((c.v - vt) / 5f, 0f, 1f) }
        c.nitroOn = false
    }

    private fun police(dt: Float) {
        if (state != RaceState.RUNNING) { sirenNear = approach(sirenNear, 0f, dt); return }
        val cops = cars.filter { it.role == Role.COP && it.active }
        var nearest = 9999f
        for (c in cops) { val dd = abs(track.delta(player.s, c.s)) + abs(c.d - player.d) * 0.5f; if (c.lightsOn) nearest = min(nearest, dd) }
        sirenNear = approach(sirenNear, clamp(1f - nearest / 320f, 0f, 1f), dt * 2f)
        if (cooldown > 0f) cooldown -= dt

        if (!pursuit) {
            // A patrol car waiting at the roadside up ahead
            if (mode == Mode.RACE && cooldown <= 0f && cops.none { it.copState == 0 } && time > nextPatrol) {
                val side = if (rnd.nextBoolean()) 1f else -1f
                spawnCop(520f + rnd.nextFloat() * 300f, chasing = false, d = side * (track.hwAt(player.s + 600f) - 1.6f))
            }
            for (c in cops) {
                if (c.copState != 0) continue
                val gap = track.delta(player.s, c.s)
                // Spotted: you flew past it (or hit it)
                if (gap in -30f..40f && abs(c.d - player.d) < 12f && player.v > 24f) startPursuit()
                if (gap < -250f) { c.active = false; nextPatrol = time + 25f + rnd.nextFloat() * 30f }
            }
            return
        }

        // In a pursuit
        pursuitTime += dt
        val chasing = cops.filter { it.copState == 1 }
        val close = cops.filter { abs(track.delta(player.s, it.s)) < 220f && it.copState != 2 }
        // The heat rises the longer they're on your tail (slower in a race, and only to 3 there)
        val maxHeat = if (mode == Mode.RACE) 3 else 5
        if (close.isNotEmpty()) heatProgress += dt / (if (mode == Mode.RACE) 60f else 45f)
        if (heatProgress >= 1f && heat < maxHeat) { heat++; heatProgress = 0f; message("HEAT LEVEL $heat", "More units joining", 0xFFFF7A3C.toInt()) }
        // Reinforcements (only while they still have you in sight)
        val want = min(heat, 5)
        if (chasing.size < want && close.isNotEmpty() && escapeMeter < 0.25f && rnd.nextFloat() < dt * 0.5f) spawnCop(-200f - rnd.nextFloat() * 60f, chasing = true)
        // Roadblocks from heat 3
        if (heat >= 3 && time > nextRoadblock && close.isNotEmpty()) {
            nextRoadblock = time + 55f
            val ahead = 650f
            val hw = track.hwAt(player.s + ahead)
            val gapSide = if (rnd.nextBoolean()) 1f else -1f
            // Two cars across the road, leaving one gap
            for (q in 0 until 2) {
                val c = spawnCop(ahead + q * 1.5f, chasing = false, d = -gapSide * (hw * 0.62f - q * 3.3f))
                c.copState = 2; c.psi = 1.25f * (if (q == 0) 1f else -1f); c.lightsOn = true
            }
            for (o in cars) if (o.role == Role.TRAFFIC && abs(track.delta(player.s + ahead, o.s)) < 120f) placeTraffic(o, ahead + 200f + rnd.nextFloat() * 200f)
            message("ROADBLOCK AHEAD", "Find the gap", 0xFFFFC840.toInt())
        }
        // Roadblocks left behind are cleared away
        for (c in cops) if (c.copState == 2 && track.delta(player.s, c.s) < -150f) c.active = false
        // Busted: stopped with the police on you
        val onYou = cops.any { abs(track.delta(player.s, it.s)) < 12f && abs(it.d - player.d) < 4.5f }
        if (onYou && abs(player.v) < 5f) bustedMeter += dt / 3f else bustedMeter = max(0f, bustedMeter - dt / 2f)
        if (bustedMeter >= 1f) {
            state = RaceState.BUSTED; message("BUSTED", "", 0xFFFF4040.toInt())
            return
        }
        // Escaping: no police near for long enough
        if (close.isEmpty()) escapeMeter += dt / 6f else escapeMeter = max(0f, escapeMeter - dt / 1.5f)
        if (escapeMeter >= 1f) {
            pursuit = false; escapeMeter = 0f; copsEvaded++
            for (c in cops) c.active = false
            cooldown = 30f; nextPatrol = time + 30f
            heatProgress = 0f
            if (mode == Mode.PURSUIT) { state = RaceState.ESCAPED; message("ESCAPED!", "You lost them", 0xFF66FF99.toInt()) }
            else message("ESCAPED", "Heat level stays at $heat", 0xFF66FF99.toInt())
        }
    }

    // ------------------------------------------------------------------ collisions

    private fun collisions() {
        for (i in 0 until cars.size) {
            val a = cars[i]; if (!a.active) continue
            for (j in i + 1 until cars.size) {
                val b = cars[j]; if (!b.active) continue
                val ds = track.delta(a.s, b.s)
                val halfL = (a.length + b.length) * 0.5f
                if (abs(ds) > halfL) continue
                val dd = b.d - a.d
                val halfW = (a.width + b.width) * 0.5f
                if (abs(dd) > halfW) continue
                val ps = halfL - abs(ds); val pd = halfW - abs(dd)
                val ma = if (a.copState == 2) 1e6f else a.spec.mass; val mb = if (b.copState == 2) 1e6f else b.spec.mass
                val wa = mb / (ma + mb); val wb = ma / (ma + mb)
                var strength: Float
                if (ps < pd * 1.4f) {
                    // Nose to tail: push apart along the road, swap some speed
                    val dir = if (ds > 0) 1f else -1f // b is ahead of a
                    a.s = track.wrap(a.s - dir * ps * wa); b.s = track.wrap(b.s + dir * ps * wb)
                    a.progress -= dir * ps * wa; b.progress += dir * ps * wb
                    val rel = (a.v - b.v) * dir
                    strength = abs(rel)
                    if (rel > 0f) {
                        val imp = rel * 1.25f
                        a.v -= dir * imp * wa; b.v += dir * imp * wb
                    }
                } else {
                    // Side by side: push apart across the road, a nudge to the heading
                    val dir = if (dd > 0) 1f else -1f
                    a.d -= dir * pd * wa; b.d += dir * pd * wb
                    strength = abs(a.v * sin(a.psi) - b.v * sin(b.psi)) + 2f
                    a.psi -= dir * 0.05f * wa; b.psi += dir * 0.05f * wb
                    a.v *= 1f - 0.01f * wa; b.v *= 1f - 0.01f * wb
                }
                a.hitCar = max(a.hitCar, strength); b.hitCar = max(b.hitCar, strength)
                if (a === player || b === player) {
                    bump = max(bump, strength)
                    val other = if (a === player) b else a
                    if (other.role == Role.COP) {
                        if (!pursuit && state == RaceState.RUNNING) startPursuit()
                        heatProgress += min(0.2f, strength * 0.02f)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ laps and positions

    private fun laps() {
        if (mode != Mode.RACE || state != RaceState.RUNNING) return
        for (c in cars) {
            if (c.role != Role.PLAYER && c.role != Role.RIVAL) continue
            if (c.finished) continue
            val lapNow = kotlin.math.floor(c.progress / track.length).toInt()
            if (lapNow > c.lap) {
                if (c.lap >= 0) {
                    val lt = time - c.lapStart
                    c.lastLap = lt
                    if (c.bestLap == 0f || lt < c.bestLap) { c.bestLap = lt; if (c === player && c.lap > 0) message("BEST LAP", fmtTime(lt), 0xFF66CCFF.toInt()) }
                }
                c.lap = lapNow
                c.lapStart = time
                if (c.lap >= laps) {
                    c.finished = true; c.finishTime = time
                    if (c === player) { state = RaceState.FINISHED; message(ordinal(position()) + " PLACE", fmtTime(time), 0xFF66FF99.toInt()) }
                } else if (c === player && c.lap == laps - 1 && laps > 1) message("FINAL LAP", "", 0xFFFFD040.toInt())
            }
        }
    }

    /** Racers in order (finished first, by time; then by distance). */
    fun standings(): List<Car> = cars.filter { it.role == Role.PLAYER || it.role == Role.RIVAL }
        .sortedWith(compareBy<Car>({ if (it.finished) 0 else 1 }, { if (it.finished) it.finishTime else -it.progress }))

    fun position(): Int = standings().indexOf(player) + 1
    val racers get() = cars.count { it.role == Role.PLAYER || it.role == Role.RIVAL }

    // ------------------------------------------------------------------ where everything is in the world

    private fun poseAll() {
        for (c in cars) {
            if (!c.active) continue
            track.pos(c.s, c.d, 0f, c.pos)
            c.yaw = track.yaw(c.s) - c.psi - c.driftVis
            c.pitch = pitchFor(track.slopeAt(c.s))
            c.roll = track.bankAt(c.s) - clamp(c.steer * c.v * 0.0016f, -0.05f, 0.05f)
        }
    }

    companion object {
        const val WHEELBASE = 2.7f
        fun fmtTime(t: Float): String { val m = (t / 60).toInt(); val s = t - m * 60; return "%d:%05.2f".format(m, s) }
        fun ordinal(n: Int) = n.toString() + when { n % 100 in 11..13 -> "TH"; n % 10 == 1 -> "ST"; n % 10 == 2 -> "ND"; n % 10 == 3 -> "RD"; else -> "TH" }
    }
}
