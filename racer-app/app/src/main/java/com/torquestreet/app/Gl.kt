package com.torquestreet.app

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Small OpenGL ES 3 helpers: shaders from the app's assets, buffers, and a mesh on the graphics card. */
object Gl {
    class ShaderError(msg: String) : RuntimeException(msg)

    private fun read(c: Context, name: String) = c.assets.open("shaders/$name").bufferedReader().use { it.readText() }

    /** Reads a shader from assets/shaders, pasting in the shared code where it says so. */
    fun source(c: Context, name: String): String = read(c, name).replace("//#include common", read(c, "common.glsl"))

    fun program(c: Context, vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, source(c, vs), vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, source(c, fs), fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw ShaderError("Couldn't link $vs + $fs: " + GLES30.glGetProgramInfoLog(p))
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun shader(type: Int, src: String, name: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw ShaderError("Couldn't compile $name: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    fun floats(a: FloatArray, n: Int = a.size): FloatBuffer {
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a, 0, n); b.position(0); return b
    }
    fun ints(a: IntArray): IntBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        b.put(a); b.position(0); return b
    }

    /** A mesh uploaded to the graphics card (the shared 14-float vertex layout). */
    class GpuMesh(val vao: Int, val vbo: Int, val ibo: Int, val count: Int) {
        fun draw() { if (count == 0) return; GLES30.glBindVertexArray(vao); GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0) }
        fun delete() { GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0); GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0) }
    }

    fun upload(verts: FloatArray, idx: IntArray): GpuMesh {
        if (idx.isEmpty()) return GpuMesh(0, 0, 0, 0)
        val ids = IntArray(1); GLES30.glGenVertexArrays(1, ids, 0); val vao = ids[0]
        GLES30.glBindVertexArray(vao)
        val bufs = IntArray(2); GLES30.glGenBuffers(2, bufs, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, floats(verts), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufs[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ints(idx), GLES30.GL_STATIC_DRAW)
        val st = 14 * 4
        val attrs = intArrayOf(0, 3, 0, 1, 3, 3, 2, 2, 6, 3, 4, 8, 4, 1, 12, 5, 1, 13)
        for (k in 0 until 6) {
            val loc = attrs[k * 3]; val size = attrs[k * 3 + 1]; val off = attrs[k * 3 + 2]
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, st, off * 4)
        }
        GLES30.glBindVertexArray(0)
        return GpuMesh(vao, bufs[0], bufs[1], idx.size)
    }
}
