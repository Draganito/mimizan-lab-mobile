// The live negative at the camera's frame rate: undo the sRGB curve of
// the finder frame, mix with the development's weights pulled back to
// linear sRGB, apply the paper, per pixel on the GPU. Zebra stripes mark
// display values above `zebra`. The same arithmetic as the Android
// shader in `MonoViewfinder.kt`.
#include <metal_stdlib>
using namespace metal;

struct FinderUniforms {
    float3 v;
    float3 vR;
    float3 vG;
    float3 vB;
    float3 lampGain;
    float plate;
    float lutLast;
    float zebra;
    // Quarter-turns clockwise to apply when the camera delivers the frame
    // in the sensor's own orientation. 0 when the connection rotated it.
    float rotation;
};

static inline float lin(float c) {
    return c <= 0.04045f ? c / 12.92f : pow((c + 0.055f) / 1.055f, 2.4f);
}

static inline float paper(float g, constant float* lut, float lutLast) {
    float x = clamp(g, 0.0f, 1.0f) * lutLast;
    int i = min(int(x), int(lutLast) - 1);
    float f = x - float(i);
    return lut[i] + (lut[i + 1] - lut[i]) * f;
}

kernel void finder(texture2d<float, access::sample> src [[texture(0)]],
                   texture2d<float, access::write> dst [[texture(1)]],
                   constant FinderUniforms& u [[buffer(0)]],
                   constant float* lut [[buffer(1)]],
                   uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
    constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
    float2 p = (float2(gid) + 0.5f) / float2(dst.get_width(), dst.get_height());
    float2 uv = p;
    int turns = int(u.rotation + 0.5f) % 4;
    if (turns == 1) uv = float2(p.y, 1.0f - p.x);        // 90 clockwise
    else if (turns == 2) uv = float2(1.0f - p.x, 1.0f - p.y);
    else if (turns == 3) uv = float2(1.0f - p.y, p.x);   // 90 counter-clockwise
    float4 c = src.sample(s, uv);
    float3 l = float3(lin(c.r), lin(c.g), lin(c.b));
    float3 o;
    if (u.plate > 0.5f) {
        // Three positives: each taking filter on the linear picture, its
        // lamp, the same paper. A clipped site clamps at 1 in every channel.
        float3 g = clamp(float3(dot(l, u.vR), dot(l, u.vG), dot(l, u.vB)), 0.0f, 1.0f) * u.lampGain;
        o = float3(paper(g.r, lut, u.lutLast), paper(g.g, lut, u.lutLast), paper(g.b, lut, u.lutLast));
    } else {
        o = float3(paper(dot(l, u.v), lut, u.lutLast));
    }
    if (u.zebra <= 1.0f && max(o.r, max(o.g, o.b)) >= u.zebra) {
        if (fmod(float(gid.x + gid.y), 18.0f) < 9.0f) o = float3(0.15f);
    }
    dst.write(float4(o, 1.0f), gid);
}
