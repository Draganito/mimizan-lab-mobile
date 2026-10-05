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
 * applies the JPEG's look — per pixel on the GPU, so the display costs
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
uniform float invGamma;
uniform float zebra;

float lin(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

half4 main(float2 xy) {
    half4 c = content.eval(xy);
    float3 l = float3(lin(float(c.r)), lin(float(c.g)), lin(float(c.b)));
    float g = clamp(dot(l, v), 0.0, 1.0);
    float e = pow(g, invGamma);
    float o = float(lut.eval(float2(e * 255.0 + 0.5, 0.5)).r);
    if (zebra <= 1.0 && o >= zebra) {
        if (mod(xy.x + xy.y, 18.0) < 9.0) o = 0.15;
    }
    return half4(half3(o), 1.0);
}
"""

/**
 * Portrait 3:4 viewfinder. [onSurface] fires with the texture once it is
 * ready (and with null when it is destroyed).
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
                shader.setFloatUniform("invGamma", tone.invGamma)
                shader.setFloatUniform("zebra", tone.zebra)
                shader.setInputShader("lut", lutShader)
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                clip = true
            },
    )
}
