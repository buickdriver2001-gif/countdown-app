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
import kotlin.math.sqrt
import kotlin.random.Random

/** How a body is posed this frame (angles in degrees). */
class Pose {
    var rootPitch = 0f     // whole body tips forward (rolls) / falls
    var rootRoll = 0f      // whole body tips sideways
    var lift = 0f          // raise/lower the whole body
    var lean = 0f          // upper body leans forward
    var twist = 0f         // upper body turns
    var sway = 0f          // upper body tips side to side
    var headPitch = 0f
    var legL = 0f; var legR = 0f       // thighs swing forward (+)
    var kneeL = 0f; var kneeR = 0f     // knees bend (shin goes back)
    var kneel = 0f         // 0..1 drops to one knee
    var armRYaw = 0f; var armRPitch = 0f; var elbowR = 0f; var wristR = -90f
    var armLYaw = 0f; var armLPitch = 0f; var elbowL = 0f
    /** Turns the shield round the forearm so its face points forward when guarding. */
    var shieldRoll = 0f
    /** Moves this pose part of the way toward [o] (k = 0..1): removes pops between moves. */
    fun blendToward(o: Pose, k: Float) {
        fun l(a: Float, b: Float) = a + (b - a) * k
        var dp = (o.rootPitch - rootPitch) % 360f
        if (dp > 180f) dp -= 360f
        if (dp < -180f) dp += 360f
        rootPitch += dp * k
        rootRoll = l(rootRoll, o.rootRoll); lift = l(lift, o.lift); lean = l(lean, o.lean); twist = l(twist, o.twist)
        sway = l(sway, o.sway); headPitch = l(headPitch, o.headPitch)
        legL = l(legL, o.legL); legR = l(legR, o.legR); kneeL = l(kneeL, o.kneeL); kneeR = l(kneeR, o.kneeR); kneel = l(kneel, o.kneel)
        armRYaw = l(armRYaw, o.armRYaw); armRPitch = l(armRPitch, o.armRPitch); elbowR = l(elbowR, o.elbowR); wristR = l(wristR, o.wristR)
        armLYaw = l(armLYaw, o.armLYaw); armLPitch = l(armLPitch, o.armLPitch); elbowL = l(elbowL, o.elbowL)
        shieldRoll = l(shieldRoll, o.shieldRoll)
    }

    fun copyFrom(o: Pose) { blendToward(o, 1f); rootPitch = o.rootPitch }

    fun reset() {
        rootPitch = 0f; rootRoll = 0f; lift = 0f; lean = 0f; twist = 0f; sway = 0f; headPitch = 0f
        legL = 0f; legR = 0f; kneeL = 5f; kneeR = 5f; kneel = 0f
        armRYaw = 0f; armRPitch = -20f; elbowR = 20f; wristR = -70f
        armLYaw = 0f; armLPitch = -30f; elbowL = 40f; shieldRoll = 0f
    }
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
    @Volatile var firstFrameDone = false
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
    private val shownPlayer = Pose()
    private val shownBoss = Pose()
    private var poseInit = false
    private var frameDt = 0f
    private var texId = 0
    private var uTex = 0; private var uMat = 0; private var uLocal = 0; private var uTexScale = 0; private var uSpec = 0; private var uTexK = 0

    // ------------------------------------------------------------------ setup

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(prog, "aPos"); aNor = GLES20.glGetAttribLocation(prog, "aNor"); aCol = GLES20.glGetAttribLocation(prog, "aCol")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP"); uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uTint = GLES20.glGetUniformLocation(prog, "uTint"); uEmit = GLES20.glGetUniformLocation(prog, "uEmit")
        uLight = GLES20.glGetUniformLocation(prog, "uLight"); uCam = GLES20.glGetUniformLocation(prog, "uCam")
        uFog = GLES20.glGetUniformLocation(prog, "uFog"); uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uPL = GLES20.glGetUniformLocation(prog, "uPL"); uPC = GLES20.glGetUniformLocation(prog, "uPC")
        uTex = GLES20.glGetUniformLocation(prog, "uTex"); uMat = GLES20.glGetUniformLocation(prog, "uMat")
        uLocal = GLES20.glGetUniformLocation(prog, "uLocal"); uTexScale = GLES20.glGetUniformLocation(prog, "uTexScale")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec"); uTexK = GLES20.glGetUniformLocation(prog, "uTexK")
        // Stone / metal / cloth textures, made in code
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val bmp = android.graphics.Bitmap.createBitmap(TextureGen.build(), TextureGen.SIZE, TextureGen.SIZE, android.graphics.Bitmap.Config.ARGB_8888)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        poseInit = false
        curMat = -1; curLocal = false
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
        val stone = rgb(0x6A6660)
        val stoneDk = rgb(0x4A4743)
        fun sh(c: FloatArray, k: Float) = floatArrayOf(c[0] * k, c[1] * k, c[2] * k)
        // Ground far below / around
        b.annulus(0f, 70f, -0.05f, 32, rgb(0x14161C))
        // Stone floor: rings of slabs with gaps, some cracked, some mossy
        for (ri in 1 until 9) {
            val r0 = ri * 2f; val r1 = r0 + 2f
            val n = max(6, (r1 * 3.1f).toInt())
            for (i in 0 until n) {
                val a0 = (i.toFloat() / n * 2 * PI).toFloat() + 0.02f; val a1 = ((i + 1).toFloat() / n * 2 * PI).toFloat() - 0.02f
                val shade = 0.75f + r.nextFloat() * 0.35f
                val roll = r.nextFloat()
                val base = when { roll < 0.1f -> rgb(0x3B3A36); roll < 0.2f -> rgb(0x4A5440); else -> rgb(0x55534D) }
                val c = floatArrayOf(base[0] * shade, base[1] * shade, base[2] * shade * 1.03f)
                val y = r.nextFloat() * 0.035f
                val ra = r0 + 0.06f; val rb = r1 - 0.06f
                b.quad(arrayOf(
                    floatArrayOf(sin(a0) * ra, y, cos(a0) * ra), floatArrayOf(sin(a1) * ra, y, cos(a1) * ra),
                    floatArrayOf(sin(a1) * rb, y, cos(a1) * rb), floatArrayOf(sin(a0) * rb, y, cos(a0) * rb)
                ), floatArrayOf(0f, 1f, 0f), c)
                if (r.nextFloat() < 0.18f) { // a crack across the slab
                    val am = (a0 + a1) / 2; val rm = (ra + rb) / 2
                    b.box(sin(am) * rm, y + 0.006f, cos(am) * rm, 0.05f, 0.01f, 1.4f, rgb(0x1E1D1B), (am * 180 / PI).toFloat() + r.nextFloat() * 60f - 30f)
                }
            }
        }
        // Centre: a worn seal with rings carved into it
        b.cylinder(0f, 0f, 0f, 2f, 0.05f, 24, rgb(0x5B4E36))
        b.annulus(1.55f, 1.7f, 0.055f, 24, rgb(0x8A7444))
        b.annulus(0.7f, 0.8f, 0.055f, 24, rgb(0x8A7444))
        for (i in 0 until 8) { val a = (i / 8.0 * 2 * PI).toFloat(); b.box(sin(a) * 1.18f, 0.058f, cos(a) * 1.18f, 0.08f, 0.01f, 0.7f, rgb(0x8A7444), (a * 180 / PI).toFloat()) }
        // Low curb around the fighting floor
        for (i in 0 until 48) {
            val a = (i / 48.0 * 2 * PI).toFloat()
            b.box(sin(a) * 17.5f, 0.2f, cos(a) * 17.5f, 2.2f, 0.4f, 0.7f, sh(stoneDk, 0.9f + r.nextFloat() * 0.2f), (a * 180 / PI).toFloat())
        }

