package com.torquestreet.app

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.torquestreet.app.core.CarShape
import com.torquestreet.app.core.Cars
import com.torquestreet.app.core.Env
import com.torquestreet.app.core.Input
import com.torquestreet.app.core.M4
import com.torquestreet.app.core.Mesh
import com.torquestreet.app.core.Race
import com.torquestreet.app.core.RaceState
import com.torquestreet.app.core.Role
import com.torquestreet.app.core.Weather
import com.torquestreet.app.core.World
import com.torquestreet.app.core.approach
import com.torquestreet.app.core.clamp
import com.torquestreet.app.core.lerp
import com.torquestreet.app.core.wrapAngle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything prepared before the race (on a background thread): the world, its textures and the race itself. */
class Loaded(val world: World, val textures: Array<ByteArray>, val texSize: Int, val env: Env, val race: Race, val carMeshes: Map<CarShape, Mesh>, val copMesh: Mesh)

/** What the player is pressing (written by the touch/tilt controls, read by the game each frame). */
class Controls {
    @Volatile var steer = 0f
    @Volatile var gas = 0f
    @Volatile var brake = 0f
    @Volatile var nitro = false
}

/**
 * Draws the race and runs it, frame by frame, on the graphics thread: the sky, the world, the cars, water and
 * effects, from a chase camera that widens and shakes as you go faster.
 */
