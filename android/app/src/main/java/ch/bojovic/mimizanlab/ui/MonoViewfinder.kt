package ch.bojovic.mimizanlab.ui

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import ch.bojovic.mimizanlab.camera.FinderTone

/**
 * The live negative at the camera's frame rate. The ISP delivers the
 * preview with its tone curve pinned to sRGB (`IspControls`); this shader
 * undoes exactly that curve, pulls the colour step back through the
 * inverse matrix of the frame, mixes with the development's weights and
 * applies the paper — per pixel on the GPU, so the display costs
 * nothing on the CPU. Zebra stripes mark display values above
 * [FinderTone.zebra].
 *
 * The effect sits on a Compose `graphicsLayer` around the `TextureView`,
 * not on the view itself: a `RenderEffect` on a `TextureView` blacked out
 * everything drawn above it on the Pixel 9 (Android 17).
 */
private const val MONO_SHADER = """
uniform shader content;
uniform shader lut;
uniform float3 v;
uniform float3 vR;
uniform float3 vG;
uniform float3 vB;
uniform float3 lampGain;
uniform float plate;
uniform float invGamma;
uniform float lutLast;
uniform float zebra;

float lin(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float paper(float g) {
    float e = pow(g, invGamma);
    return float(lut.eval(float2(e * lutLast + 0.5, 0.5)).r);
}

half4 main(float2 xy) {
    half4 c = content.eval(xy);
    float3 l = float3(lin(float(c.r)), lin(float(c.g)), lin(float(c.b)));
    float3 o;
    if (plate > 0.5) {
        // Three positives: each taking filter on the linear picture, its
        // lamp, the same paper. A clipped site clamps at 1 in every channel.
        float3 g = clamp(float3(dot(l, vR), dot(l, vG), dot(l, vB)), 0.0, 1.0) * lampGain;
        o = float3(paper(g.r), paper(g.g), paper(g.b));
    } else {
        o = float3(paper(clamp(dot(l, v), 0.0, 1.0)));
    }
    if (zebra <= 1.0 && max(o.r, max(o.g, o.b)) >= zebra) {
        if (mod(xy.x + xy.y, 18.0) < 9.0) o = float3(0.15);
    }
    return half4(half3(o), 1.0);
}
"""

/**
 * The preview texture at the sensor's [aspect] (height / width, portrait).
 * [onSurface] fires with the texture once it is ready (and with null when
 * it is destroyed).
 */
@Composable
fun MonoViewfinder(
    tone: FinderTone,
    aspect: Float,
    onSurface: (SurfaceTexture?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listener = remember {
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = onSurface(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                onSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }
    val shader = remember { RuntimeShader(MONO_SHADER) }
    val lutShader = remember(tone) {
        BitmapShader(tone.lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { v ->
                v.isOpaque = true
                v.surfaceTextureListener = listener
                v.surfaceTexture?.let(onSurface)
            }
        },
        modifier = modifier
            .aspectRatio(aspect)
            .graphicsLayer {
                shader.setFloatUniform("v", tone.v[0], tone.v[1], tone.v[2])
                val p = tone.plate
                if (p != null) {
                    shader.setFloatUniform("vR", p[0], p[1], p[2])
                    shader.setFloatUniform("vG", p[3], p[4], p[5])
                    shader.setFloatUniform("vB", p[6], p[7], p[8])
                    shader.setFloatUniform("lampGain", tone.lampGain[0], tone.lampGain[1], tone.lampGain[2])
                    shader.setFloatUniform("plate", 1f)
                } else {
                    shader.setFloatUniform("vR", 0f, 0f, 0f)
                    shader.setFloatUniform("vG", 0f, 0f, 0f)
                    shader.setFloatUniform("vB", 0f, 0f, 0f)
                    shader.setFloatUniform("lampGain", 1f, 1f, 1f)
                    shader.setFloatUniform("plate", 0f)
                }
                shader.setFloatUniform("invGamma", tone.invGamma)
                shader.setFloatUniform("lutLast", (tone.lut.size - 1).toFloat())
                shader.setFloatUniform("zebra", tone.zebra)
                shader.setInputShader("lut", lutShader)
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                clip = true
            },
    )
}