        // Pillars: fluted columns on stepped plinths; some whole, some broken
        val whole = BooleanArray(Game.PILLARS.size)
        Game.PILLARS.forEachIndexed { i, p ->
            val broken = i % 3 == 1
            whole[i] = !broken
            val h = if (broken) 2.5f + r.nextFloat() * 2.5f else 9f
            b.box(p[0], 0.2f, p[1], 2.4f, 0.4f, 2.4f, rgb(0x55524D))
            b.box(p[0], 0.5f, p[1], 2f, 0.3f, 2f, rgb(0x5E5A55))
            b.cylinder(p[0], 0.65f, p[1], p[2] * 1.12f, 0.25f, 14, rgb(0x5E5A55))
            b.cylinder(p[0], 0.9f, p[1], p[2], h - 0.3f, 14, stone, top = true, rTop = p[2] * 0.92f)
            // Fluting: shallow grooves up the shaft
            for (f in 0 until 7) {
                val a = (f / 7.0 * 2 * PI).toFloat() + 0.2f
                b.box(p[0] + sin(a) * p[2] * 0.97f, 0.9f + (h - 0.3f) / 2, p[1] + cos(a) * p[2] * 0.97f, 0.1f, h - 0.5f, 0.08f, sh(stone, 0.7f), (a * 180 / PI).toFloat())
            }
            // Bands
            for (bh in floatArrayOf(2.6f, 5.8f)) if (bh < h) b.cylinder(p[0], bh, p[1], p[2] * 1.06f, 0.18f, 14, rgb(0x57534E))
            if (!broken) {
                b.cylinder(p[0], 0.6f + h, p[1], p[2] * 0.95f, 0.3f, 14, rgb(0x5E5A55), top = true, rTop = p[2] * 1.25f)
                b.box(p[0], 0.6f + h + 0.5f, p[1], 2.3f, 0.45f, 2.3f, rgb(0x5E5A55))
            } else {
                // Jagged broken top and chunks around the base
                b.box(p[0] + 0.2f, 0.9f + h - 0.1f, p[1], 0.9f, 0.5f, 0.7f, stone, r.nextFloat() * 90f, 18f)
                repeat(5) {
                    val a = r.nextFloat() * 6.28f; val d = 1.5f + r.nextFloat() * 1.8f
                    val s = 0.4f + r.nextFloat() * 0.7f
                    b.box(p[0] + sin(a) * d, s / 2, p[1] + cos(a) * d, s * 1.4f, s, s, stone, r.nextFloat() * 90f, r.nextFloat() * 20f)
                }
                // One fallen drum of the column lying on its side
                val a = r.nextFloat() * 6.28f
                b.box(p[0] + sin(a) * 2.4f, 0.75f, p[1] + cos(a) * 2.4f, 1.5f, 1.5f, 2.2f, stone, (a * 180 / PI).toFloat())
            }
        }
        // Arches between neighbouring whole pillars
        for (i in Game.PILLARS.indices) {
            val j = (i + 1) % Game.PILLARS.size
            if (!whole[i] || !whole[j]) continue
            val p = Game.PILLARS[i]; val q = Game.PILLARS[j]
            val mx = (p[0] + q[0]) / 2; val mz = (p[1] + q[1]) / 2
            val dx = q[0] - p[0]; val dz = q[1] - p[1]
            val len = sqrt(dx * dx + dz * dz)
            val yaw = (kotlin.math.atan2(dx, dz) * 180 / PI).toFloat()
            b.box(mx, 10.55f, mz, 1.3f, 0.7f, len, rgb(0x5A5651), yaw)
            // A curve of stone blocks under the beam
            val span = len - 2f
            for (k in 0..10) {
                val u = k / 10f
                val ang = (u * PI).toFloat()
                val along = -span / 2 + u * span
                val y = 6.4f + sin(ang) * 3.4f
                b.box(mx + dx / len * along, y, mz + dz / len * along, 1.1f, 0.6f, 0.9f, rgb(0x625E58), yaw, -(cos(ang) * 60f))
            }
        }

        // Braziers between the pillars: fire bowls on stands
        braziers.clear()
        for (i in 0 until 4) {
            val a = (i / 4.0 * 2 * PI).toFloat()
            val x = sin(a) * 15.3f; val z = cos(a) * 15.3f
            b.box(x, 0.15f, z, 1.1f, 0.3f, 1.1f, rgb(0x3E3B37), (a * 180 / PI).toFloat())
            b.cylinder(x, 0.3f, z, 0.3f, 0.8f, 8, rgb(0x3E3B37))
            for (leg in 0 until 3) { val la = leg * 2.094f + a; b.box(x + sin(la) * 0.35f, 0.7f, z + cos(la) * 0.35f, 0.08f, 0.8f, 0.08f, rgb(0x2C2A28), (la * 180 / PI).toFloat(), 15f) }
            b.cylinder(x, 1.1f, z, 0.35f, 0.4f, 10, rgb(0x2C2A28), top = true, rTop = 0.75f)
            b.cylinder(x, 1.45f, z, 0.6f, 0.06f, 10, rgb(0xC9561A))
            braziers.add(floatArrayOf(x, 1.9f, z))
        }

        // Kneeling stone knight statues by the wall, swords planted point-down
        for (i in 0 until 4) {
            val a = ((i + 0.5f) / 4.0 * 2 * PI).toFloat()
            val x = sin(a) * 17.9f; val z = cos(a) * 17.9f
            val yd = (a * 180 / PI).toFloat() + 180f // facing the centre
            val fx = -sin(a); val fz = -cos(a)
            val st = rgb(0x77736C)
            b.box(x, 0.6f, z, 2f, 1.2f, 2f, rgb(0x55524D), yd)
            b.box(x - fx * 0.3f, 1.65f, z - fz * 0.3f, 0.9f, 0.9f, 0.7f, st, yd)          // legs folded
            b.box(x, 2.6f, z, 1f, 1.3f, 0.6f, st, yd, 8f)                                   // body
            b.box(x + fx * 0.05f, 3.5f, z + fz * 0.05f, 0.5f, 0.55f, 0.5f, st, yd)         // helm
            b.box(x + fx * 0.75f, 2.2f, z + fz * 0.75f, 0.14f, 2.6f, 0.3f, rgb(0x8A8780), yd) // sword
            b.box(x + fx * 0.75f, 3.1f, z + fz * 0.75f, 0.7f, 0.12f, 0.14f, rgb(0x8A8780), yd) // crossguard
            b.box(x + fx * 0.45f, 3.0f, z + fz * 0.45f, 0.9f, 0.25f, 0.3f, st, yd)          // hands on the hilt
        }