class GameRenderer(
    private val ctx: Context, private val L: Loaded, private val controls: Controls, private val prefs: Prefs,
    private val hud: HudState, private val sound: SoundState, private val onError: (String) -> Unit
) : GLSurfaceView.Renderer {
    private val race = L.race
    private val env = L.env
    private val world = L.world
    @Volatile var paused = false
    @Volatile var cameraMode = prefs.camera

    private var pWorld = 0; private var pSky = 0; private var pWater = 0; private var pFx = 0
    private val loc = HashMap<String, Int>()
    private var texArray = 0
    private class GChunk(val x: Float, val z: Float, val r: Float, val solid: Gl.GpuMesh, val cut: Gl.GpuMesh)
    private val chunks = ArrayList<GChunk>()
    private var skyline: Gl.GpuMesh? = null
    private var water: Gl.GpuMesh? = null
    private val carGpu = HashMap<CarShape, Gl.GpuMesh>()
    private var copGpu: Gl.GpuMesh? = null
    private var skyVao = 0
    private var fxVao = 0; private var fxVbo = 0
    private var ready = false
    private var failed = false

    private var w = 1; private var h = 1
    private var lastNs = 0L
    private var t = 0f

    // Camera
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16); private val inv = FloatArray(16)
    private val model = FloatArray(16); private val ident = FloatArray(16).also { M4.identity(it) }
    private var camYaw = 0f; private var fov = 62f; private var shake = 0f
    private val eye = FloatArray(3); private val camR = FloatArray(3); private val camU = FloatArray(3); private val camF = FloatArray(3)
    private var drawDist = 1000f

    private val input = Input()
    private val parts = Particles(900)
    private val rnd = java.util.Random(3)
    private var topSpeed = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        ready = false
        try {
            pWorld = Gl.program(ctx, "world.vert", "world.frag")
            pSky = Gl.program(ctx, "sky.vert", "sky.frag")
            pWater = Gl.program(ctx, "water.vert", "water.frag")
            pFx = Gl.program(ctx, "fx.vert", "fx.frag")
            loc.clear()
            uploadTextures()
            chunks.clear()
            for (c in world.chunks) chunks.add(GChunk(c.x, c.z, c.radius, Gl.upload(c.solid.verts, c.solid.idx), Gl.upload(c.cutout.verts, c.cutout.idx)))
            skyline = Gl.upload(world.skyline.verts, world.skyline.idx)
            if (world.hasWater) {
                val r = 20000f; val cx = world.centerX; val cz = world.centerZ
                val v = FloatArray(4 * 14)
                val xs = floatArrayOf(cx - r, cx + r, cx + r, cx - r); val zs = floatArrayOf(cz - r, cz - r, cz + r, cz + r)
                for (i in 0 until 4) { v[i * 14] = xs[i]; v[i * 14 + 1] = world.waterLevel; v[i * 14 + 2] = zs[i] }
                water = Gl.upload(v, intArrayOf(0, 1, 2, 0, 2, 3))
            }
            carGpu.clear()
            for ((s, m) in L.carMeshes) carGpu[s] = Gl.upload(m.verts, m.idx)
            copGpu = Gl.upload(L.copMesh.verts, L.copMesh.idx)
            // The sky: one triangle over the whole screen
            val ids = IntArray(1); GLES30.glGenVertexArrays(1, ids, 0); skyVao = ids[0]
            GLES30.glBindVertexArray(skyVao)
            val b = IntArray(1); GLES30.glGenBuffers(1, b, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
            val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, Gl.floats(tri), GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            // Effects: rebuilt every frame
            GLES30.glGenVertexArrays(1, ids, 0); fxVao = ids[0]
            GLES30.glBindVertexArray(fxVao)
            GLES30.glGenBuffers(1, b, 0); fxVbo = b[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fxVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, FX_MAX * 9 * 4, null, GLES30.GL_STREAM_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 36, 12)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 36, 20)
            GLES30.glBindVertexArray(0)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            drawDist = min(floatArrayOf(650f, 950f, 1300f)[prefs.quality.coerceIn(0, 2)], 2.1f / env.fogDens)
            ready = true
            lastNs = 0L
        } catch (e: Throwable) {
            failed = true
            onError(e.message ?: e.toString())
        }
    }

    private fun uploadTextures() {
        val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); texArray = ids[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texArray)
        val n = L.texSize
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_RGBA8, n, n, L.textures.size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val buf = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        for ((i, layer) in L.textures.withIndex()) {
            buf.clear(); buf.put(layer); buf.position(0)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, n, n, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        // Sharp road far ahead (when the phone supports it)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        if ("GL_EXT_texture_filter_anisotropic" in ext) {
            val mx = FloatArray(1); GLES30.glGetFloatv(0x84FF, mx, 0)
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D_ARRAY, 0x84FE, min(mx[0], if (prefs.quality == 0) 4f else 8f))
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = max(1, width); h = max(1, height)
        GLES30.glViewport(0, 0, w, h)
    }

    private fun u(p: Int, name: String): Int = loc.getOrPut("$p/$name") { GLES30.glGetUniformLocation(p, name) }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) { GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 60f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        if (!paused) {
            t += dt
            input.steer = controls.steer; input.gas = controls.gas; input.brake = controls.brake; input.nitro = controls.nitro
            race.step(dt, input)
            topSpeed = max(topSpeed, race.player.v)
            spawnEffects(dt)
            parts.update(dt)
        }
        camera(if (paused) 0f else dt)
        draw()
        publish()
    }

    // ------------------------------------------------------------------ camera

    private fun camera(dt: Float) {
        val p = race.player
        val sf = clamp(abs(p.v) / 75f, 0f, 1f)
        // The camera swings round behind the car during the countdown, and slowly circles it at the end
        var orbit = 0f
        if (race.state == RaceState.COUNTDOWN) orbit = (race.countdown - 0.6f).coerceAtLeast(0f) / 3f * 2.4f
        val ended = race.state == RaceState.FINISHED || race.state == RaceState.BUSTED || race.state == RaceState.ESCAPED
        if (ended) endOrbit += dt * 0.25f else endOrbit = 0f
        val targetYaw = p.yaw + orbit + endOrbit
        camYaw += wrapAngle(targetYaw - camYaw) * min(1f, dt * (if (race.state == RaceState.COUNTDOWN) 3f else 6.5f))
        val nitroK = if (p.nitroOn) 1f else 0f
        nitroFov = approach(nitroFov, nitroK, dt * 2.5f)
        val hood = cameraMode == 1 && !ended && race.state != RaceState.COUNTDOWN
        val wantFov = (if (hood) 68f + 14f * sf else 60f + 17f * sf) + 9f * nitroFov
        fov = lerp(fov, wantFov, min(1f, dt * 3f))
        shake = max(shake * (1f - min(1f, dt * 8f)), race.bump * 0.02f)
        val fx = sin(camYaw); val fz = cos(camYaw)
        val px = p.pos[0]; val py = p.pos[1]; val pz = p.pos[2]
        var ex: Float; var ey: Float; var ez: Float; var tx: Float; var ty: Float; var tz: Float
        if (hood) {
            val cf = sin(p.yaw); val cc = cos(p.yaw)
            ex = px + cf * 0.4f; ey = py + 1.12f; ez = pz + cc * 0.4f
            tx = px + cf * 20f; ty = py + 0.95f; tz = pz + cc * 20f
        } else {
            val dist = (if (ended) 9f else 6.1f + 1.7f * sf) + if (race.state == RaceState.COUNTDOWN) 2f else 0f
            val ht = if (ended) 3.2f else 2.25f - 0.35f * sf
            ex = px - fx * dist; ey = py + ht; ez = pz - fz * dist
            tx = px + fx * 9f; ty = py + 1.05f; tz = pz + fz * 9f
        }
        // A little shake at speed, more over bumps
        val sh = 0.012f * sf * sf + shake
        ex += sin(t * 37f) * sh; ey += sin(t * 29f + 1f) * sh; ez += cos(t * 31f) * sh
        // Never below the ground or the road
        ey = max(ey, world.groundAt(ex, ez) + 0.6f)
        eye[0] = ex; eye[1] = ey; eye[2] = ez
        M4.perspective(proj, fov, w.toFloat() / h, 0.4f, 3200f)
        M4.lookAt(view, ex, ey, ez, tx, ty, tz, 0f, 1f, 0f)
        M4.mul(vp, proj, view)
        Matrix.invertM(inv, 0, vp, 0)
        camR[0] = view[0]; camR[1] = view[4]; camR[2] = view[8]
        camU[0] = view[1]; camU[1] = view[5]; camU[2] = view[9]
        camF[0] = -view[2]; camF[1] = -view[6]; camF[2] = -view[10]
    }
    private var endOrbit = 0f
    private var nitroFov = 0f

    // ------------------------------------------------------------------ drawing

    private fun setEnv(p: Int) {
        GLES30.glUniform3f(u(p, "uCam"), eye[0], eye[1], eye[2])
        GLES30.glUniform3fv(u(p, "uSunDir"), 1, env.sunDir, 0)
        GLES30.glUniform3fv(u(p, "uSunCol"), 1, env.sunCol, 0)
        GLES30.glUniform3fv(u(p, "uSkyTop"), 1, env.skyTop, 0)
        GLES30.glUniform3fv(u(p, "uSkyHor"), 1, env.skyHor, 0)
        GLES30.glUniform1f(u(p, "uCloud"), env.cloud)
        GLES30.glUniform1f(u(p, "uNight"), env.night)
        GLES30.glUniform1f(u(p, "uExposure"), env.exposure)
        GLES30.glUniform1f(u(p, "uTime"), t)
        GLES30.glUniform1f(u(p, "uFogDens"), env.fogDens)
    }

    private fun draw() {
        GLES30.glClearColor(env.skyHor[0], env.skyHor[1], env.skyHor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // Sky
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDepthMask(false)
        GLES30.glUseProgram(pSky); setEnv(pSky)
        GLES30.glUniform1f(u(pSky, "uStars"), env.stars)
        GLES30.glUniformMatrix4fv(u(pSky, "uInvVP"), 1, false, inv, 0)
        GLES30.glBindVertexArray(skyVao); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        // World
        val p = pWorld
        GLES30.glUseProgram(p); setEnv(p)
        GLES30.glUniform3fv(u(p, "uAmbSky"), 1, env.ambSky, 0)
        GLES30.glUniform3fv(u(p, "uAmbGround"), 1, env.ambGround, 0)
        GLES30.glUniform3fv(u(p, "uFogCol"), 1, env.fogCol, 0)
        GLES30.glUniform3fv(u(p, "uLampCol"), 1, env.lampCol, 0)
        GLES30.glUniform1f(u(p, "uWet"), env.wet)
        GLES30.glUniform1f(u(p, "uSand"), env.sand)
        GLES30.glUniformMatrix4fv(u(p, "uVP"), 1, false, vp, 0)
        GLES30.glUniform1i(u(p, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texArray)
        // Headlights: on at night, at dusk, and in rain or fog
        val pl = race.player
        val headOn = if (env.night > 0.3f || env.weather != Weather.CLEAR) 1f else 0f
        val hx = sin(pl.yaw); val hz = cos(pl.yaw)
        GLES30.glUniform3f(u(p, "uHeadPos"), pl.pos[0] + hx * 1.5f, pl.pos[1] + 0.75f, pl.pos[2] + hz * 1.5f)
        val dl = sqrt(hx * hx + 0.012f + hz * hz)
        GLES30.glUniform3f(u(p, "uHeadDir"), hx / dl, -0.11f / dl, hz / dl)
        GLES30.glUniform1f(u(p, "uHeadOn"), headOn)
        // Police lights wash over everything nearby
        GLES30.glUniform3f(u(p, "uFlash"), flash[0], flash[1], flash[2])
        GLES30.glUniformMatrix4fv(u(p, "uModel"), 1, false, ident, 0)
        GLES30.glUniform1f(u(p, "uCarLamp"), 0f)
        GLES30.glUniform2f(u(p, "uSiren"), 0f, 0f)
        GLES30.glUniform1f(u(p, "uBrake"), 0f)
        GLES30.glUniform1f(u(p, "uCutout"), 0f)
        GLES30.glUniform3f(u(p, "uPaint"), 1f, 1f, 1f)
        skyline?.draw()
        for (c in chunks) if (visible(c.x, c.z, c.r)) c.solid.draw()
        // Cars
        drawCars(p)
        // Leaves (cut out)
        GLES30.glUniformMatrix4fv(u(p, "uModel"), 1, false, ident, 0)
        GLES30.glUniform1f(u(p, "uCarLamp"), 0f)
        GLES30.glUniform1f(u(p, "uCutout"), 1f)
        for (c in chunks) if (c.cut.count > 0 && visible(c.x, c.z, c.r)) c.cut.draw()
        // Water
        water?.let {
            GLES30.glUseProgram(pWater); setEnv(pWater)
            GLES30.glUniform3fv(u(pWater, "uAmbSky"), 1, env.ambSky, 0)
            GLES30.glUniform3fv(u(pWater, "uFogCol"), 1, env.fogCol, 0)
            GLES30.glUniform1f(u(pWater, "uRain"), env.rain)
            GLES30.glUniformMatrix4fv(u(pWater, "uVP"), 1, false, vp, 0)
            it.draw()
        }
        drawFx()
        GLES30.glBindVertexArray(0)
    }

    private fun visible(x: Float, z: Float, r: Float): Boolean {
        val dx = x - eye[0]; val dz = z - eye[2]
        val d = hypot(dx, dz)
        if (d - r > drawDist) return false
        // Behind the camera?
        return dx * camF[0] + dz * camF[2] > -r - 10f
    }

    private val flash = FloatArray(3)

    private fun drawCars(p: Int) {
        val hood = cameraMode == 1 && race.state == RaceState.RUNNING
        for (c in race.cars) {
            if (!c.active) continue
            if (hood && c.role == Role.PLAYER) continue
            val dx = c.pos[0] - eye[0]; val dz = c.pos[2] - eye[2]
            if (dx * dx + dz * dz > drawDist * drawDist) continue
            if (dx * camF[0] + dz * camF[2] < -8f) continue
            val mesh = (if (c.role == Role.COP) copGpu else carGpu[c.shape]) ?: continue
            M4.model(model, c.pos[0], c.pos[1], c.pos[2], c.yaw, c.pitch, c.roll)
            GLES30.glUniformMatrix4fv(u(p, "uModel"), 1, false, model, 0)
            GLES30.glUniform3f(u(p, "uPaint"), c.paint[0], c.paint[1], c.paint[2])
            GLES30.glUniform1f(u(p, "uCarLamp"), world.lampAt(c.pos[0], c.pos[1] + 1f, c.pos[2]))
            GLES30.glUniform1f(u(p, "uBrake"), if (c.brake > 0.1f && c.v > 1f) 1f else 0f)
            if (c.role == Role.COP && c.lightsOn) { val s = siren(c.id); GLES30.glUniform2f(u(p, "uSiren"), s[0], s[1]) }
            else GLES30.glUniform2f(u(p, "uSiren"), 0f, 0f)
            mesh.draw()
        }
    }

    private val sirenOut = FloatArray(2)
    /** The light bar's pattern: red, red, then blue, blue (each car a little out of step). */
    private fun siren(id: Int): FloatArray {
        val ph = (t * 1.6f + id * 0.37f) % 1f
        sirenOut[0] = if (ph < 0.12f || (ph > 0.2f && ph < 0.32f)) 1f else 0.08f
        sirenOut[1] = if ((ph > 0.5f && ph < 0.62f) || (ph > 0.7f && ph < 0.82f)) 1f else 0.08f
        return sirenOut
    }

    // ------------------------------------------------------------------ effects

    private val fx = FloatArray(FX_MAX * 9)
    private var fxN = 0
    private var fxBuf: FloatBuffer = ByteBuffer.allocateDirect(FX_MAX * 9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun vtx(x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) {
        if (fxN >= FX_MAX) return
        val o = fxN * 9
        fx[o] = x; fx[o + 1] = y; fx[o + 2] = z; fx[o + 3] = u; fx[o + 4] = v; fx[o + 5] = r; fx[o + 6] = g; fx[o + 7] = b; fx[o + 8] = a
        fxN++
    }

    /** A soft spot facing the camera. */
    private fun sprite(x: Float, y: Float, z: Float, s: Float, r: Float, g: Float, b: Float, a: Float) {
        if (fxN + 6 > FX_MAX) return
        val rx = camR[0] * s; val ry = camR[1] * s; val rz = camR[2] * s
        val ux = camU[0] * s; val uy = camU[1] * s; val uz = camU[2] * s
        vtx(x - rx - ux, y - ry - uy, z - rz - uz, -1f, -1f, r, g, b, a)
        vtx(x + rx - ux, y + ry - uy, z + rz - uz, 1f, -1f, r, g, b, a)
        vtx(x + rx + ux, y + ry + uy, z + rz + uz, 1f, 1f, r, g, b, a)
        vtx(x - rx - ux, y - ry - uy, z - rz - uz, -1f, -1f, r, g, b, a)
        vtx(x + rx + ux, y + ry + uy, z + rz + uz, 1f, 1f, r, g, b, a)
        vtx(x - rx + ux, y - ry + uy, z - rz + uz, -1f, 1f, r, g, b, a)
    }

    /** A thin streak from one point to another, facing the camera (rain, wind). */
    private fun streak(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, wd: Float, r: Float, g: Float, b: Float, a: Float) {
        if (fxN + 6 > FX_MAX) return
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        // Side vector: across the streak, facing the camera
        val vx = (x0 + x1) / 2 - eye[0]; val vy = (y0 + y1) / 2 - eye[1]; val vz = (z0 + z1) / 2 - eye[2]
        var sx = dy * vz - dz * vy; var sy = dz * vx - dx * vz; var sz = dx * vy - dy * vx
        val l = sqrt(sx * sx + sy * sy + sz * sz); if (l < 1e-5f) return
        sx = sx / l * wd; sy = sy / l * wd; sz = sz / l * wd
        vtx(x0 - sx, y0 - sy, z0 - sz, -1f, 2f, r, g, b, a * 0.3f)
        vtx(x0 + sx, y0 + sy, z0 + sz, 1f, 2f, r, g, b, a * 0.3f)
        vtx(x1 + sx, y1 + sy, z1 + sz, 1f, 2f, r, g, b, a)
        vtx(x0 - sx, y0 - sy, z0 - sz, -1f, 2f, r, g, b, a * 0.3f)
        vtx(x1 + sx, y1 + sy, z1 + sz, 1f, 2f, r, g, b, a)
        vtx(x1 - sx, y1 - sy, z1 - sz, -1f, 2f, r, g, b, a)
    }

    /** A point on a car, from its own frame (x left, y up, z forward) into the world. */
    private val tp = FloatArray(3)
    private fun carPoint(c: com.torquestreet.app.core.Car, x: Float, y: Float, z: Float): FloatArray {
        val cy = cos(c.yaw); val sy = sin(c.yaw)
        tp[0] = c.pos[0] + x * cy + z * sy; tp[1] = c.pos[1] + y; tp[2] = c.pos[2] - x * sy + z * cy
        return tp
    }

    private fun spawnEffects(dt: Float) {
        for (c in race.cars) {
            if (!c.active) continue
            val dx = c.pos[0] - eye[0]; val dz = c.pos[2] - eye[2]
            if (dx * dx + dz * dz > 250f * 250f) continue
            val hl = c.length / 2; val hw = c.width / 2
            // Tyre smoke when sliding
            if (c.slide > 0.3f && abs(c.v) > 8f && rnd.nextFloat() < c.slide * 0.9f) {
                for (side in intArrayOf(-1, 1)) {
                    val q = carPoint(c, side * hw * 0.8f, 0.3f, -hl + 0.7f)
                    val g = if (env.night > 0.5f) 0.42f else 0.82f
                    parts.add(q[0], q[1], q[2], (rnd.nextFloat() - 0.5f), 0.6f + rnd.nextFloat() * 0.5f, (rnd.nextFloat() - 0.5f), 1.4f, 0.6f, 2.6f, g, g, g * 1.03f, 0.22f * c.slide, false)
                }
            }
            // Spray off the wet road
            if (env.wet > 0.5f && abs(c.v) > 14f && rnd.nextFloat() < 0.7f) {
                val q = carPoint(c, (rnd.nextFloat() - 0.5f) * c.width, 0.25f, -hl - 0.2f)
                val sx = sin(c.yaw) * c.v * 0.25f; val sz = cos(c.yaw) * c.v * 0.25f
                val g = if (env.night > 0.5f) 0.35f else 0.8f
                parts.add(q[0], q[1], q[2], sx, 0.8f, sz, 0.55f, 0.5f, 3.2f, g, g * 1.02f, g * 1.06f, 0.16f, false)
            }
            // Sparks when scraping a wall or trading paint
            if ((c.scrape > 0.5f && abs(c.v) > 6f) || c.hitCar > 5f) {
                val n = if (c.hitCar > 5f) 6 else 2
                for (k in 0 until n) {
                    val side = if (c.d > 0) -1f else 1f
                    val q = carPoint(c, side * hw, 0.35f, (rnd.nextFloat() - 0.5f) * c.length)
                    parts.add(q[0], q[1], q[2], (rnd.nextFloat() - 0.5f) * 7f, 2f + rnd.nextFloat() * 3f, (rnd.nextFloat() - 0.5f) * 7f, 0.35f, 0.07f, 0f, 1f, 0.6f, 0.2f, 1f, true, gravity = true)
                }
            }
            // Nitro: blue flames from the exhaust
            if (c.nitroOn) {
                for (side in intArrayOf(-1, 1)) {
                    val q = carPoint(c, side * 0.35f, 0.38f, -hl - 0.15f)
                    val bx = -sin(c.yaw) * 9f; val bz = -cos(c.yaw) * 9f
                    parts.add(q[0], q[1], q[2], bx + c.v * sin(c.yaw), 0f, bz + c.v * cos(c.yaw), 0.13f, 0.32f, -1.5f, 0.4f, 0.65f, 1f, 0.9f, true)
                }
            }
        }
        // Police lights light up the surroundings when they're close
        flash[0] = 0f; flash[1] = 0f; flash[2] = 0f
        for (c in race.cars) {
            if (c.role != Role.COP || !c.active || !c.lightsOn) continue
            val dx = c.pos[0] - eye[0]; val dz = c.pos[2] - eye[2]
            val k = clamp(1f - sqrt(dx * dx + dz * dz) / 45f, 0f, 1f) * (0.25f + 0.75f * env.night)
            if (k <= 0f) continue
            val s = siren(c.id)
            flash[0] += s[0] * k * 0.5f; flash[1] += s[1] * k * 0.05f; flash[2] += s[1] * k * 0.6f
        }
    }

    private fun drawFx() {
        fxN = 0
        val night = env.night
        // --- soft, darkening and smoky things (normal blending)
        for (c in race.cars) {
            if (!c.active) continue
            val dx = c.pos[0] - eye[0]; val dz = c.pos[2] - eye[2]
            if (dx * dx + dz * dz > 160f * 160f) continue
            // Shadow under the car
            val hl = c.length / 2 + 0.35f; val hw = c.width / 2 + 0.3f
            val sy = sin(c.yaw); val cy = cos(c.yaw)
            val y = c.pos[1] + 0.06f
            val a = if (night > 0.5f) 0.4f else 0.58f
            fun corner(x: Float, z: Float, u: Float, v: Float) = vtx(c.pos[0] + x * cy + z * sy, y, c.pos[2] - x * sy + z * cy, u, v, 0f, 0f, 0f, a)
            corner(-hw, -hl, -1f, -1f); corner(hw, -hl, 1f, -1f); corner(hw, hl, 1f, 1f)
            corner(-hw, -hl, -1f, -1f); corner(hw, hl, 1f, 1f); corner(-hw, hl, -1f, 1f)
        }
        parts.emit(false) { x, y, z, s, r, g, b, a -> sprite(x, y, z, s, r, g, b, a) }
        val nAlpha = fxN
        // --- glowing things (added light)
        val lampHalo = if (env.weather == Weather.CLEAR) 0.12f else 0.28f
        if (night > 0.05f) {
            for (l in world.lamps) {
                val dx = l.x - eye[0]; val dz = l.z - eye[2]
                val d2 = dx * dx + dz * dz
                if (d2 > 450f * 450f || dx * camF[0] + dz * camF[2] < 0f) continue
                sprite(l.x, l.y - 0.15f, l.z, 0.9f, env.lampCol[0], env.lampCol[1], env.lampCol[2], 0.9f * night)
                sprite(l.x, l.y - 0.6f, l.z, 4.5f, env.lampCol[0], env.lampCol[1], env.lampCol[2], lampHalo * night)
            }
            val bl = if ((t % 1.4f) < 0.5f) 1f else 0.15f
            val b = world.beacons
            var i = 0
            while (i < b.n) { sprite(b.a[i], b.a[i + 1], b.a[i + 2], 2.2f, 1f, 0.1f, 0.06f, 0.8f * bl * night); i += 3 }
        }
        val lights = night > 0.3f || env.weather != Weather.CLEAR
        for (c in race.cars) {
            if (!c.active) continue
            val dx = c.pos[0] - eye[0]; val dz = c.pos[2] - eye[2]
            if (dx * dx + dz * dz > 600f * 600f) continue
            val hl = c.length / 2; val hw = c.width / 2
            val belt = c.shape.belt
            if (lights || c.role == Role.COP) {
                for (side in intArrayOf(-1, 1)) {
                    if (lights) {
                        val q = carPoint(c, side * hw * 0.62f, belt - 0.12f, hl + 0.1f)
                        sprite(q[0], q[1], q[2], 0.55f, 1f, 0.97f, 0.9f, 0.9f)
                        sprite(q[0], q[1], q[2], 1.8f, 1f, 0.95f, 0.85f, 0.18f)
                    }
                    val braking = c.brake > 0.1f && c.v > 1f
                    if (lights || braking) {
                        val q = carPoint(c, side * hw * 0.66f, belt - 0.1f, -hl - 0.08f)
                        sprite(q[0], q[1], q[2], if (braking) 0.6f else 0.38f, 1f, 0.08f, 0.04f, if (braking) 1f else 0.7f)
                    }
                }
            }
            if (c.role == Role.COP && c.lightsOn) {
                val s = siren(c.id)
                val roof = c.shape.roof + 0.12f
                val qr = carPoint(c, hw * 0.36f, roof, 0f); sprite(qr[0], qr[1], qr[2], 1.9f, 1f, 0.12f, 0.08f, s[0])
                val qb = carPoint(c, -hw * 0.36f, roof, 0f); sprite(qb[0], qb[1], qb[2], 1.9f, 0.15f, 0.35f, 1f, s[1])
            }
        }
        parts.emit(true) { x, y, z, s, r, g, b, a -> sprite(x, y, z, s, r, g, b, a) }
        // Rain: streaks falling past the camera, slanting with your speed
        if (env.rain > 0f) {
            val p = race.player
            val vx = sin(p.yaw) * p.v; val vz = cos(p.yaw) * p.v
            val n = when (prefs.quality) { 0 -> 220; 1 -> 380; else -> 520 }
            val rc = if (night > 0.5f) 0.35f else 0.75f
            for (k in 0 until n) {
                // Each drop has a fixed spot in a box that moves with the camera (wraps around)
                val sx = ((k * 7.31f + t * 0.0f) % 1f); val sz = (k * 3.77f % 1f); val ph = (k * 0.618f % 1f)
                val bx = eye[0] + (fract(sx - eye[0] / 36f) - 0.5f) * 36f
                val bz = eye[2] + (fract(sz - eye[2] / 36f) - 0.5f) * 36f
                val by = eye[1] + 12f - fract(ph + t * 0.9f) * 22f
                streak(bx, by, bz, bx - vx * 0.035f, by - 0.65f, bz - vz * 0.035f, 0.012f, rc, rc, rc * 1.08f, 0.32f)
            }
        }
        // Wind streaks at speed: the air rushing past
        val pl = race.player
        val sf = clamp((abs(pl.v) - 38f) / 40f, 0f, 1f) + (if (pl.nitroOn) 0.5f else 0f)
        if (sf > 0f && cameraMode == 0) {
            val fxv = sin(pl.yaw); val fzv = cos(pl.yaw)
            val rxv = -fzv; val rzv = fxv
            for (k in 0 until 26) {
                val ph = fract(k * 0.381f + t * (1.1f + k % 5 * 0.12f))
                val along = 30f - ph * 40f
                val side = ((k * 0.733f) % 1f - 0.5f) * 9f
                val up = 0.4f + (k * 0.517f % 1f) * 3.2f
                if (abs(side) < 1.6f && up < 1.8f) continue
                val x0 = pl.pos[0] + fxv * along + rxv * side; val z0 = pl.pos[2] + fzv * along + rzv * side
                val len = 2f + abs(pl.v) * 0.08f
                val a = sf * 0.14f * sin(ph * 3.14f)
                streak(x0, pl.pos[1] + up, z0, x0 - fxv * len, pl.pos[1] + up, z0 - fzv * len, 0.01f, 0.9f, 0.95f, 1f, a)
            }
        }
        if (fxN == 0) return
        fxBuf.clear(); fxBuf.put(fx, 0, fxN * 9); fxBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fxVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, fxN * 9 * 4, fxBuf)
        GLES30.glUseProgram(pFx)
        GLES30.glUniformMatrix4fv(u(pFx, "uVP"), 1, false, vp, 0)
        GLES30.glUniform3f(u(pFx, "uCam"), eye[0], eye[1], eye[2])
        GLES30.glUniform1f(u(pFx, "uFogDens"), env.fogDens)
        GLES30.glBindVertexArray(fxVao)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(-1f, -2f)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        if (nAlpha > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, nAlpha)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        if (fxN > nAlpha) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, nAlpha, fxN - nAlpha)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
    }

    private fun fract(x: Float) = x - kotlin.math.floor(x)

    // ------------------------------------------------------------------ for the HUD and the sound

    private fun publish() {
        val r = race
        val p = r.player
        hud.publish { f ->
            f.speed = abs(p.v); f.gear = p.gear; f.rpm = p.rpm; f.nitro = p.nitro; f.nitroOn = p.nitroOn
            f.pos = r.position(); f.racers = r.racers; f.lap = p.lap; f.laps = r.laps; f.time = r.time; f.best = p.bestLap
            f.heat = r.heat; f.heatProg = r.heatProgress; f.pursuit = r.pursuit; f.busted = r.bustedMeter; f.escape = r.escapeMeter
            f.state = r.state; f.countdown = r.countdown; f.mode = r.mode; f.topSpeed = topSpeed; f.evaded = r.copsEvaded
            f.style = if (r.styleT > 0f) r.styleText else ""
            f.msgN = 0
            for (m in r.messages.takeLast(2)) { f.msgText[f.msgN] = m.text; f.msgSub[f.msgN] = m.sub; f.msgT[f.msgN] = m.t; f.msgColor[f.msgN] = m.color; f.msgN++ }
            var n = 0
            for (c in r.cars) {
                if (!c.active || n >= 40) continue
                if (c.role == Role.TRAFFIC) continue
                f.dots[n * 3] = c.pos[0]; f.dots[n * 3 + 1] = c.pos[2]
                f.dots[n * 3 + 2] = when (c.role) { Role.PLAYER -> 0f; Role.RIVAL -> 1f; else -> if (c.lightsOn) 2f else 3f }
                n++
            }
            f.nDots = n
            f.playerYaw = p.yaw
            f.paused = paused
        }
        sound.rpm = p.rpm; sound.throttle = p.throttle; sound.speed = abs(p.v); sound.slide = if (abs(p.v) > 6f) p.slide else 0f
        sound.nitro = p.nitroOn; sound.siren = r.sirenNear; sound.scrape = p.scrape * clamp(abs(p.v) / 20f, 0f, 1f)
        sound.rain = env.rain; sound.gear = p.gear; sound.paused = paused
        sound.countdown = if (r.state == RaceState.COUNTDOWN) r.countdown else -1f
        if (r.bump > 2f) sound.bump(r.bump)
        if (r.bump > 3f) hud.bump = max(hud.bump, r.bump)
    }

    companion object { const val FX_MAX = 16000 }
}

