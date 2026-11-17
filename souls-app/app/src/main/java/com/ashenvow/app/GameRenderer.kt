package com.ashenvow.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** How a body is posed this frame (angles in degrees). */
class Pose {
    var rootPitch = 0f     // whole body tips forward (rolls) / falls
    var rootRoll = 0f      // whole body tips sideways
    var lift = 0f          // raise/lower the whole body
    var lean = 0f          // upper body leans forward
    var twist = 0f         // upper body turns
    var legL = 0f; var legR = 0f
    var kneel = 0f         // 0..1 drops to one knee
    var armRYaw = 0f; var armRPitch = 0f; var wristR = -90f
    var armLYaw = 0f; var armLPitch = 0f
    fun reset() { rootPitch = 0f; rootRoll = 0f; lift = 0f; lean = 0f; twist = 0f; legL = 0f; legR = 0f; kneel = 0f; armRYaw = 0f; armRPitch = -20f; wristR = -90f; armLYaw = 0f; armLPitch = -30f }
}

class GameRenderer(private val game: Game) : GLSurfaceView.Renderer {

    private var prog = 0
    private var aPos = 0; private var aNor = 0; private var aCol = 0
    private var uMVP = 0; private var uModel = 0; private var uTint = 0; private var uEmit = 0
    private var uLight = 0; private var uCam = 0; private var uFog = 0; private var uAlpha = 0
    private var uPL = 0; private var uPC = 0

    private var pProg = 0
    private var pPos = 0; private var pCol = 0; private var pMVP = 0; private var pSize = 0

    private lateinit var arena: Mesh
    private lateinit var cube: Mesh
    private lateinit var disc: Mesh
    private lateinit var ring: Mesh
    private lateinit var ball: Mesh

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val stack = FloatArray(16 * 24)
    private var sp = 0

    private var width = 1; private var height = 1
    @Volatile var lockX = 0f
    @Volatile var lockY = 0f
    @Volatile var lockVisible = false
    private var lastNs = 0L
    private val fog = floatArrayOf(0.055f, 0.065f, 0.095f)
    private val lightDir = normalize3(0.35f, -0.85f, 0.4f)
    private val braziers = ArrayList<FloatArray>()
    private val plPos = FloatArray(12)
    private val plCol = FloatArray(12)

    private var pts: FloatBuffer = ByteBuffer.allocateDirect(7 * 4 * 1200).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pose = Pose()

    // ------------------------------------------------------------------ setup

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(prog, "aPos"); aNor = GLES20.glGetAttribLocation(prog, "aNor"); aCol = GLES20.glGetAttribLocation(prog, "aCol")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP"); uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uTint = GLES20.glGetUniformLocation(prog, "uTint"); uEmit = GLES20.glGetUniformLocation(prog, "uEmit")
        uLight = GLES20.glGetUniformLocation(prog, "uLight"); uCam = GLES20.glGetUniformLocation(prog, "uCam")
        uFog = GLES20.glGetUniformLocation(prog, "uFog"); uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uPL = GLES20.glGetUniformLocation(prog, "uPL"); uPC = GLES20.glGetUniformLocation(prog, "uPC")
        pProg = link(PVS, PFS)
        pPos = GLES20.glGetAttribLocation(pProg, "aPos"); pCol = GLES20.glGetAttribLocation(pProg, "aCol")
        pMVP = GLES20.glGetUniformLocation(pProg, "uMVP"); pSize = GLES20.glGetUniformLocation(pProg, "uSize")

        val white = floatArrayOf(1f, 1f, 1f)
        cube = MeshBuilder().apply { box(0f, 0f, 0f, 1f, 1f, 1f, white) }.build()
        disc = MeshBuilder().apply { annulus(0f, 1f, 0f, 20, white) }.build()
        ring = MeshBuilder().apply { annulus(0.82f, 1f, 0f, 48, white) }.build()
        ball = MeshBuilder().apply { sphere(0f, 0f, 0f, 1f, 6, 10, white) }.build()
        arena = buildArena()