        // Outer wall: tall ruined segments with buttresses, battlements, windows and banners
        val segs = 36
        for (i in 0 until segs) {
            val a = (i.toFloat() / segs * 2 * PI).toFloat()
            val yd = (a * 180 / PI).toFloat()
            val ruined = r.nextFloat() < 0.18f
            val h = if (ruined) 1.5f + r.nextFloat() * 2f else 5.5f + r.nextFloat() * 2.5f
            val x = sin(a) * 19.4f; val z = cos(a) * 19.4f
            val wc = sh(stoneDk, 0.9f + r.nextFloat() * 0.2f)
            b.box(x, h / 2, z, 3.45f, h, 1.4f, wc, yd)
            if (ruined) {
                repeat(3) { val s = 0.5f + r.nextFloat() * 0.6f; b.box(x * 0.95f + (r.nextFloat() - 0.5f) * 2f, s / 2, z * 0.95f + (r.nextFloat() - 0.5f) * 2f, s * 1.3f, s, s, wc, r.nextFloat() * 90f, r.nextFloat() * 20f) }
                continue
            }
            // Battlements along the top
            for (m in -1..1) {
                val ox = cos(a) * m * 1.15f; val oz = -sin(a) * m * 1.15f
                b.box(x + ox, h + 0.35f, z + oz, 0.7f, 0.7f, 1.4f, wc, yd)
            }
            // Buttress on the inside every other segment
            if (i % 2 == 0) {
                b.box(sin(a) * 18.5f, h * 0.4f, cos(a) * 18.5f, 0.9f, h * 0.8f, 0.8f, sh(wc, 0.9f), yd)
                b.box(sin(a) * 18.2f, 0.6f, cos(a) * 18.2f, 1.1f, 1.2f, 0.7f, sh(wc, 0.85f), yd)
            } else if (h > 6f) {
                // Narrow dark window
                b.box(sin(a) * 18.68f, h * 0.62f, cos(a) * 18.68f, 0.45f, 1.5f, 0.05f, rgb(0x0B0C10), yd)
                b.box(sin(a) * 18.68f, h * 0.62f + 0.85f, cos(a) * 18.68f, 0.6f, 0.2f, 0.06f, sh(wc, 1.1f), yd)
            }
            // Tattered banners hanging on some segments
            if (i % 6 == 3) {
                val bx = sin(a) * 18.6f; val bz = cos(a) * 18.6f
                b.box(bx, h - 0.25f, bz, 1.5f, 0.12f, 0.12f, rgb(0x3A2A1A), yd)
                b.box(bx, h - 1.9f, bz, 1.2f, 3.2f, 0.04f, rgb(0x5E1A1A), yd)
                b.box(bx, h - 1.9f, bz - 0.0f, 0.3f, 2.4f, 0.05f, rgb(0x9A7A38), yd)
                b.box(bx + cos(a) * 0.35f, h - 3.75f, bz - sin(a) * 0.35f, 0.4f, 0.6f, 0.04f, rgb(0x5E1A1A), yd)
            }
        }
        // A great gate behind the player's start
        run {
            val gz = -19.4f
            b.box(-2.6f, 4.5f, gz, 1.6f, 9f, 2f, rgb(0x46433F)); b.box(2.6f, 4.5f, gz, 1.6f, 9f, 2f, rgb(0x46433F))
            b.box(0f, 8.6f, gz, 6.8f, 1.4f, 2f, rgb(0x46433F))
            b.box(0f, 3.5f, gz - 0.2f, 3.6f, 7f, 0.3f, rgb(0x2A1E14))
            for (k in -3..3) b.box(k * 0.5f, 3.5f, gz + 0.0f, 0.08f, 7f, 0.35f, rgb(0x1A1A1C))
            b.box(-2.6f, 9.6f, gz, 1.9f, 0.8f, 2.3f, rgb(0x46433F)); b.box(2.6f, 9.6f, gz, 1.9f, 0.8f, 2.3f, rgb(0x46433F))
        }
        // Fallen warriors' swords stuck in the floor
        repeat(14) {
            val a = r.nextFloat() * 6.283f; val d = 6f + r.nextFloat() * 10.5f
            val sx = sin(a) * d; val sz = cos(a) * d
            val yd = r.nextFloat() * 360f; val tilt = 10f + r.nextFloat() * 25f
            b.box(sx, 0.55f, sz, 0.05f, 1.1f, 0.12f, rgb(0x6C7078), yd, tilt)
            b.box(sx + sin(yd * PI.toFloat() / 180) * sin(tilt * PI.toFloat() / 180) * 1.1f, 1.08f, sz + cos(yd * PI.toFloat() / 180) * sin(tilt * PI.toFloat() / 180) * 1.1f, 0.3f, 0.05f, 0.08f, rgb(0x4A3A28), yd, tilt)
        }
        // Scattered rubble and bones
        repeat(55) {
            val a = r.nextFloat() * 6.283f; val d = 3f + r.nextFloat() * 14f
            val s = 0.1f + r.nextFloat() * 0.3f
            val bone = r.nextFloat() < 0.25f
            if (bone) b.box(sin(a) * d, 0.04f, cos(a) * d, 0.07f, 0.06f, 0.5f, rgb(0xB8B09A), r.nextFloat() * 180f)
            else b.box(sin(a) * d, s / 2, cos(a) * d, s * 1.5f, s, s * 1.2f, rgb(0x4C4A45), r.nextFloat() * 180f, r.nextFloat() * 25f)
        }
        // Dead trees just outside the walls
        repeat(10) {
            val a = r.nextFloat() * 6.283f; val d = 22f + r.nextFloat() * 6f
            val tx = sin(a) * d; val tz = cos(a) * d
            val th = 6f + r.nextFloat() * 5f
            b.cylinder(tx, 0f, tz, 0.35f, th, 6, rgb(0x2A221C), top = true, rTop = 0.12f)
            repeat(4) { k ->
                val ba = r.nextFloat() * 360f
                b.box(tx, th * (0.45f + k * 0.13f), tz, 0.12f, 2.4f, 0.12f, rgb(0x2A221C), ba, 50f + r.nextFloat() * 20f)
            }
        }
        // A ruined castle on the hills, fading into the fog
        run {
            val cx = 10f; val cz = 52f
            b.box(cx, 6f, cz, 24f, 12f, 8f, rgb(0x262931))
            for (k in -2..2) {
                val tx = cx + k * 6f
                val th = 18f + (if (k == 0) 10f else r.nextFloat() * 6f)
                b.cylinder(tx, 0f, cz - 1f, 2.2f, th, 8, rgb(0x23262E))
                b.cylinder(tx, th, cz - 1f, 2.6f, 4f, 8, rgb(0x1E2128), top = true, rTop = 0.1f)
            }
        }
        // Distant cliffs
        repeat(26) {
            val a = r.nextFloat() * 6.283f; val d = 34f + r.nextFloat() * 20f
            val h = 8f + r.nextFloat() * 20f; val w = 4f + r.nextFloat() * 8f
            b.box(sin(a) * d, h / 2 - 1f, cos(a) * d, w, h, w * (0.6f + r.nextFloat()), rgb(0x23262E), r.nextFloat() * 90f)
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
        frameDt = kotlin.math.min(dt, 0.1f)
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uTexK, 0.3f)
        setMat(M_STONE, local = false)
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
        setMat(M_NONE, local = false)

        // Blob shadows under the fighters
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        shadow(g.player.x, g.player.z, 0.6f, 0.5f)
        if (g.boss.state != BState.DEAD || g.boss.t < 3f) {
            val by = g.boss.y
            // High up (sky-fall) the shadow grows as it comes down
            val sr = if (by > 8f) 1.4f + 1.2f * (1f - by / 40f) else 1.9f * (1f - min(0.6f, by / 10f))
            shadow(g.boss.x, g.boss.z, sr, if (by > 8f) 0.4f + 0.25f * sin(g.time * 10f) else 0.55f)
        }
        // Small shadows under falling stars
        for (st in g.stars) if (st.t > 0f && !st.done) shadow(st.x, st.z, 0.3f + 0.7f * (st.t / STAR_FALL), 0.35f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        drawKnight(g.player)
        drawBoss(g)
        setMat(M_NONE, local = false)
        drawEffects(g)
        drawParticles(g)
        firstFrameDone = true
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

    private var curMat = -1; private var curLocal = false

    /** Picks the surface: stone, metal, cloth, or plain (effects). */
    private fun setMat(mat: Int, local: Boolean, spec: Float = -1f) {
        val sp = if (spec >= 0f) spec else when (mat) { M_STONE -> 0.12f; M_METAL -> 1.0f; M_CLOTH -> 0.05f; else -> 0f }
        if (mat != curMat) { GLES20.glUniform1f(uMat, mat.toFloat()); curMat = mat }
        if (local != curLocal) { GLES20.glUniform1f(uLocal, if (local) 1f else 0f); curLocal = local }
        GLES20.glUniform1f(uSpec, sp)
    }

    private fun drawMesh(m: Mesh, r: Float, g: Float, b: Float) {
        Matrix.multiplyMM(mvp, 0, vp, 0, stack, sp * 16)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, stack, sp * 16)
        GLES20.glUniform3f(uTint, r, g, b)
        GLES20.glUniform3f(uEmit, emitR, emitG, emitB)
        m.draw(aPos, aNor, aCol)
    }

    /** A box part: centred at (x, y, z) in the current frame, size sx·sy·sz. */
    private fun part(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, c: FloatArray, mat: Int = M_METAL, spec: Float = -1f) {
        setMat(mat, local = true, spec = spec)
        GLES20.glUniform3f(uTexScale, sx * texDensity, sy * texDensity, sz * texDensity)
        push(); tr(x, y, z); scl(sx, sy, sz); drawMesh(cube, c[0], c[1], c[2]); pop()
    }

    /** Glowing bit (runes, gems, eyes): emissive colour on top of the material. */
    private fun glowPart(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, er: Float, eg: Float, eb: Float) {
        val sr = emitR; val sg = emitG; val sb = emitB
        emitR = sr + er; emitG = sg + eg; emitB = sb + eb
        part(x, y, z, sx, sy, sz, cBlack, M_NONE)
        emitR = sr; emitG = sg; emitB = sb
    }

    private var texDensity = 2.4f

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
    /** Fast start, soft landing: good for swings that snap through. */
    private fun snap(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).let { it * it * it }

    /**
     * A running / walking cycle. phase advances with distance travelled; s = 0 (still) .. 1 (full run).
     * Thighs swing, the knee folds as the leg comes through, arms swing against the legs,
     * the body bobs twice per stride and leans into the run.
     */
    private fun gait(q: Pose, phase: Float, s: Float, stride: Float, heavy: Boolean) {
        val sn = sin(phase); val cs = cos(phase)
        q.legL = sn * stride * s
        q.legR = -sn * stride * s
        // The knee folds most while that leg swings forward, and stays soft while planted
        q.kneeL = 8f + s * (12f + 70f * max(0f, cs))
        q.kneeR = 8f + s * (12f + 70f * max(0f, -cs))
        q.lift = -0.05f * s + 0.07f * s * kotlin.math.abs(sn) - (if (heavy) 0.02f * s else 0f)
        q.lean = (if (heavy) 8f else 14f) * s
        q.twist = -sn * 9f * s
        q.sway = sn * (if (heavy) 4f else 2f) * s
        q.rootRoll = if (heavy) sn * 2.5f * s else 0f
        q.armLPitch = -20f + sn * 40f * s
        q.armRPitch = -22f - sn * 32f * s
        q.elbowL = 30f + 45f * s
        q.elbowR = 25f + 40f * s
        q.headPitch = -q.lean * 0.5f
    }

