// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui.viewer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import org.hdricapture.geom.Quat
import org.hdricapture.processing.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * Renders an equirectangular HDR as a view into the sphere. Each fragment computes its world ray
 * from the device pose and looks up the panorama, then applies exposure + ACES tone mapping.
 */
class PanoramaRenderer(
    private val hdrPath: String,
    private val pose: () -> Quat,
    private val onLoaded: (ok: Boolean, autoExposure: Float) -> Unit,
) : GLSurfaceView.Renderer {

    /** Extra yaw from touch dragging, degrees (positive = look right). */
    @Volatile var yawOffsetDeg = 0f
    /** Extra pitch from touch dragging, degrees (positive = look up). Applied in camera space. */
    @Volatile var pitchOffsetDeg = 0f
    /** Vertical field of view, degrees. */
    @Volatile var fovDeg = 75f
    /** Linear exposure multiplier. */
    @Volatile var exposure = 1f
    /** Yaw (world, compass degrees) that maps to the panorama centre. */
    @Volatile var headingDeg = Float.NaN

    private var program = 0
    private var texture = 0
    private var vbo = 0
    private var aspect = 1f
    private var loaded = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val info = NativeBridge.uploadHdrTexture(hdrPath)
        loaded = info != null
        onLoaded(loaded, info?.third ?: 1f)

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        vbo = b[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!loaded) return
        val q = pose()
        // Device -> world rotation as a column-major mat3 (columns = device axes in world space).
        val r = q.rotate(org.hdricapture.geom.Vec3(1.0, 0.0, 0.0))
        val u = q.rotate(org.hdricapture.geom.Vec3(0.0, 1.0, 0.0))
        val b = q.rotate(org.hdricapture.geom.Vec3(0.0, 0.0, 1.0))
        val m = floatArrayOf(
            r.x.toFloat(), r.y.toFloat(), r.z.toFloat(),
            u.x.toFloat(), u.y.toFloat(), u.z.toFloat(),
            b.x.toFloat(), b.y.toFloat(), b.z.toFloat(),
        )
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "uDevToWorld"), 1, false, m, 0)
        val tanHalf = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uTanHalf"), tanHalf * aspect, tanHalf)
        val heading = if (headingDeg.isNaN()) 0f else headingDeg
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uYawOffset"), Math.toRadians((heading - yawOffsetDeg).toDouble()).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPitchOffset"), Math.toRadians(pitchOffsetDeg.toDouble()).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uExposure"), exposure)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uPano"), 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        return p
    }

    companion object {
        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        // World: X east, Y north, Z up. Panorama: u = (yaw - heading + 180°) / 360°, v = (90° - pitch) / 180°,
        // with yaw measured clockwise from +Y — the same mapping the stitcher writes.
        private const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
out vec4 fragColor;
uniform sampler2D uPano;
uniform mat3 uDevToWorld;
uniform vec2 uTanHalf;
uniform float uYawOffset;
uniform float uPitchOffset;
uniform float uExposure;
const float PI = 3.14159265359;

vec3 aces(vec3 x) { return clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
vec3 toSrgb(vec3 c) { return mix(12.92 * c, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }

void main() {
    // Ray in device space: back camera looks along -Z, screen up is +Y.
    vec3 ray = normalize(vec3(vNdc.x * uTanHalf.x, vNdc.y * uTanHalf.y, -1.0));
    // Manual pitch tweak (touch drag) about the device X axis.
    float cp = cos(uPitchOffset), sp = sin(uPitchOffset);
    ray = vec3(ray.x, cp * ray.y - sp * ray.z, sp * ray.y + cp * ray.z);
    vec3 w = uDevToWorld * ray;
    float yaw = atan(w.x, w.y) - uYawOffset;
    float pitch = asin(clamp(w.z, -1.0, 1.0));
    vec2 uv = vec2(fract(yaw / (2.0 * PI) + 0.5), 0.5 - pitch / PI);
    // textureGrad with zero derivatives avoids a mip/derivative seam where yaw wraps.
    vec3 c = textureGrad(uPano, uv, vec2(0.0), vec2(0.0)).rgb;
    fragColor = vec4(toSrgb(aces(c * uExposure)), 1.0);
}
"""
    }
}