/** Short-lived effect particles: smoke, spray, sparks, flames. */
class Particles(private val cap: Int) {
    private val x = FloatArray(cap); private val y = FloatArray(cap); private val z = FloatArray(cap)
    private val vx = FloatArray(cap); private val vy = FloatArray(cap); private val vz = FloatArray(cap)
    private val life = FloatArray(cap); private val max = FloatArray(cap); private val size = FloatArray(cap); private val grow = FloatArray(cap)
    private val r = FloatArray(cap); private val g = FloatArray(cap); private val b = FloatArray(cap); private val a = FloatArray(cap)
    private val add = BooleanArray(cap); private val grav = BooleanArray(cap)
    private var next = 0

    fun add(px: Float, py: Float, pz: Float, ux: Float, uy: Float, uz: Float, lifeS: Float, s: Float, growS: Float,
            cr: Float, cg: Float, cb: Float, ca: Float, additive: Boolean, gravity: Boolean = false) {
        val i = next; next = (next + 1) % cap
        x[i] = px; y[i] = py; z[i] = pz; vx[i] = ux; vy[i] = uy; vz[i] = uz
        life[i] = lifeS; max[i] = lifeS; size[i] = s; grow[i] = growS
        r[i] = cr; g[i] = cg; b[i] = cb; a[i] = ca; add[i] = additive; grav[i] = gravity
    }

    fun update(dt: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
            val drag = 1f - min(1f, dt * 1.8f)
            vx[i] *= drag; vz[i] *= drag
            if (grav[i]) vy[i] -= 14f * dt else vy[i] *= drag
            size[i] = max(0.02f, size[i] + grow[i] * dt)
        }
    }

    fun emit(additive: Boolean, f: (Float, Float, Float, Float, Float, Float, Float, Float) -> Unit) {
        for (i in 0 until cap) {
            if (life[i] <= 0f || add[i] != additive) continue
            val k = life[i] / max[i]
            f(x[i], y[i], z[i], size[i], r[i], g[i], b[i], a[i] * k)
        }
    }
}