    private fun knightPose(p: Player) {
        val q = pose; q.reset()
        val s = min(1f, p.speedNow / 5.2f)
        // Breathing while standing
        val breathe = sin(game.time * 2.2f) * (1f - s)
        gait(q, p.walkPhase * 0.85f, s, 46f, heavy = false)
        q.lift += breathe * 0.012f
        q.armLPitch += breathe * 2f
        // Guard stance when locked on and standing still: shield up, sword ready
        if (game.locked && s < 0.3f) {
            val k = 1f - s / 0.3f
            q.armLPitch = lerp(q.armLPitch, -60f, k); q.elbowL = lerp(q.elbowL, 70f, k); q.armLYaw = lerp(0f, 20f, k)
            q.armRPitch = lerp(q.armRPitch, -35f, k); q.elbowR = lerp(q.elbowR, 45f, k)
            q.kneeL += 14f * k; q.kneeR += 14f * k; q.legL += 10f * k; q.legR -= 8f * k; q.lift -= 0.04f * k
        }
        q.wristR = -80f + q.elbowR
        when (p.state) {
            PState.ATTACK -> {
                val len = if (p.combo == 2) 0.72f else 0.52f
                val k = p.t / len
                q.legL = 22f; q.legR = -18f; q.kneeL = 25f; q.kneeR = 15f; q.lift = -0.06f
                when (p.combo) {
                    0, 1 -> {
                        // Horizontal slash: gather to one side, snap across, follow through
                        val dir = if (p.combo == 0) 1f else -1f
                        val wind = ease(k / 0.3f)
                        val cut = snap((k - 0.3f) / 0.24f)
                        val yaw = lerp(lerp(-20f * dir, -115f * dir, wind), 95f * dir, cut)
                        q.armRYaw = if (dir > 0) yaw else -yaw - 15f
                        q.armRPitch = lerp(-60f, -85f, wind); q.elbowR = lerp(40f, 5f, wind); q.wristR = lerp(-60f, -10f, wind)
                        q.twist = lerp(lerp(0f, -30f, wind), 30f, cut) * dir
                        q.lean = 10f + 8f * cut
                        q.armLPitch = -45f; q.elbowL = 60f
                    }
                    else -> {
                        // Overhead finisher: rise up, then crash down with the whole body
                        val up = ease(k / 0.42f)
                        val down = snap((k - 0.42f) / 0.16f)
                        q.armRPitch = lerp(lerp(-30f, -170f, up), -40f, down); q.elbowR = lerp(lerp(30f, 40f, up), 5f, down)
                        q.wristR = lerp(-80f, -15f, down)
                        q.armLPitch = lerp(-30f, -140f, up * (1f - down)); q.elbowL = 30f
                        q.lean = lerp(-10f * up, 26f, down)
                        q.lift = lerp(0.02f * up, -0.12f, down)
                        q.kneeL = lerp(15f, 45f, down); q.kneeR = lerp(15f, 30f, down)
                    }
                }
            }
            PState.HEAVY -> {
                val up = ease(p.t / 0.48f)
                val down = snap((p.t - 0.5f) / 0.13f)
                q.armRPitch = lerp(lerp(-25f, -195f, up), -38f, down); q.elbowR = lerp(lerp(20f, 50f, up), 0f, down)
                q.wristR = lerp(-90f, -10f, down)
                q.armLPitch = lerp(-30f, -165f, up * (1f - down)); q.elbowL = lerp(40f, 50f, up)
                q.lean = lerp(-14f * up, 32f, down)
                q.lift = lerp(-0.05f * up, -0.18f, down)
                q.legL = lerp(10f, 32f, down); q.legR = lerp(-5f, -25f, down)
                q.kneeL = lerp(20f, 55f, down); q.kneeR = lerp(20f, 35f, down)
            }
            PState.ROLL -> {
                val k = p.t / ROLL_TIME
                q.rootPitch = 360f * ease(k)
                q.lift = -0.45f * sin(k * PI.toFloat())
                // Tucked up tight
                q.legL = 80f; q.legR = 70f; q.kneeL = 120f; q.kneeR = 115f
                q.armRPitch = -70f; q.armLPitch = -70f; q.elbowR = 80f; q.elbowL = 90f
                q.lean = 30f; q.headPitch = 20f
            }
            PState.HEAL -> {
                val k = ease(p.t / 0.5f) * (1f - ease((p.t - 0.85f) / 0.3f))
                q.armLPitch = lerp(q.armLPitch, -60f, k); q.armLYaw = lerp(0f, -25f, k); q.elbowL = lerp(q.elbowL, 120f, k)
                q.headPitch = -18f * k
            }
            PState.BLOCK -> {
                // Shield raised square in front of the chest, sword drawn back behind it, knees bent.
                // A blow on the shield knocks it back and rocks the body for a moment.
                val up = ease(p.t / 0.12f)
                val stun = (p.blockStun / BLOCK_STUN).coerceIn(0f, 1f)
                val jolt = stun * stun
                q.armLYaw = lerp(q.armLYaw, -40f, up); q.armLPitch = lerp(q.armLPitch, -70f, up) + 14f * jolt
                q.elbowL = lerp(q.elbowL, 105f, up) - 10f * jolt; q.shieldRoll = 50f * up
                q.armRPitch = lerp(q.armRPitch, -32f, up); q.elbowR = lerp(q.elbowR, 58f, up); q.armRYaw = lerp(q.armRYaw, 12f, up)
                q.wristR = -80f + q.elbowR
                q.kneeL += 18f * up; q.kneeR += 18f * up; q.legL += 12f * up; q.legR -= 10f * up
                q.lift -= 0.06f * up + 0.03f * jolt
                q.lean = lerp(q.lean, 10f, up) - 16f * jolt
                q.headPitch = 8f * up - 6f * jolt
                q.twist = lerp(q.twist, -8f, up)
            }
            PState.STAGGER -> {
                val k = 1f - ease(p.t / p.staggerLen)
                q.lean = -24f * k; q.headPitch = -15f * k; q.armRPitch = -55f * k - 15f; q.armLPitch = -70f * k - 15f
                q.legL = 18f * k; q.kneeL = 30f * k; q.kneeR = 20f * k; q.lift = -0.06f * k
            }
            PState.DEAD -> {
                val k = ease(p.t / 0.8f)
                q.kneeL = 60f * k; q.kneeR = 40f * k
                q.rootPitch = -85f * k; q.lift = -0.25f * k; q.armRPitch = -150f * k; q.armLPitch = -120f * k
                q.headPitch = -20f * k
            }
            PState.IDLE -> {}
        }
    }

    private fun drawKnight(p: Player) {
        knightPose(p)
        // Blend toward the new pose so nothing snaps (rolls and deaths follow exactly)
        if (!poseInit) shownPlayer.copyFrom(pose)
        else shownPlayer.blendToward(pose, 1f - kotlin.math.exp(-frameDt * 24f))
        if (p.state == PState.ROLL) shownPlayer.rootPitch = pose.rootPitch
        val q = shownPlayer
        val flash = p.hurtFlash
        emitR = flash * 0.6f; emitG = flash * 0.1f; emitB = flash * 0.1f
        sp = 0; Matrix.setIdentityM(stack, 0)
        tr(p.x, 0f, p.z)
        rot(deg(p.yaw), 0f, 1f, 0f)
        drawHumanoid(q, isBoss = false)
        // Healing glow
        if (p.state == PState.HEAL) {
            emitR = 0.9f; emitG = 0.6f; emitB = 0.2f
            push(); tr(0.1f, 1.45f, 0.38f); scl(0.12f, 0.12f, 0.12f); drawMesh(ball, 1f, 0.8f, 0.4f); pop()
        }
        emitR = 0f; emitG = 0f; emitB = 0f
    }

    // Colours (made once: no garbage every frame)
    private val cBlack = rgb(0x000000)
    private val kArmor = steel; private val kArmorDk = steelDark; private val kCape = cloth
    private val bArmor = rgb(0x3A3D44); private val bArmorDk = rgb(0x22252B); private val bCape = rgb(0x2B1D18)
    private val cBelt = rgb(0x3A2E26); private val cMail = rgb(0x6A707A); private val cBossMail = rgb(0x2E3036)
    private val cBossCrest = rgb(0x55331C); private val cHorn = rgb(0x1A1A1E); private val cCrown = rgb(0x2A2A30)
    private val cVisor = rgb(0x0A0A0C); private val cGauntlet = rgb(0x2A2622); private val cShield = rgb(0x5A4128)
    private val cShieldRim = rgb(0x7A7F88); private val cClaw = rgb(0x1E1E22); private val cIron = rgb(0x2B2B30)
    private val cBossBlade = rgb(0x5F636B); private val cFuller = rgb(0x34373E); private val cChain = rgb(0x45474D)
    private val cPommel = rgb(0x3A3A40)

