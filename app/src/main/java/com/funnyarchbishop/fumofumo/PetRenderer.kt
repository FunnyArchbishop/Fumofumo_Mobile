package com.funnyarchbishop.fumofumo

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * 独立 GL 线程：自己建 EGL 上下文，渲染到 TextureView 的 SurfaceTexture 上。
 * 用 TextureView 而不是 GLSurfaceView，是因为 SurfaceView 在悬浮窗里做不出真正的透明背景。
 */
class PetRenderer(
    private val surfaceTexture: SurfaceTexture,
    private val prefs: PetPrefs,
    private val onMessage: (String) -> Unit
) : Thread("pet-gl") {

    private val lock = Any()
    val physics = PetPhysics()

    @Volatile
    private var running = true

    @Volatile
    private var viewW = 1

    @Volatile
    private var viewH = 1

    // 待上传的网格（由 UI 线程塞进来）
    private var mesh: MeshData? = null
    private var meshVersion = 0
    private var uploadedVersion = -1
    private var uploadedScale = -1f
    private var uploadedFlip: Boolean? = null

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    // GL
    private var program = 0
    private var locPos = -1
    private var locNormal = -1
    private var locUv = -1
    private var locMvp = -1
    private var locModel = -1
    private var locLightDir = -1
    private var locTex = -1
    private var locHasTex = -1
    private var locAmbient = -1
    private var locGamma = -1

    private var vboPos = 0
    private var vboNrm = 0
    private var vboUv = 0
    private var texId = 0
    private var hasTex = false
    private var vertexCount = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    fun setMesh(data: MeshData) {
        synchronized(lock) {
            mesh = data
            meshVersion++
        }
    }

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        viewW = w
        viewH = h
        surfaceTexture.setDefaultBufferSize(w, h)
        synchronized(lock) {
            physics.width = w.toFloat()
            physics.height = h.toFloat()
        }
    }

    fun shutdown() {
        running = false
    }

    // ---------------- 触摸（由 UI 线程调用，坐标已换算成 y 向上） ----------------

    fun hitTest(worldX: Float, worldY: Float): Boolean {
        synchronized(lock) {
            val aabb = physics.computeAabb()
            val m = 16f
            return worldX >= aabb[0] - m && worldX <= aabb[2] + m &&
                worldY >= aabb[1] - m && worldY <= aabb[3] + m
        }
    }

    fun beginDrag(worldX: Float, worldY: Float) = synchronized(lock) {
        physics.grab(worldX, worldY)
    }

    fun dragTo(worldX: Float, worldY: Float) = synchronized(lock) {
        physics.dragTo(worldX, worldY)
    }

    fun endDrag(throwVx: Float, throwVy: Float) = synchronized(lock) {
        physics.release(throwVx, throwVy, prefs.getFloat("throwMultiplier"), true)
    }

    /** 双指拖动：横向 → 绕 Y 转，纵向 → 绕 X 转 */
    fun spinBy(deltaX: Float, deltaY: Float) = synchronized(lock) {
        physics.rotateBy(deltaY * 0.006f, -deltaX * 0.006f, 0f, 1f)
    }

    fun isDragging(): Boolean = synchronized(lock) { physics.dragging }

    // ---------------- GL 线程 ----------------

    override fun run() {
        try {
            if (!initEgl()) {
                onMessage("初始化 OpenGL 失败")
                return
            }
            initGl()
            var last = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now

                synchronized(lock) {
                    val gravity = prefs.getFloat("gravity")
                    val restitution = prefs.getFloat("restitution")
                    val friction = prefs.getFloat("friction")
                    val airDrag = prefs.getFloat("airDrag")
                    physics.step(dt, gravity, restitution, friction, airDrag)
                }

                drawFrame()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) break

                val spentMs = (System.nanoTime() - now) / 1_000_000f
                val sleep = 16f - spentMs
                if (sleep > 1f) Thread.sleep(sleep.toLong())
            }
        } catch (t: Throwable) {
            onMessage(t.message ?: t.javaClass.simpleName)
        } finally {
            releaseGl()
            releaseEgl()
        }
    }

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) return false
        if (numConfigs[0] < 1) return false
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun initGl() {
        val vs = """
            #version 300 es
            uniform mat4 uMvp;
            uniform mat4 uModel;
            in vec3 aPos;
            in vec3 aNormal;
            in vec2 aUv;
            out vec3 vNormal;
            out vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vNormal = mat3(uModel) * aNormal;
                vUv = aUv;
            }
        """.trimIndent()

        val fs = """
            #version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec2 vUv;
            uniform vec3 uLightDir;
            uniform sampler2D uTex;
            uniform float uHasTex;
            uniform float uAmbient;
            uniform float uGamma;
            out vec4 fragColor;
            void main() {
                vec4 c = (uHasTex > 0.5) ? texture(uTex, vUv) : vec4(0.7, 0.8, 1.0, 1.0);
                if (c.a < 0.2) discard;
                vec3 n = normalize(vNormal);
                float d = max(dot(n, normalize(uLightDir)), 0.0);
                vec3 lighting = vec3(uAmbient) + vec3(d * (1.0 - uAmbient));
                fragColor = vec4(pow(c.rgb * lighting, vec3(1.0 / uGamma)), c.a);
            }
        """.trimIndent()

        val v = compileShader(GLES30.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES30.GL_FRAGMENT_SHADER, fs)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, v)
        GLES30.glAttachShader(program, f)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            throw RuntimeException("着色器链接失败: $log")
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)

        locPos = GLES30.glGetAttribLocation(program, "aPos")
        locNormal = GLES30.glGetAttribLocation(program, "aNormal")
        locUv = GLES30.glGetAttribLocation(program, "aUv")
        locMvp = GLES30.glGetUniformLocation(program, "uMvp")
        locModel = GLES30.glGetUniformLocation(program, "uModel")
        locLightDir = GLES30.glGetUniformLocation(program, "uLightDir")
        locTex = GLES30.glGetUniformLocation(program, "uTex")
        locHasTex = GLES30.glGetUniformLocation(program, "uHasTex")
        locAmbient = GLES30.glGetUniformLocation(program, "uAmbient")
        locGamma = GLES30.glGetUniformLocation(program, "uGamma")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("着色器编译失败: $log")
        }
        return shader
    }

    private fun releaseGl() {
        if (program != 0) GLES30.glDeleteProgram(program)
        val buffers = intArrayOf(vboPos, vboNrm, vboUv)
        GLES30.glDeleteBuffers(3, buffers, 0)
        if (texId != 0) GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
    }

    // ---------------- 网格上传 ----------------

    private fun uploadIfNeeded() {
        val data = synchronized(lock) { mesh } ?: return
        val flip = prefs.flipTexture
        val scale = computeScale(data)
        if (uploadedVersion == meshVersion && uploadedScale == scale && uploadedFlip == flip) return

        val centerX = (data.min[0] + data.max[0]) / 2f
        val centerY = (data.min[1] + data.max[1]) / 2f
        val centerZ = (data.min[2] + data.max[2]) / 2f

        val positions = FloatArray(data.positions.size)
        var i = 0
        while (i < data.positions.size) {
            positions[i] = (data.positions[i] - centerX) * scale
            positions[i + 1] = (data.positions[i + 1] - centerY) * scale
            positions[i + 2] = (data.positions[i + 2] - centerZ) * scale
            i += 3
        }

        if (vboPos == 0) {
            val ids = IntArray(3)
            GLES30.glGenBuffers(3, ids, 0)
            vboPos = ids[0]; vboNrm = ids[1]; vboUv = ids[2]
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, positions.size * 4,
            java.nio.FloatBuffer.wrap(positions), GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNrm)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, data.normals.size * 4,
            java.nio.FloatBuffer.wrap(data.normals), GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUv)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, data.uvs.size * 4,
            java.nio.FloatBuffer.wrap(data.uvs), GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        vertexCount = data.vertexCount

        // 贴图
        if (texId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
            texId = 0
        }
        hasTex = false
        val bmp = data.texture
        if (bmp != null) {
            texId = uploadTexture(bmp, flip)
            hasTex = texId != 0
        }

        uploadedVersion = meshVersion
        uploadedScale = scale
        uploadedFlip = flip

        val halfX = (data.max[0] - data.min[0]) / 2f * scale
        val halfY = (data.max[1] - data.min[1]) / 2f * scale
        val halfZ = (data.max[2] - data.min[2]) / 2f * scale
        synchronized(lock) {
            val firstTime = physics.halfX <= 1f && physics.halfY <= 1f
            physics.halfX = halfX.coerceAtLeast(1f)
            physics.halfY = halfY.coerceAtLeast(1f)
            physics.halfZ = halfZ.coerceAtLeast(1f)
            physics.inertia = (((halfX * 2f) * (halfX * 2f) + (halfY * 2f) * (halfY * 2f)) / 12f)
                .coerceAtLeast(1500f)
            physics.width = viewW.toFloat()
            physics.height = viewH.toFloat()
            if (firstTime) physics.reset(viewW / 2f, viewH / 2f)
        }

        onMessage("模型已载入：${data.triangleCount} 个三角面")
    }

    private fun computeScale(data: MeshData): Float {
        val ext = maxOf(
            data.max[0] - data.min[0],
            data.max[1] - data.min[1],
            data.max[2] - data.min[2]
        )
        val target = viewH * prefs.getFloat("heightRatio") * prefs.getFloat("modelScale")
        return if (ext > 1e-6f) target / ext else 1f
    }

    private fun uploadTexture(source: Bitmap, flip: Boolean): Int {
        val bmp = if (flip) {
            val m = Matrix().apply { postScale(1f, -1f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, false)
        } else {
            source
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    // ---------------- 绘制 ----------------

    private fun drawFrame() {
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return

        uploadIfNeeded()
        if (vertexCount == 0) return

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)

        synchronized(lock) {
            val r = physics.rot
            // 模型矩阵（行主序）：左上 3x3 = 旋转，最后一列 = 屏幕位置
            modelMatrix[0] = r[0]; modelMatrix[1] = r[1]; modelMatrix[2] = r[2]; modelMatrix[3] = physics.cx
            modelMatrix[4] = r[3]; modelMatrix[5] = r[4]; modelMatrix[6] = r[5]; modelMatrix[7] = physics.cy
            modelMatrix[8] = r[6]; modelMatrix[9] = r[7]; modelMatrix[10] = r[8]; modelMatrix[11] = 0f
            modelMatrix[12] = 0f; modelMatrix[13] = 0f; modelMatrix[14] = 0f; modelMatrix[15] = 1f
        }

        // 正交投影：像素坐标 → NDC（y 向上，和桌面版一致）
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        val proj = floatArrayOf(
            2f / w, 0f, 0f, -1f,
            0f, 2f / h, 0f, -1f,
            0f, 0f, 0.001f, 0f,
            0f, 0f, 0f, 1f
        )
        multiply4(proj, modelMatrix, mvpMatrix)

        GLES30.glUniformMatrix4fv(locMvp, 1, false, transpose4(mvpMatrix), 0)
        GLES30.glUniformMatrix4fv(locModel, 1, false, transpose4(modelMatrix), 0)
        GLES30.glUniform3f(locLightDir, 0.3f, -0.5f, 1.0f)
        GLES30.glUniform1f(locAmbient, prefs.getFloat("ambient"))
        GLES30.glUniform1f(locGamma, prefs.getFloat("gamma").coerceAtLeast(0.1f))
        GLES30.glUniform1f(locHasTex, if (hasTex) 1f else 0f)
        GLES30.glUniform1i(locTex, 0)

        if (hasTex) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        }

        bindAttribute(locPos, vboPos, 3)
        bindAttribute(locNormal, vboNrm, 3)
        bindAttribute(locUv, vboUv, 2)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)

        GLES30.glDisableVertexAttribArray(locPos)
        GLES30.glDisableVertexAttribArray(locNormal)
        GLES30.glDisableVertexAttribArray(locUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun bindAttribute(location: Int, buffer: Int, size: Int) {
        if (location < 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 0, 0)
    }

    /** 行主序 4x4 相乘：out = a * b */
    private fun multiply4(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[row * 4 + k] * b[k * 4 + col]
                out[row * 4 + col] = sum
            }
        }
    }

    /** 行主序 → 列主序（OpenGL 要列主序） */
    private fun transpose4(src: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                out[col * 4 + row] = src[row * 4 + col]
            }
        }
        return out
    }
}
