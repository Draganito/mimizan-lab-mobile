package ch.bojovic.mimizanlab.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The live preview as the negative will see it: the camera's processed RGB
 * frame is linearised, mixed with the current channel weights, gamma
 * encoded and then bent by the same contrast S-curve the development uses,
 * on the GPU (AGSL `RuntimeShader`). Zebra stripes mark pixels above
 * [MonoLook.zebraThreshold].
 *
 * The effect sits on a Compose `graphicsLayer` around the `TextureView`,
 * not on the view itself: a `RenderEffect` on a `TextureView` blacked out
 * everything drawn above it on the Pixel 9 (Android 17).
 */
data class MonoLook(
    val r: Float = 0.25f,
    val g: Float = 0.5f,
    val b: Float = 0.25f,
    /** 0..1 display value above which stripes appear; > 1 = off. */
    val zebraThreshold: Float = 2f,
    val grid: Boolean = false,
    /** Same -1..1 S-curve as `look_with_contrast`, in the display domain. */
    val contrast: Float = 0f,
)

private const val MONO_SHADER = """
uniform shader content;
uniform float3 w;
uniform float zebra;
uniform float grid;
uniform float contrast;
uniform float2 size;

float tanh1(float x) {
    float e = exp(clamp(2.0 * x, -20.0, 20.0));
    return (e - 1.0) / (e + 1.0);
}

float applyContrast(float v, float c) {
    float a = abs(c);
    if (a < 0.001) return v;
    float k = a * 4.0;
    float t = tanh1(0.5 * k);
    float d = v - 0.5;
    float outv = c > 0.0
        ? 0.5 + 0.5 * tanh1(k * d) / t
        : 0.5 + 0.5 * log((1.0 + clamp(2.0 * t * d, -0.999999, 0.999999)) / (1.0 - clamp(2.0 * t * d, -0.999999, 0.999999))) / k;
    return clamp(outv, 0.0, 1.0);
}

half4 main(float2 xy) {
    half4 c = content.eval(xy);
    float3 lin = pow(max(float3(c.rgb), float3(0.0)), float3(2.2));
    float y = dot(lin, w);
    float v = applyContrast(pow(clamp(y, 0.0, 1.0), 1.0 / 2.2), contrast);
    if (zebra <= 1.0 && v >= zebra) {
        float s = mod(xy.x + xy.y, 18.0);
        if (s < 9.0) v = 0.15;
    }
    if (grid > 0.5) {
        float gx = min(abs(xy.x - size.x / 3.0), abs(xy.x - 2.0 * size.x / 3.0));
        float gy = min(abs(xy.y - size.y / 3.0), abs(xy.y - 2.0 * size.y / 3.0));
        if (gx < 0.75 || gy < 0.75) v = mix(v, 1.0, 0.35);
    }
    return half4(half3(v), 1.0);
}
"""

/**
 * Portrait 3:4 viewfinder. [onSurface] fires with the texture once it is
 * ready (and with null when it is destroyed).
 */
@Composable
fun MonoViewfinder(
    look: MonoLook,
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
                val sum = (look.r + look.g + look.b).takeIf { it > 1e-6f } ?: 1f
                shader.setFloatUniform("w", look.r / sum, look.g / sum, look.b / sum)
                shader.setFloatUniform("zebra", look.zebraThreshold)
                shader.setFloatUniform("grid", if (look.grid) 1f else 0f)
                shader.setFloatUniform("contrast", look.contrast)
                shader.setFloatUniform("size", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                clip = true
            },
    )
}