    /** Shared body for the knight and the boss (the boss is a bigger, darker, broken knight). */
    private fun drawHumanoid(q: Pose, isBoss: Boolean) {
        val armor = if (isBoss) bArmor else kArmor
        val armorDk = if (isBoss) bArmorDk else kArmorDk
        val cape = if (isBoss) bCape else kCape
        val time = game.time
        val wind = sin(time * 2.3f) * 4f + sin(time * 3.7f) * 2f
        // Hips are the pivot for rolling and falling
        tr(0f, 0.92f + q.lift - q.kneel * 0.42f, 0f)
        rot(q.rootPitch, 1f, 0f, 0f)
        rot(q.rootRoll, 0f, 0f, 1f)
        // Legs: thigh, knee, shin, boot
        for (side in intArrayOf(-1, 1)) {
            push()
            tr(side * 0.13f, 0f, 0f)
            val right = side < 0
            val a = if (right) q.legR - q.kneel * 10f else q.legL + q.kneel * 80f
            rot(-a, 1f, 0f, 0f)
            part(0f, -0.23f, 0f, 0.18f, 0.48f, 0.2f, armorDk, M_CLOTH)               // padded thigh
            part(0f, -0.2f, 0.07f, 0.16f, 0.36f, 0.06f, armor)                         // thigh plate
            push(); tr(0f, -0.47f, 0f)
            part(0f, 0f, 0.06f, 0.16f, 0.13f, 0.1f, armor)                              // knee cop
            part(0f, 0.02f, 0.11f, 0.06f, 0.06f, 0.03f, armorDk)
            val kneeA = (if (right) q.kneeR else q.kneeL) + q.kneel * (if (right) 85f else 80f)
            rot(kneeA, 1f, 0f, 0f)
            part(0f, -0.2f, 0f, 0.16f, 0.42f, 0.18f, armor)                             // greave
            part(0f, -0.18f, 0.08f, 0.1f, 0.34f, 0.04f, armorDk)                        // shin ridge
            // Boot stays roughly level with the ground
            push(); tr(0f, -0.41f, 0f)
            rot((a - kneeA) * 0.8f, 1f, 0f, 0f)
            part(0f, -0.02f, 0.05f, 0.17f, 0.09f, 0.28f, armorDk)
            part(0f, -0.02f, 0.18f, 0.15f, 0.07f, 0.06f, armor)                         // toe cap
            pop()
            pop()
            pop()
        }
        // Mail skirt hanging from the belt
        part(0f, -0.1f, 0f, 0.44f, 0.24f, 0.3f, if (isBoss) cBossMail else cMail, M_CLOTH, 0.5f)
        // Upper body
        push()
        rot(q.lean, 1f, 0f, 0f)
        rot(q.twist, 0f, 1f, 0f)
        rot(q.sway, 0f, 0f, 1f)
        part(0f, 0.08f, 0f, 0.44f, 0.12f, 0.28f, cBelt, M_CLOTH)                       // belt
        part(0f, 0.08f, 0.145f, 0.08f, 0.08f, 0.02f, if (isBoss) cIron else gold)       // buckle
        part(0.17f, 0.02f, 0.12f, 0.08f, 0.1f, 0.06f, leather, M_CLOTH)                 // pouch
        part(0f, -0.04f, 0.12f, 0.3f, 0.16f, 0.05f, armorDk)                            // front tasset
        part(0f, 0.4f, 0f, 0.5f, 0.46f, 0.3f, armor)                                    // chest
        part(0f, 0.43f, 0.16f, 0.38f, 0.32f, 0.04f, armorDk)                            // breastplate
        part(0f, 0.43f, 0.18f, 0.04f, 0.3f, 0.02f, armor)                               // centre ridge
        part(0f, 0.25f, 0.155f, 0.42f, 0.05f, 0.03f, armor)                             // lower lame
        part(0f, 0.57f, 0.17f, 0.12f, 0.07f, 0.03f, if (isBoss) cBossCrest else gold)   // crest
        part(0f, 0.42f, -0.16f, 0.4f, 0.36f, 0.03f, armorDk)                            // backplate
        if (isBoss && game.boss.phase2) {
            // Burning cracks across the chest
            val gl = 0.8f + 0.3f * sin(time * 6f)
            glowPart(0.06f, 0.46f, 0.185f, 0.02f, 0.22f, 0.01f, 1.4f * gl, 0.45f * gl, 0.08f)
            glowPart(-0.08f, 0.38f, 0.185f, 0.14f, 0.02f, 0.01f, 1.4f * gl, 0.45f * gl, 0.08f)
            glowPart(-0.03f, 0.52f, 0.185f, 0.02f, 0.12f, 0.01f, 1.4f * gl, 0.45f * gl, 0.08f)
        }
        // Cape: hinged strips that flow back when moving and stir in the wind
        val capeSwing = 8f + q.lean * 0.4f + kotlin.math.abs(q.legL) * 0.3f
        val strips = if (isBoss) 5 else 3
        for (k in 0 until strips) {
            val off = (k - (strips - 1) / 2f) * (0.48f / strips)
            val len = if (isBoss) 0.75f + ((k * 37) % 5) * 0.08f else 0.9f
            push(); tr(off, 0.62f, -0.18f)
            rot(capeSwing + wind * (0.6f + k * 0.15f), 1f, 0f, 0f)
            part(0f, -len * 0.25f, 0f, 0.48f / strips + 0.005f, len * 0.5f, 0.035f, cape, M_CLOTH)
            push(); tr(0f, -len * 0.5f, 0f); rot(4f + wind * 0.8f + q.lean * 0.2f, 1f, 0f, 0f)
            part(0f, -len * 0.25f, 0f, 0.48f / strips - 0.01f, len * 0.5f, 0.03f, cape, M_CLOTH)
            pop(); pop()
        }
        // Head / helm
        push(); tr(0f, 0.72f, 0f); rot(q.headPitch, 1f, 0f, 0f)
        part(0f, 0.04f, 0f, 0.1f, 0.08f, 0.1f, if (isBoss) cBossMail else cMail, M_CLOTH, 0.5f) // mail collar
        part(0f, 0.15f, 0f, 0.27f, 0.3f, 0.29f, armor)
        part(0f, 0.24f, 0.13f, 0.28f, 0.05f, 0.06f, armorDk)                           // brow
        part(0.13f, 0.1f, 0.06f, 0.04f, 0.16f, 0.18f, armorDk)                          // cheek plates
        part(-0.13f, 0.1f, 0.06f, 0.04f, 0.16f, 0.18f, armorDk)
        if (isBoss) {
            // Spiked crown and burning eyes
            part(0f, 0.33f, 0.02f, 0.26f, 0.05f, 0.26f, cCrown)
            for (k in -2..2) part(k * 0.055f, 0.4f + (2 - kotlin.math.abs(k)) * 0.03f, 0.12f, 0.03f, 0.12f + (2 - kotlin.math.abs(k)) * 0.05f, 0.03f, cHorn)
            push(); tr(0.15f, 0.32f, 0f); rot(-25f, 0f, 0f, 1f); part(0f, 0.12f, 0f, 0.05f, 0.26f, 0.05f, cHorn); pop()
            push(); tr(-0.15f, 0.32f, 0f); rot(25f, 0f, 0f, 1f); part(0f, 0.12f, 0f, 0.05f, 0.26f, 0.05f, cHorn); pop()
            val e = eyeGlow
            glowPart(0.06f, 0.17f, 0.146f, 0.06f, 0.03f, 0.02f, 0.6f + 1.6f * e, 0.12f + 0.3f * e, 0.05f)
            glowPart(-0.06f, 0.17f, 0.146f, 0.06f, 0.03f, 0.02f, 0.6f + 1.6f * e, 0.12f + 0.3f * e, 0.05f)
        } else {
            part(0f, 0.17f, 0.15f, 0.2f, 0.03f, 0.02f, cVisor)                          // visor slit
            part(0f, 0.09f, 0.15f, 0.03f, 0.1f, 0.02f, cVisor)
            for (k in -1..1) part(k * 0.05f, 0.06f, 0.15f, 0.015f, 0.04f, 0.02f, cVisor) // breaths
            part(0f, 0.32f, -0.02f, 0.03f, 0.05f, 0.26f, armor)                           // comb
            part(0f, 0.36f, -0.06f, 0.04f, 0.12f, 0.22f, cloth, M_CLOTH)                  // plume
        }
        pop()
        // Layered pauldrons
        for (side in intArrayOf(-1, 1)) {
            part(side * 0.31f, 0.62f, 0f, 0.22f, 0.12f, 0.32f, armorDk)
            part(side * 0.34f, 0.55f, 0f, 0.2f, 0.07f, 0.3f, armor)
            part(side * 0.36f, 0.49f, 0f, 0.17f, 0.06f, 0.27f, armorDk)
            if (isBoss) {
                push(); tr(side * 0.36f, 0.68f, 0.05f); rot(-side * 30f, 0f, 0f, 1f); part(0f, 0.09f, 0f, 0.04f, 0.2f, 0.04f, cHorn); pop()
                push(); tr(side * 0.3f, 0.68f, -0.08f); rot(-side * 15f, 0f, 0f, 1f); part(0f, 0.07f, 0f, 0.035f, 0.15f, 0.035f, cHorn); pop()
            }
        }
        // Right arm + weapon (the character's right is local -X)
        push(); tr(-0.32f, 0.56f, 0f)
        rot(q.armRYaw, 0f, 1f, 0f); rot(q.armRPitch, 1f, 0f, 0f)
        part(0f, -0.12f, 0f, 0.13f, 0.25f, 0.14f, armor)
        push(); tr(0f, -0.24f, 0f)
        part(0f, 0f, 0.03f, 0.12f, 0.1f, 0.1f, armorDk)                                 // elbow cop
        rot(-q.elbowR, 1f, 0f, 0f)
        part(0f, -0.1f, 0f, 0.12f, 0.22f, 0.13f, armorDk)                               // vambrace
        part(0f, -0.17f, 0f, 0.14f, 0.05f, 0.15f, armor)
        push(); tr(0f, -0.22f, 0f); rot(q.wristR, 1f, 0f, 0f)
        part(0f, 0f, 0f, 0.13f, 0.11f, 0.13f, cGauntlet)                                 // gauntlet
        if (isBoss) drawGreatsword() else drawKnightSword()
        pop(); pop(); pop()
        // Left arm + shield (knight) / clawed hand (boss)
        push(); tr(0.32f, 0.56f, 0f)
        rot(q.armLYaw, 0f, 1f, 0f); rot(q.armLPitch, 1f, 0f, 0f)
        part(0f, -0.12f, 0f, 0.13f, 0.25f, 0.14f, armor)
        push(); tr(0f, -0.24f, 0f)
        part(0f, 0f, 0.03f, 0.12f, 0.1f, 0.1f, armorDk)
        rot(-q.elbowL, 1f, 0f, 0f)
        part(0f, -0.1f, 0f, 0.12f, 0.22f, 0.13f, armorDk)
        if (!isBoss) {
            // Heater shield: wood face, iron rim and cross, gold boss
            push(); rot(q.shieldRoll, 0f, 1f, 0f); tr(0.09f, -0.12f, 0.02f)
            part(0f, 0f, 0f, 0.05f, 0.56f, 0.44f, cShield, M_CLOTH, 0.1f)
            part(0f, -0.33f, 0f, 0.05f, 0.12f, 0.24f, cShield, M_CLOTH, 0.1f)
            part(0.02f, 0f, 0f, 0.03f, 0.6f, 0.05f, cShieldRim)
            part(0.02f, 0f, 0f, 0.03f, 0.05f, 0.48f, cShieldRim)
            part(0.02f, 0.27f, 0f, 0.03f, 0.04f, 0.46f, cShieldRim)
            part(0.02f, 0f, 0.21f, 0.03f, 0.56f, 0.03f, cShieldRim)
            part(0.02f, 0f, -0.21f, 0.03f, 0.56f, 0.03f, cShieldRim)
            part(0.045f, 0f, 0f, 0.03f, 0.11f, 0.11f, gold)
            pop()
        } else {
            part(0f, -0.26f, 0f, 0.16f, 0.13f, 0.16f, cClaw)
            for (k in -1..1) part(k * 0.05f, -0.37f, 0.05f, 0.03f, 0.12f, 0.03f, cClaw)
            // Chains wrapped round the wrist, swinging
            push(); tr(0f, -0.2f, 0f); rot(wind * 2f, 1f, 0f, 0f)
            for (k in 0 until 4) part(0.09f, -0.04f - k * 0.06f, 0f, 0.025f, 0.05f, 0.04f, cChain)
            pop()
        }
        pop(); pop()
        pop()
    }