        GLES20.glClearColor(fog[0], fog[1], fog[2], 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        lastNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 58f, w.toFloat() / h, 0.1f, 140f)
    }

    // ------------------------------------------------------------------ the arena (built once)

    private fun buildArena(): Mesh {
        val b = MeshBuilder()
        val r = Random(11)
        // Ground far below / around
        b.annulus(0f, 60f, -0.05f, 32, rgb(0x14161C))
        // Stone floor: rings of slabs with gaps between them
        val rings = 9
        for (ri in 0 until rings) {
            val r0 = ri * 2f; val r1 = r0 + 2f
            val n = max(6, (r1 * 3.1f).toInt())
            for (i in 0 until n) {
                val a0 = (i.toFloat() / n * 2 * PI).toFloat() + 0.02f; val a1 = ((i + 1).toFloat() / n * 2 * PI).toFloat() - 0.02f
                val shade = 0.75f + r.nextFloat() * 0.35f
                val base = if (r.nextFloat() < 0.12f) rgb(0x3B3A36) else rgb(0x55534D)
                val c = floatArrayOf(base[0] * shade, base[1] * shade, base[2] * shade * 1.03f)
                val y = r.nextFloat() * 0.03f
                val ra = r0 + 0.06f; val rb = r1 - 0.06f
                if (ri == 0 && i > 0) continue
                if (ri == 0) { // centre seal
                    b.cylinder(0f, 0f, 0f, 1.9f, 0.04f, 16, rgb(0x6B5A3A))
                    continue
                }
                b.quad(arrayOf(
                    floatArrayOf(sin(a0) * ra, y, cos(a0) * ra), floatArrayOf(sin(a1) * ra, y, cos(a1) * ra),
                    floatArrayOf(sin(a1) * rb, y, cos(a1) * rb), floatArrayOf(sin(a0) * rb, y, cos(a0) * rb)
                ), floatArrayOf(0f, 1f, 0f), c)
            }
        }
        // Pillars: some whole, some broken
        Game.PILLARS.forEachIndexed { i, p ->
            val broken = i % 3 == 1
            val h = if (broken) 2.5f + r.nextFloat() * 2.5f else 9f
            val stone = rgb(0x6A6660)
            b.box(p[0], 0.3f, p[1], 2.1f, 0.6f, 2.1f, rgb(0x5A5752))
            b.cylinder(p[0], 0.6f, p[1], p[2], h, 10, stone, top = true, rTop = p[2] * 0.92f)
            if (!broken) b.box(p[0], 0.6f + h + 0.35f, p[1], 2.2f, 0.7f, 2.2f, rgb(0x5E5A55))
            else repeat(4) {
                val a = r.nextFloat() * 6.28f; val d = 1.6f + r.nextFloat() * 1.5f
                val s = 0.4f + r.nextFloat() * 0.6f
                b.box(p[0] + sin(a) * d, s / 2, p[1] + cos(a) * d, s * 1.4f, s, s, stone, r.nextFloat() * 90f, r.nextFloat() * 20f)
            }
        }
        // Braziers between the pillars: fire bowls on stands
        braziers.clear()
        for (i in 0 until 4) {
            val a = (i / 4.0 * 2 * PI).toFloat()
            val x = sin(a) * 15.3f; val z = cos(a) * 15.3f
            b.cylinder(x, 0f, z, 0.35f, 1.1f, 8, rgb(0x3E3B37))
            b.cylinder(x, 1.1f, z, 0.35f, 0.4f, 10, rgb(0x2C2A28), top = true, rTop = 0.75f)
            b.cylinder(x, 1.45f, z, 0.6f, 0.06f, 10, rgb(0xC9561A))
            braziers.add(floatArrayOf(x, 1.9f, z))
        }
        // Outer wall: tall ruined segments all around
        val segs = 36
        for (i in 0 until segs) {
            val a = (i.toFloat() / segs * 2 * PI).toFloat()
            val h = if (r.nextFloat() < 0.2f) 1.5f + r.nextFloat() * 2f else 4.5f + r.nextFloat() * 3.5f
            val x = sin(a) * 19.2f; val z = cos(a) * 19.2f
            b.box(x, h / 2, z, 3.45f, h, 1.4f, rgb(0x4A4743), (a * 180 / PI).toFloat())
            if (h > 4f && i % 2 == 0) b.box(x, h + 0.4f, z, 1.1f, 0.8f, 1.5f, rgb(0x4A4743), (a * 180 / PI).toFloat())
        }
        // Distant cliffs and ruined towers fading into the fog
        repeat(26) {
            val a = r.nextFloat() * 6.283f; val d = 32f + r.nextFloat() * 18f
            val h = 8f + r.nextFloat() * 22f; val w = 4f + r.nextFloat() * 8f
            b.box(sin(a) * d, h / 2 - 1f, cos(a) * d, w, h, w * (0.6f + r.nextFloat()), rgb(0x23262E), r.nextFloat() * 90f)
        }
        // Scattered rubble on the floor
        repeat(40) {
            val a = r.nextFloat() * 6.283f; val d = 4f + r.nextFloat() * 13f
            val s = 0.12f + r.nextFloat() * 0.3f
            b.box(sin(a) * d, s / 2, cos(a) * d, s * 1.5f, s, s * 1.2f, rgb(0x4C4A45), r.nextFloat() * 180f, r.nextFloat() * 25f)
        }
        // The moon
        b.sphere(-38f, 34f, 62f, 5f, 8, 14, rgb(0xE8EEF8))
        return b.build()
    }

    // ------------------------------------------------------------------ frame

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f)
        lastNs = now
        game.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val g = game
        val sh = g.shake * 0.25f
        val jx = (Math.random().toFloat() - 0.5f) * sh; val jy = (Math.random().toFloat() - 0.5f) * sh
        Matrix.setLookAtM(view, 0, g.camX + jx, g.camY + jy, g.camZ, g.lookX, g.lookY + jy, g.lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        // Where the boss's chest is on screen (for the lock-on marker)
        val bp = floatArrayOf(g.boss.x, g.boss.y + 3.4f, g.boss.z, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, vp, 0, bp, 0)
        if (out[3] > 0.1f) {
            lockX = (out[0] / out[3] * 0.5f + 0.5f) * width
            lockY = (1f - (out[1] / out[3] * 0.5f + 0.5f)) * height
            lockVisible = true
        } else lockVisible = false

        GLES20.glUseProgram(prog)
        GLES20.glUniform3f(uLight, lightDir[0], lightDir[1], lightDir[2])
        GLES20.glUniform3f(uCam, g.camX, g.camY, g.camZ)
        GLES20.glUniform3f(uFog, fog[0], fog[1], fog[2])
        GLES20.glUniform1f(uAlpha, 1f)
        // Warm flickering fire light from the braziers (the boss burns too in phase two)
        val t = g.time
        for (i in 0 until 4) {
            val bz = braziers.getOrNull(i) ?: floatArrayOf(0f, -50f, 0f)
            val flick = 0.85f + 0.15f * sin(t * 9f + i * 2f) * sin(t * 13.7f + i)
            if (i == 3 && g.boss.phase2 && g.boss.state != BState.DEAD) {
                plPos[i * 3] = g.boss.x; plPos[i * 3 + 1] = g.boss.y + 3f; plPos[i * 3 + 2] = g.boss.z
                plCol[i * 3] = 2.2f * flick; plCol[i * 3 + 1] = 0.8f * flick; plCol[i * 3 + 2] = 0.25f * flick
            } else {
                plPos[i * 3] = bz[0]; plPos[i * 3 + 1] = bz[1]; plPos[i * 3 + 2] = bz[2]
                plCol[i * 3] = 1.6f * flick; plCol[i * 3 + 1] = 0.75f * flick; plCol[i * 3 + 2] = 0.3f * flick
            }
        }
        GLES20.glUniform3fv(uPL, 4, plPos, 0)
        GLES20.glUniform3fv(uPC, 4, plCol, 0)

        // Arena
        sp = 0
        Matrix.setIdentityM(stack, 0)
        drawMesh(arena, 1f, 1f, 1f)

        // Blob shadows under the fighters
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        shadow(g.player.x, g.player.z, 0.6f, 0.5f)
        if (g.boss.state != BState.DEAD || g.boss.t < 3f) shadow(g.boss.x, g.boss.z, 1.9f * (1f - min(0.6f, g.boss.y / 10f)), 0.55f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        drawKnight(g.player)
        drawBoss(g)
        drawEffects(g)
        drawParticles(g)
    }

    private fun shadow(x: Float, z: Float, r: Float, a: Float) {
        GLES20.glUniform1f(uAlpha, a)
        Matrix.setIdentityM(stack, 0); sp = 0
        Matrix.translateM(stack, 0, x, 0.04f, z)
        Matrix.scaleM(stack, 0, r, 1f, r)
        drawMesh(disc, 0f, 0f, 0f)
        GLES20.glUniform1f(uAlpha, 1f)
    }

    // ------------------------------------------------------------------ matrix stack helpers

    private fun push() { System.arraycopy(stack, sp * 16, stack, (sp + 1) * 16, 16); sp++ }
    private fun pop() { sp-- }
    private fun tr(x: Float, y: Float, z: Float) = Matrix.translateM(stack, sp * 16, x, y, z)
    private fun rot(deg: Float, x: Float, y: Float, z: Float) { if (deg != 0f) Matrix.rotateM(stack, sp * 16, deg, x, y, z) }
    private fun scl(x: Float, y: Float, z: Float) = Matrix.scaleM(stack, sp * 16, x, y, z)

    private var emitR = 0f; private var emitG = 0f; private var emitB = 0f

    private fun drawMesh(m: Mesh, r: Float, g: Float, b: Float) {
        Matrix.multiplyMM(mvp, 0, vp, 0, stack, sp * 16)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, stack, sp * 16)
        GLES20.glUniform3f(uTint, r, g, b)
        GLES20.glUniform3f(uEmit, emitR, emitG, emitB)
        m.draw(aPos, aNor, aCol)
    }

    /** A box part: centred at (x, y, z) in the current frame, size sx·sy·sz. */
    private fun part(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, c: FloatArray) {
        push(); tr(x, y, z); scl(sx, sy, sz); drawMesh(cube, c[0], c[1], c[2]); pop()
    }

    // ------------------------------------------------------------------ characters

    private val steel = rgb(0x8C96A6)
    private val steelDark = rgb(0x4E5664)
    private val cloth = rgb(0x6E1E1E)
    private val leather = rgb(0x4A3524)
    private val gold = rgb(0xB8913E)
    private val blade = rgb(0xD5DCE6)

    private fun deg(rad: Float) = (rad * 180f / PI).toFloat()
    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun knightPose(p: Player) {
        val q = pose; q.reset()
        val walk = sin(p.walkPhase * 2f) * min(1f, p.speedNow / 5f)
        q.legL = walk * 35f; q.legR = -walk * 35f
        q.armLPitch = -30f - walk * 12f
        q.armRPitch = -25f + walk * 15f
        q.wristR = -70f
        when (p.state) {
            PState.ATTACK -> {
                val len = if (p.combo == 2) 0.72f else 0.52f
                val k = p.t / len
                when (p.combo) {
                    0, 1 -> {
                        // Horizontal slash: wind to one side, cut across to the other
                        val from = if (p.combo == 0) -110f else 80f
                        val to = if (p.combo == 0) 80f else -110f
                        val swing = if (k < 0.3f) ease(k / 0.3f) * 0.15f else 0.15f + ease((k - 0.3f) / 0.25f) * 0.85f
                        q.armRYaw = lerp(from, to, swing); q.armRPitch = -88f; q.wristR = -8f
                        q.twist = lerp(-25f, 25f, swing) * (if (p.combo == 0) 1f else -1f)
                        q.lean = 10f
                    }
                    else -> {
                        // Overhead finisher
                        val up = ease(k / 0.42f)
                        val down = ease((k - 0.42f) / 0.18f)
                        q.armRPitch = lerp(lerp(-30f, -175f, up), -45f, down); q.wristR = lerp(-90f, -15f, down)
                        q.lean = lerp(-8f, 20f, down)
                    }
                }
            }
            PState.HEAVY -> {
                val up = ease(p.t / 0.48f)
                val down = ease((p.t - 0.5f) / 0.14f)
                q.armRPitch = lerp(lerp(-25f, -190f, up), -40f, down); q.wristR = lerp(-90f, -10f, down)
                q.armLPitch = lerp(-30f, -150f, up * (1f - down))
                q.lean = lerp(-12f * up, 28f, down)
                q.lift = -0.12f * down
            }
            PState.ROLL -> {
                val k = p.t / ROLL_TIME
                q.rootPitch = 360f * ease(k)
                q.lift = -0.45f * sin(k * PI.toFloat())
                q.legL = -60f; q.legR = -60f; q.armRPitch = -60f; q.armLPitch = -60f
            }
            PState.HEAL -> {
                val k = ease(p.t / 0.5f) * (1f - ease((p.t - 0.85f) / 0.3f))
                q.armLPitch = lerp(-30f, -130f, k); q.armLYaw = 30f * k
            }
            PState.STAGGER -> { q.lean = -22f * (1f - p.t / 0.5f); q.armRPitch = -50f; q.armLPitch = -60f }
            PState.DEAD -> { val k = ease(p.t / 0.7f); q.rootPitch = -88f * k; q.lift = -0.05f * k; q.armRPitch = -150f * k }
            PState.IDLE -> {
                if (game.locked && p.speedNow < 0.5f) { q.armRPitch = -40f; q.armLPitch = -55f }
            }
        }
    }

    private fun drawKnight(p: Player) {
        knightPose(p)
        val q = pose
        val flash = p.hurtFlash
        emitR = flash * 0.6f; emitG = flash * 0.1f; emitB = flash * 0.1f
        sp = 0; Matrix.setIdentityM(stack, 0)
        tr(p.x, 0f, p.z)
        rot(deg(p.yaw), 0f, 1f, 0f)
        drawHumanoid(q, isBoss = false)
        // Healing glow
        if (p.state == PState.HEAL) {
            emitR = 0.9f; emitG = 0.6f; emitB = 0.2f
            push(); tr(0.25f, 1.55f, 0.35f); scl(0.12f, 0.12f, 0.12f); drawMesh(ball, 1f, 0.8f, 0.4f); pop()
        }
        emitR = 0f; emitG = 0f; emitB = 0f
    }

    /** Shared body for the knight and the boss (the boss is a bigger, darker, broken knight). */
    private fun drawHumanoid(q: Pose, isBoss: Boolean) {
        val armor = if (isBoss) rgb(0x3A3D44) else steel
        val armorDk = if (isBoss) rgb(0x22252B) else steelDark
        val cape = if (isBoss) rgb(0x2B1D18) else cloth
        // Hips are the pivot for rolling and falling
        tr(0f, 0.92f + q.lift - q.kneel * 0.4f, 0f)
        rot(q.rootPitch, 1f, 0f, 0f)
        rot(q.rootRoll, 0f, 0f, 1f)
        // Legs
        for (side in intArrayOf(-1, 1)) {
            push()
            tr(side * 0.14f, 0f, 0f)
            // Kneeling: right knee down on the ground, left foot planted in front
            val a = if (side < 0) q.legR - q.kneel * 10f else q.legL + q.kneel * 80f
            rot(-a, 1f, 0f, 0f)
            part(0f, -0.24f, 0f, 0.17f, 0.48f, 0.19f, armorDk)
            push(); tr(0f, -0.48f, 0f)
            rot(q.kneel * (if (side < 0) 85f else 80f), 1f, 0f, 0f)
            part(0f, -0.2f, 0.02f, 0.16f, 0.42f, 0.18f, armor)
            part(0f, -0.41f, 0.06f, 0.17f, 0.08f, 0.26f, armorDk)
            pop()
            pop()
        }
        // Upper body
        push()
        rot(q.lean, 1f, 0f, 0f)
        rot(q.twist, 0f, 1f, 0f)
        part(0f, 0.08f, 0f, 0.42f, 0.2f, 0.26f, rgb(0x3A2E26))               // belt / tassets
        part(0f, 0.4f, 0f, 0.5f, 0.48f, 0.3f, armor)                            // chest
        part(0f, 0.42f, 0.16f, 0.36f, 0.3f, 0.04f, armorDk)                     // breastplate ridge
        // Cape hanging behind
        push(); tr(0f, 0.6f, -0.17f); rot(8f + (if (isBoss) 4f else 0f), 1f, 0f, 0f)
        part(0f, -0.42f, 0f, 0.46f, 0.9f, 0.04f, cape); pop()
        // Head / helm
        push(); tr(0f, 0.78f, 0f)
        part(0f, 0.04f, 0f, 0.27f, 0.3f, 0.29f, armor)
        if (isBoss) {
            // Horned crown and burning eyes
            part(0.13f, 0.24f, 0f, 0.05f, 0.22f, 0.05f, rgb(0x1A1A1E))
            part(-0.13f, 0.24f, 0f, 0.05f, 0.22f, 0.05f, rgb(0x1A1A1E))
            val e = eyeGlow
            val sr = emitR; val sg = emitG; val sb = emitB
            emitR = 0.6f + 1.4f * e; emitG = 0.12f + 0.25f * e; emitB = 0.05f
            part(0.06f, 0.06f, 0.145f, 0.06f, 0.03f, 0.02f, rgb(0xFF5A2A))
            part(-0.06f, 0.06f, 0.145f, 0.06f, 0.03f, 0.02f, rgb(0xFF5A2A))
            emitR = sr; emitG = sg; emitB = sb
        } else {
            part(0f, 0.06f, 0.15f, 0.2f, 0.03f, 0.02f, rgb(0x0A0A0C))           // visor slit
            part(0f, 0.22f, -0.02f, 0.04f, 0.1f, 0.22f, cloth)                    // plume
        }
        pop()
        // Shoulders
        part(-0.31f, 0.6f, 0f, 0.2f, 0.12f, 0.3f, armorDk)
        part(0.31f, 0.6f, 0f, 0.2f, 0.12f, 0.3f, armorDk)
        // Right arm + weapon (the character's right is local -X)
        push(); tr(-0.32f, 0.56f, 0f)
        rot(q.armRYaw, 0f, 1f, 0f); rot(q.armRPitch, 1f, 0f, 0f)
        part(0f, -0.2f, 0f, 0.13f, 0.42f, 0.14f, armor)
        push(); tr(0f, -0.44f, 0f); rot(q.wristR, 1f, 0f, 0f)
        part(0f, 0f, 0f, 0.13f, 0.12f, 0.13f, rgb(0x2A2622))                    // gauntlet
        if (isBoss) {
            // Huge cleaver-like greatsword
            part(0f, -0.1f, 0f, 0.06f, 0.22f, 0.06f, leather)
            part(0f, -0.24f, 0f, 0.44f, 0.06f, 0.1f, rgb(0x2B2B30))
            val sr = emitR
            if (game.boss.phase2) { emitR = sr + 0.5f; emitG = 0.15f }
            part(0f, -1.15f, 0.02f, 0.13f, 1.8f, 0.26f, rgb(0x5F636B))
            emitR = sr; emitG = 0f
        } else {
            part(0f, -0.08f, 0f, 0.04f, 0.18f, 0.04f, leather)                   // grip
            part(0f, -0.2f, 0f, 0.3f, 0.04f, 0.06f, gold)                         // crossguard
            part(0f, -0.72f, 0f, 0.025f, 0.98f, 0.08f, blade)                     // blade
        }
        pop(); pop()
        // Left arm + shield (knight) / bare claw (boss)
        push(); tr(0.32f, 0.56f, 0f)
        rot(q.armLYaw, 0f, 1f, 0f); rot(q.armLPitch, 1f, 0f, 0f)
        part(0f, -0.2f, 0f, 0.13f, 0.42f, 0.14f, armor)
        if (!isBoss) {
            push(); tr(0.1f, -0.3f, 0.05f); rot(-q.armLPitch * 0.6f, 1f, 0f, 0f)
            part(0f, 0f, 0f, 0.05f, 0.55f, 0.42f, rgb(0x5A4128))
            part(0.03f, 0f, 0f, 0.03f, 0.12f, 0.12f, gold)
            pop()
        } else part(0f, -0.48f, 0f, 0.16f, 0.14f, 0.16f, rgb(0x1E1E22))
        pop()
        pop()
    }

    private var eyeGlow = 0f

    private fun bossPose(g: Game) {
        val b = g.boss
        val q = pose; q.reset()
        val walk = sin(b.walkPhase * 1.6f) * b.moving
        q.legL = walk * 28f; q.legR = -walk * 28f
        q.armRPitch = -35f + walk * 10f; q.wristR = -60f
        q.armLPitch = -20f - walk * 10f
        val t = b.t
        when (b.state) {
            BState.INTRO -> { val k = ease(t / 1.4f); q.kneel = 1f - k; q.lean = 25f * (1f - k) }
            BState.SLAM, BState.COMBO -> {
                if (b.state == BState.COMBO && b.comboStep == 0) sweepPose(q, t, 0.6f, 0.82f)
                else {
                    val wind = if (b.state == BState.SLAM) 0.75f else 0.55f
                    val hit = if (b.state == BState.SLAM) 0.95f else 0.75f
                    val up = ease(t / wind)
                    val down = ease((t - (hit - 0.12f)) / 0.12f)
                    val rec = ease((t - hit - 0.6f) / 0.5f)
                    q.armRPitch = lerp(lerp(-30f, -185f, up), -40f, down); q.wristR = lerp(-90f, -12f, down)
                    q.armLPitch = lerp(-20f, -170f, up * (1f - down))
                    q.lean = lerp(-15f * up, 32f, down) * (1f - rec)
                    q.lift = -0.15f * down * (1f - rec)
                    if (rec > 0f) { q.armRPitch = lerp(q.armRPitch, -35f, rec); q.wristR = lerp(q.wristR, -60f, rec) }
                }
            }
            BState.SWEEP -> sweepPose(q, t, 0.7f, 0.95f)
            BState.LUNGE -> {
                val wind = ease(t / 0.6f)
                q.armRPitch = lerp(-30f, -70f, wind); q.armRYaw = lerp(0f, -50f, wind); q.wristR = lerp(-60f, 0f, wind)
                q.lean = 20f * wind; q.lift = -0.25f * wind
                if (t > 0.6f && t < 1.15f) { q.armRYaw = -10f; q.armRPitch = -90f; q.wristR = 0f; q.lean = 30f; q.lift = -0.1f }
                if (t >= 1.15f) { val r = ease((t - 1.15f) / 0.6f); q.lean = 30f * (1f - r); q.armRPitch = lerp(-90f, -35f, r); q.wristR = lerp(0f, -60f, r) }
            }
            BState.LEAP -> {
                if (t < 0.7f) { val k = ease(t / 0.7f); q.kneel = 0.6f * k; q.lean = 20f * k; q.armRPitch = lerp(-30f, -120f, k) }
                else if (t < 1.5f) { q.armRPitch = -190f; q.wristR = -90f; q.armLPitch = -160f; q.legL = -40f; q.legR = 20f }
                else { val r = ease((t - 2f) / 0.6f); q.armRPitch = lerp(-40f, -35f, r); q.wristR = lerp(-10f, -60f, r); q.lean = 35f * (1f - r); q.kneel = 0.4f * (1f - r) }
            }
            BState.STAGGER -> { val k = ease(t / 0.3f) * (1f - ease((t - 1.8f) / 0.4f)); q.kneel = k; q.lean = 35f * k; q.armRPitch = -10f; q.armLPitch = -10f }
            BState.PHASE -> { val k = ease(t / 0.8f) * (1f - ease((t - 1.9f) / 0.5f)); q.armRPitch = -40f; q.armRYaw = -60f * k; q.armLPitch = -100f * k; q.armLYaw = 60f * k; q.lean = -25f * k }
            BState.DEAD -> { val k = ease(t / 1.2f); q.kneel = k; q.lean = 45f * k; q.armRPitch = -5f; q.armLPitch = -5f; q.rootRoll = 6f * k }
            BState.IDLE -> {}
        }
    }

    private fun sweepPose(q: Pose, t: Float, wind: Float, end: Float) {
        val w = ease(t / wind)
        val s = ease((t - wind) / (end - wind))
        val rec = ease((t - end - 0.3f) / 0.5f)
        q.armRPitch = lerp(-35f, -88f, w); q.wristR = lerp(-60f, -5f, w)
        q.armRYaw = lerp(lerp(0f, -130f, w), 110f, s)
        q.twist = lerp(lerp(0f, -35f, w), 35f, s)
        q.lean = 12f * w
        if (rec > 0f) { q.armRYaw *= 1f - rec; q.twist *= 1f - rec; q.armRPitch = lerp(q.armRPitch, -35f, rec); q.wristR = lerp(q.wristR, -60f, rec) }
    }

    private fun drawBoss(g: Game) {
        val b = g.boss
        if (b.state == BState.DEAD && b.t > 4f) return
        bossPose(g)
        eyeGlow = g.bossWindup()
        val flash = b.hurtFlash
        emitR = flash * 0.5f + (if (b.phase2) 0.12f else 0f); emitG = flash * 0.2f + (if (b.phase2) 0.03f else 0f); emitB = flash * 0.15f
        if (b.state == BState.STAGGER) { emitR += 0.25f; emitG += 0.2f; emitB += 0.05f }
        sp = 0; Matrix.setIdentityM(stack, 0)
        val sink = if (b.state == BState.DEAD) max(0f, b.t - 2f) * 1.5f else 0f
        tr(b.x, b.y - sink, b.z)
        rot(deg(b.yaw), 0f, 1f, 0f)
        scl(2.7f, 2.7f, 2.7f)
        drawHumanoid(pose, isBoss = true)
        emitR = 0f; emitG = 0f; emitB = 0f
    }

    // ------------------------------------------------------------------ effects

    private fun drawEffects(g: Game) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        // Shockwave rings
        for (w in g.waves) {
            sp = 0; Matrix.setIdentityM(stack, 0)
            tr(w.x, 0.08f, w.z); scl(w.r, 1f, w.r)
            GLES20.glUniform1f(uAlpha, (1f - w.r / 11f).coerceIn(0f, 1f))
            emitR = 1.2f; emitG = 0.5f; emitB = 0.15f
            drawMesh(ring, 0f, 0f, 0f)
        }
        // Danger marker where the slam / leap will land
        val b = g.boss
        val w = g.bossWindup()
        if (w > 0f && (b.state == BState.SLAM || b.state == BState.LEAP || (b.state == BState.COMBO && b.comboStep == 1))) {
            val hx: Float; val hz: Float; val r: Float
            if (b.state == BState.LEAP) { hx = b.leapToX; hz = b.leapToZ; r = 2.4f } else { hx = b.x + sin(b.yaw) * 3.2f; hz = b.z + cos(b.yaw) * 3.2f; r = 2.0f }
            sp = 0; Matrix.setIdentityM(stack, 0)
            tr(hx, 0.07f, hz); scl(r, 1f, r)
            GLES20.glUniform1f(uAlpha, 0.15f + 0.35f * w)
            emitR = 0.9f; emitG = 0.15f; emitB = 0.05f
            drawMesh(ring, 0f, 0f, 0f)
        }
        // Brazier flames
        val t = g.time
        for ((i, bz) in braziers.withIndex()) {
            for (k in 0 until 3) {
                sp = 0; Matrix.setIdentityM(stack, 0)
                val fl = 0.8f + 0.25f * sin(t * (8f + k * 3f) + i + k)
                tr(bz[0] + sin(t * 3f + k) * 0.06f, 1.55f + k * 0.18f * fl, bz[2] + cos(t * 2.6f + k) * 0.06f)
                val s = (0.42f - k * 0.11f) * fl
                scl(s, s * 1.6f, s)
                GLES20.glUniform1f(uAlpha, 0.55f)
                emitR = 1.3f; emitG = 0.45f + k * 0.15f; emitB = 0.08f
                drawMesh(ball, 0f, 0f, 0f)
            }
        }
        emitR = 0f; emitG = 0f; emitB = 0f
        GLES20.glUniform1f(uAlpha, 1f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawParticles(g: Game) {
        val list = g.sparks
        val n = min(list.size, 1200)
        if (n == 0) return
        pts.clear()
        for (i in 0 until n) {
            val s = list[i]
            pts.put(s.x).put(s.y).put(s.z)
            val a = min(1f, s.life * 2f)
            if (s.ember) pts.put(1f).put(0.45f).put(0.12f).put(a) else pts.put(1f).put(0.85f).put(0.5f).put(a)
        }
        pts.position(0)
        GLES20.glUseProgram(pProg)
        GLES20.glUniformMatrix4fv(pMVP, 1, false, vp, 0)
        GLES20.glUniform1f(pSize, height * 0.012f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glEnableVertexAttribArray(pPos)
        pts.position(0)
        GLES20.glVertexAttribPointer(pPos, 3, GLES20.GL_FLOAT, false, 28, pts)
        GLES20.glEnableVertexAttribArray(pCol)
        pts.position(3)
        GLES20.glVertexAttribPointer(pCol, 4, GLES20.GL_FLOAT, false, 28, pts)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(pPos)
        GLES20.glDisableVertexAttribArray(pCol)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ------------------------------------------------------------------ shaders

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        const val VS = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNor;
            attribute vec3 aCol;
            varying vec3 vCol;
            varying vec3 vNor;
            varying vec3 vWorld;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vNor = (uModel * vec4(aNor, 0.0)).xyz;
                vCol = aCol;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        const val FS = """
            precision mediump float;
            uniform vec3 uTint;
            uniform vec3 uEmit;
            uniform vec3 uLight;
            uniform vec3 uCam;
            uniform vec3 uFog;
            uniform float uAlpha;
            uniform vec3 uPL[4];
            uniform vec3 uPC[4];
            varying vec3 vCol;
            varying vec3 vNor;
            varying vec3 vWorld;
            void main() {
                vec3 n = normalize(vNor);
                vec3 base = vCol * uTint;
                float dif = max(dot(n, -uLight), 0.0);
                vec3 c = base * (vec3(0.16, 0.18, 0.25) + vec3(0.55, 0.6, 0.78) * dif);
                for (int i = 0; i < 4; i++) {
                    vec3 L = uPL[i] - vWorld;
                    float d = length(L);
                    c += base * uPC[i] * max(dot(n, L / d), 0.0) / (1.0 + d * d * 0.06);
                }
                c += uEmit;
                float f = clamp((length(vWorld - uCam) - 14.0) / 40.0, 0.0, 1.0);
                c = mix(c, uFog, f);
                gl_FragColor = vec4(c, uAlpha);
            }
        """
        const val PVS = """
            uniform mat4 uMVP;
            uniform float uSize;
            attribute vec3 aPos;
            attribute vec4 aCol;
            varying vec4 vCol;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                gl_PointSize = max(1.5, uSize * 4.0 / gl_Position.w);
                vCol = aCol;
            }
        """
        const val PFS = """
            precision mediump float;
            varying vec4 vCol;
            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                float a = 1.0 - smoothstep(0.2, 0.5, length(d));
                gl_FragColor = vec4(vCol.rgb, vCol.a * a);
            }
        """
    }
}