    /** The knight's arming sword. */
    private fun drawKnightSword() {
        part(0f, 0.08f, 0f, 0.06f, 0.06f, 0.06f, gold)                                   // pommel
        part(0f, -0.08f, 0f, 0.04f, 0.18f, 0.04f, leather, M_CLOTH)                       // grip
        for (k in 0..2) part(0f, -0.02f - k * 0.055f, 0f, 0.046f, 0.012f, 0.046f, cIron)  // wraps
        part(0f, -0.2f, 0f, 0.32f, 0.04f, 0.06f, gold)                                     // crossguard
        part(0.17f, -0.18f, 0f, 0.04f, 0.06f, 0.06f, gold); part(-0.17f, -0.18f, 0f, 0.04f, 0.06f, 0.06f, gold)
        part(0f, -0.72f, 0f, 0.025f, 0.98f, 0.08f, blade, M_METAL, 1.6f)                   // blade
        part(0f, -0.62f, 0f, 0.03f, 0.7f, 0.02f, cFuller)                                  // fuller
        push(); tr(0f, -1.21f, 0f); rot(45f, 1f, 0f, 0f); part(0f, 0f, 0f, 0.024f, 0.056f, 0.056f, blade, M_METAL, 1.6f); pop() // tip
    }

    /** The Warden's greatsword: horned guard, burning gem, serrated back, runes in the fuller. */
    private fun drawGreatsword() {
        val p2 = game.boss.phase2
        val time = game.time
        // Spiked pommel and wrapped grip
        part(0f, 0.13f, 0f, 0.09f, 0.09f, 0.09f, cPommel)
        for (k in 0 until 4) { push(); tr(0f, 0.13f, 0f); rot(k * 90f + 45f, 0f, 1f, 0f); rot(70f, 1f, 0f, 0f); part(0f, 0.06f, 0f, 0.025f, 0.08f, 0.025f, cHorn); pop() }
        part(0f, -0.07f, 0f, 0.055f, 0.26f, 0.055f, leather, M_CLOTH)
        for (k in 0..3) part(0f, 0.04f - k * 0.07f, 0f, 0.065f, 0.018f, 0.065f, cIron)
        // Crossguard with horns curving toward the blade
        part(0f, -0.23f, 0f, 0.17f, 0.1f, 0.15f, cIron)
        part(0.17f, -0.23f, 0f, 0.22f, 0.05f, 0.09f, cIron); part(-0.17f, -0.23f, 0f, 0.22f, 0.05f, 0.09f, cIron)
        for (side in intArrayOf(-1, 1)) {
            push(); tr(side * 0.28f, -0.25f, 0f); rot(side * 35f, 0f, 0f, 1f); part(0f, -0.08f, 0f, 0.04f, 0.17f, 0.06f, cHorn); pop()
            push(); tr(side * 0.27f, -0.2f, 0f); rot(-side * 30f, 0f, 0f, 1f); part(0f, 0.05f, 0f, 0.03f, 0.1f, 0.05f, cHorn); pop()
        }
        val gemPulse = 0.6f + 0.4f * sin(time * 3f)
        glowPart(0f, -0.23f, 0.08f, 0.06f, 0.06f, 0.02f, (if (p2) 1.6f else 0.9f) * gemPulse, (if (p2) 0.5f else 0.12f) * gemPulse, 0.05f)
        glowPart(0f, -0.23f, -0.08f, 0.06f, 0.06f, 0.02f, (if (p2) 1.6f else 0.9f) * gemPulse, (if (p2) 0.5f else 0.12f) * gemPulse, 0.05f)
        // The blade: a thick slab, darker fuller on both faces
        part(0f, -1.17f, 0.02f, 0.13f, 1.8f, 0.28f, cBossBlade, M_METAL, 1.3f)
        part(0.066f, -1.05f, 0.02f, 0.012f, 1.45f, 0.08f, cFuller)
        part(-0.066f, -1.05f, 0.02f, 0.012f, 1.45f, 0.08f, cFuller)
        // Runes running down the fuller, flickering in sequence
        for (k in 0 until 7) {
            val y = -0.42f - k * 0.2f
            val fl = (0.35f + 0.65f * kotlin.math.max(0f, sin(time * 4f - k * 0.9f))) * (if (p2) 1.5f else 0.7f)
            glowPart(0.074f, y, 0.02f, 0.008f, 0.07f, 0.045f, fl, fl * (if (p2) 0.35f else 0.15f), fl * (if (p2) 0.05f else 0.1f))
            glowPart(-0.074f, y, 0.02f, 0.008f, 0.07f, 0.045f, fl, fl * (if (p2) 0.35f else 0.15f), fl * (if (p2) 0.05f else 0.1f))
        }
        // Serrated back edge
        for (k in 0 until 10) { push(); tr(0f, -0.45f - k * 0.15f, 0.165f); rot(45f, 1f, 0f, 0f); part(0f, 0f, 0f, 0.09f, 0.07f, 0.07f, cBossBlade, M_METAL, 1.1f); pop() }
        // Cutting edge: a bright bevel (burns in phase two)
        if (p2) glowPart(0f, -1.17f, -0.125f, 0.05f, 1.75f, 0.03f, 1.1f, 0.35f, 0.05f)
        else part(0f, -1.17f, -0.125f, 0.05f, 1.75f, 0.03f, blade, M_METAL, 1.8f)
        // A chipped, angled tip and a crack near the end
        push(); tr(0f, -2.08f, 0.0f); rot(32f, 1f, 0f, 0f); part(0f, -0.08f, 0f, 0.12f, 0.26f, 0.17f, cBossBlade, M_METAL, 1.3f); pop()
        part(0.067f, -1.75f, 0.06f, 0.006f, 0.014f, 0.16f, cBlack); part(-0.067f, -1.68f, -0.02f, 0.006f, 0.012f, 0.12f, cBlack)
    }

    private var eyeGlow = 0f

    private fun bossPose(g: Game) {
        val b = g.boss
        val q = pose; q.reset()
        // Heavy, swaying stride
        gait(q, b.walkPhase * 1.6f, b.moving, 30f, heavy = true)
        val breathe = sin(g.time * 1.4f) * (1f - b.moving)
        q.lift += breathe * 0.015f; q.lean += 6f + breathe * 2f
        q.armRPitch = -35f - sin(b.walkPhase * 1.6f) * 10f * b.moving; q.elbowR = 25f; q.wristR = -60f
        q.armLPitch -= 5f
        val t = b.t
        when (b.state) {
            BState.INTRO -> {
                // Rises from a kneel and squares up
                val k = ease(t / 1.4f)
                q.kneel = 1f - k; q.lean = lerp(35f, 6f, k); q.headPitch = lerp(30f, 0f, ease((t - 0.8f) / 0.6f))
            }
            BState.CHAIN -> {
                val mt = b.mt
                val m = b.move
                when (m) {
                    Move.SWEEP -> sweepPose(q, mt, m.wind, m.hit1, -135f, 115f, if (b.comboStep < b.chain.size - 1) 0f else 1f)
                    Move.BACKHAND -> sweepPose(q, mt, m.wind, m.hit1, 125f, -120f, if (b.comboStep < b.chain.size - 1) 0f else 1f)
                    Move.SLAM -> slamPose(q, mt, m.wind, m.hit0, b.comboStep < b.chain.size - 1)
                    Move.THRUST -> {
                        val w = ease(mt / m.wind)
                        val go = snap((mt - m.wind) / (m.hit0 - m.wind + 0.05f))
                        val rec = if (b.comboStep < b.chain.size - 1) 0f else ease((mt - m.hit1 - 0.25f) / 0.6f)
                        // Draw the blade back at the hip, then drive it forward with a long lunge
                        q.armRPitch = lerp(lerp(-35f, -70f, w), -92f, go); q.armRYaw = lerp(lerp(0f, -40f, w), -8f, go)
                        q.elbowR = lerp(lerp(25f, 100f, w), 0f, go); q.wristR = lerp(lerp(-60f, -5f, w), 0f, go)
                        q.twist = lerp(lerp(0f, -30f, w), 15f, go)
                        q.lean = lerp(lerp(6f, 4f, w), 30f, go); q.lift = lerp(-0.1f * w, -0.22f, go)
                        q.legL = lerp(10f * w, 45f, go); q.legR = lerp(-5f * w, -30f, go)
                        q.kneeL = lerp(20f * w, 55f, go); q.kneeR = lerp(15f * w, 20f, go)
                        q.armLPitch = lerp(-25f, 10f, go); q.armLYaw = lerp(0f, 30f, go)
                        if (rec > 0f) {
                            q.armRPitch = lerp(q.armRPitch, -35f, rec); q.armRYaw *= 1f - rec; q.elbowR = lerp(q.elbowR, 25f, rec); q.wristR = lerp(q.wristR, -60f, rec)
                            q.twist *= 1f - rec; q.lean = lerp(q.lean, 6f, rec); q.lift *= 1f - rec
                            q.legL *= 1f - rec; q.legR *= 1f - rec; q.kneeL = lerp(q.kneeL, 8f, rec); q.kneeR = lerp(q.kneeR, 8f, rec)
                        }
                    }
                }
            }
            BState.SKYFALL -> {
                when {
                    t < 0.9f -> { val k = ease(t / 0.9f); q.kneeL = 95f * k; q.kneeR = 95f * k; q.legL = 50f * k; q.legR = 45f * k; q.lift = -0.4f * k; q.lean = 30f * k; q.armRPitch = lerp(-35f, 10f, k); q.armLPitch = lerp(-25f, 15f, k) }
                    t < 3.75f -> {
                        // Both hands on the hilt, blade pointing straight down
                        q.armRPitch = -150f; q.elbowR = 70f; q.wristR = -150f
                        q.armLPitch = -150f; q.elbowL = 70f; q.armLYaw = -25f
                        q.legL = 30f; q.legR = -10f; q.kneeL = 70f; q.kneeR = 40f; q.lean = 10f
                    }
                    else -> {
                        val r = ease((t - 4.4f) / 0.8f)
                        q.kneel = 1f - r; q.lean = lerp(40f, 6f, r); q.headPitch = 20f * (1f - r)
                        q.armRPitch = lerp(-10f, -35f, r); q.elbowR = lerp(10f, 25f, r); q.wristR = lerp(-30f, -60f, r)
                    }
                }
            }
            BState.LUNGE -> {
                val wind = ease(t / 0.6f)
                q.armRPitch = lerp(-30f, -75f, wind); q.armRYaw = lerp(0f, -55f, wind); q.elbowR = lerp(25f, 60f, wind); q.wristR = lerp(-60f, -20f, wind)
                q.lean = 22f * wind; q.lift = -0.22f * wind; q.kneeL = 50f * wind; q.kneeR = 35f * wind; q.legL = 28f * wind; q.legR = -15f * wind
                if (t > 0.6f && t < 1.12f) {
                    // Charging: thrust the blade forward, legs pumping
                    gait(q, b.walkPhase * 1.6f, 1f, 45f, heavy = false)
                    q.armRYaw = -10f; q.armRPitch = -90f; q.elbowR = 0f; q.wristR = 0f; q.lean = 32f
                }
                if (t >= 1.12f) {
                    val r = ease((t - 1.12f) / 0.7f)
                    q.lean = lerp(32f, 6f, r); q.armRPitch = lerp(-90f, -35f, r); q.wristR = lerp(0f, -60f, r); q.elbowR = lerp(0f, 25f, r)
                    q.kneeL = lerp(40f, 8f, r); q.legL = lerp(30f, 0f, r); q.lift = lerp(-0.15f, 0f, r)
                }
            }
            BState.LEAP -> {
                when {
                    t < 0.7f -> { val k = ease(t / 0.7f); q.kneeL = 80f * k; q.kneeR = 80f * k; q.legL = 40f * k; q.legR = 35f * k; q.lift = -0.35f * k; q.lean = 25f * k; q.armRPitch = lerp(-30f, -60f, k); q.armLPitch = lerp(-25f, 20f, k) }
                    t < 1.5f -> {
                        val k = (t - 0.7f) / 0.8f
                        q.armRPitch = lerp(-150f, -200f, k); q.elbowR = 40f; q.wristR = -90f; q.armLPitch = -160f
                        q.legL = lerp(-20f, 50f, k); q.legR = lerp(10f, 30f, k); q.kneeL = 60f; q.kneeR = lerp(20f, 70f, k); q.lean = lerp(-10f, 10f, k)
                    }
                    else -> {
                        val r = ease((t - 1.9f) / 0.7f)
                        q.armRPitch = lerp(-38f, -35f, r); q.elbowR = lerp(0f, 25f, r); q.wristR = lerp(-10f, -60f, r)
                        q.lean = lerp(40f, 6f, r); q.kneel = 0.5f * (1f - r); q.lift = lerp(-0.1f, 0f, r)
                    }
                }
            }
            BState.STAGGER -> {
                val k = ease(t / 0.3f) * (1f - ease((t - 1.8f) / 0.4f))
                q.kneel = k; q.lean = 38f * k; q.headPitch = 25f * k
                q.armRPitch = lerp(q.armRPitch, -5f, k); q.armLPitch = lerp(q.armLPitch, 15f, k); q.elbowR = 10f; q.elbowL = 10f
            }
            BState.PHASE -> {
                val k = ease(t / 0.8f) * (1f - ease((t - 1.9f) / 0.5f))
                q.armRPitch = -40f; q.armRYaw = -70f * k; q.armLPitch = -100f * k; q.armLYaw = 70f * k; q.elbowL = 20f
                q.lean = -28f * k; q.headPitch = -30f * k; q.kneeL = 25f * k; q.kneeR = 25f * k; q.lift = -0.08f * k
            }
            BState.DEAD -> {
                val k = ease(t / 1.2f)
                q.kneel = k; q.lean = 48f * k; q.headPitch = 30f * k; q.armRPitch = -5f; q.armLPitch = -5f; q.rootRoll = 6f * k
            }
            BState.IDLE -> {}
        }
    }

    /** A big horizontal cut: coil the whole body to one side, then unwind through the swing. */
    private fun sweepPose(q: Pose, t: Float, wind: Float, end: Float, from: Float, to: Float, recover: Float) {
        val w = ease(t / wind)
        val s = snap((t - wind) / (end - wind))
        val rec = recover * ease((t - end - 0.3f) / 0.5f)
        val dir = if (to > from) 1f else -1f
        q.armRPitch = lerp(-35f, -86f, w); q.elbowR = lerp(25f, 5f, w); q.wristR = lerp(-60f, -5f, w)
        q.armRYaw = lerp(lerp(0f, from, w), to, s)
        q.twist = lerp(lerp(0f, -40f * dir, w), 40f * dir, s)
        q.lean = 12f * w + 6f * s
        q.lift = -0.12f * w
        q.legL = 22f * w; q.legR = -18f * w; q.kneeL = 10f + 40f * w; q.kneeR = 10f + 30f * w
        q.armLPitch = lerp(-25f, -70f, w); q.armLYaw = lerp(0f, 40f * dir, w) - 50f * dir * s
        if (rec > 0f) {
            q.armRYaw *= 1f - rec; q.twist *= 1f - rec; q.armRPitch = lerp(q.armRPitch, -35f, rec); q.wristR = lerp(q.wristR, -60f, rec)
            q.elbowR = lerp(q.elbowR, 25f, rec); q.lift *= 1f - rec; q.kneeL = lerp(q.kneeL, 8f, rec); q.kneeR = lerp(q.kneeR, 8f, rec)
            q.legL *= 1f - rec; q.legR *= 1f - rec; q.armLYaw *= 1f - rec
        }
    }

    /** Overhead smash: rear back on the toes, then the whole body drives the blade into the floor. */
    private fun slamPose(q: Pose, t: Float, wind: Float, hit: Float, chained: Boolean) {
        val up = ease(t / wind)
        val down = snap((t - (hit - 0.1f)) / 0.1f)
        val rec = if (chained) 0f else ease((t - hit - 0.5f) / 0.55f)
        q.armRPitch = lerp(lerp(-30f, -190f, up), -38f, down); q.elbowR = lerp(lerp(25f, 45f, up), 0f, down)
        q.wristR = lerp(-85f, -10f, down)
        q.armLPitch = lerp(-25f, -175f, up * (1f - down)); q.elbowL = 30f
        q.lean = lerp(lerp(6f, -16f, up), 36f, down)
        q.lift = lerp(0.04f * up, -0.22f, down)
        q.legL = lerp(0f, 30f, down); q.legR = lerp(-5f, -28f, down)
        q.kneeL = lerp(10f, 60f, down); q.kneeR = lerp(15f, 40f, down)
        q.headPitch = lerp(-10f * up, 15f, down)
        if (rec > 0f) {
            q.armRPitch = lerp(q.armRPitch, -35f, rec); q.wristR = lerp(q.wristR, -60f, rec); q.elbowR = lerp(q.elbowR, 25f, rec)
            q.lean = lerp(q.lean, 6f, rec); q.lift = lerp(q.lift, 0f, rec); q.kneeL = lerp(q.kneeL, 8f, rec); q.kneeR = lerp(q.kneeR, 8f, rec)
            q.legL = lerp(q.legL, 0f, rec); q.legR = lerp(q.legR, 0f, rec)
        }
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
        if (!poseInit) shownBoss.copyFrom(pose)
        else shownBoss.blendToward(pose, 1f - kotlin.math.exp(-frameDt * 17f))
        poseInit = true
        drawHumanoid(shownBoss, isBoss = true)
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
        // Falling stars of light
        for (st in g.stars) {
            if (st.t < 0f || st.done) continue
            val k = st.t / STAR_FALL
            sp = 0; Matrix.setIdentityM(stack, 0)
            tr(st.x, 14f * (1f - k), st.z); scl(0.32f, 0.55f, 0.32f)
            GLES20.glUniform1f(uAlpha, 0.9f)
            emitR = 1.4f; emitG = 1.25f; emitB = 0.8f
            drawMesh(ball, 0f, 0f, 0f)
            sp = 0; Matrix.setIdentityM(stack, 0)
            tr(st.x, 14f * (1f - k) + 1.1f, st.z); scl(0.12f, 1.4f, 0.12f)
            GLES20.glUniform1f(uAlpha, 0.35f)
            drawMesh(ball, 0f, 0f, 0f)
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
        const val M_STONE = 0
        const val M_METAL = 1
        const val M_CLOTH = 2
        const val M_NONE = 3

        const val VS = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform vec3 uTexScale;
            attribute vec3 aPos;
            attribute vec3 aNor;
            attribute vec3 aCol;
            varying vec3 vCol;
            varying vec3 vNor;
            varying vec3 vWorld;
            varying vec3 vLocal;
            varying vec3 vLNor;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vNor = (uModel * vec4(aNor, 0.0)).xyz;
                vLocal = aPos * uTexScale;
                vLNor = aNor;
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
            uniform sampler2D uTex;
            uniform float uMat;
            uniform float uLocal;
            uniform float uSpec;
            uniform float uTexK;
            varying vec3 vCol;
            varying vec3 vNor;
            varying vec3 vWorld;
            varying vec3 vLocal;
            varying vec3 vLNor;
            void main() {
                vec3 n = normalize(vNor);
                float m = 0.75;
                if (uMat < 2.5) {
                    // Texture projected from three sides (no UVs needed)
                    vec3 p = mix(vWorld * uTexK, vLocal, uLocal);
                    vec3 an = abs(normalize(mix(vNor, vLNor, uLocal)));
                    an = an / (an.x + an.y + an.z);
                    vec4 tx = texture2D(uTex, p.yz) * an.x + texture2D(uTex, p.xz) * an.y + texture2D(uTex, p.xy) * an.z;
                    m = uMat < 0.5 ? tx.r : (uMat < 1.5 ? tx.g : tx.b);
                }
                vec3 base = vCol * uTint * (0.45 + 0.75 * m);
                vec3 V = normalize(uCam - vWorld);
                float dif = max(dot(n, -uLight), 0.0);
                float shin = uSpec * (0.3 + m);
                vec3 H = normalize(V - uLight);
                vec3 c = base * (vec3(0.16, 0.18, 0.25) + vec3(0.55, 0.6, 0.78) * dif);
                c += vec3(0.45, 0.5, 0.65) * pow(max(dot(n, H), 0.0), 30.0) * shin * dif;
                for (int i = 0; i < 4; i++) {
                    vec3 L = uPL[i] - vWorld;
                    float d = length(L);
                    vec3 l = L / d;
                    float att = 1.0 / (1.0 + d * d * 0.06);
                    c += base * uPC[i] * max(dot(n, l), 0.0) * att;
                    c += uPC[i] * pow(max(dot(n, normalize(l + V)), 0.0), 24.0) * shin * 0.45 * att;
                }
                // Cool rim light so figures stand out from the dark
                c += vec3(0.12, 0.16, 0.26) * pow(1.0 - max(dot(n, V), 0.0), 3.0) * uLocal;
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
