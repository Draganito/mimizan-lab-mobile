// The viewfinder. Two kernels, one per kind of frame:
//
//   finder     the camera's own SDR preview (BGRA), turned upright.
//              Monochrome is that picture with the colour taken out;
//              colour is the picture unchanged.
//   finderLog  the live negative on an Apple Log frame (10-bit 4:2:0 or 4:2:2; the chroma plane is sampled normalised, so either pitch reads the same):
//              undo the log, mix with the development's weights pulled
//              back to linear BT.2020, apply the paper, per pixel. The
//              same arithmetic as the Android shader in `MonoViewfinder.kt`.
//
// Zebra stripes mark display values at or above `zebra`.
#include <metal_stdlib>
using namespace metal;

/// Mirrors `FinderUniforms` in FinderView.swift.
struct FinderUniforms {
    float3x3 ycc;
    float3 v;
    float3 vR;
    float3 vG;
    float3 vB;
    float3 lampGain;
    float gain;
    float plate;
    float lutLast;
    float zebra;
    // Quarter-turns clockwise to apply when the camera delivers the frame
    // in the sensor's own orientation. 0 when the connection rotated it.
    float rotation;
    float mono;
};

/// Where the output pixel samples the frame: the turn undone.
static inline float2 source(uint2 gid, uint w, uint h, float rotation) {
    float2 p = (float2(gid) + 0.5f) / float2(w, h);
    int turns = int(rotation + 0.5f) % 4;
    if (turns == 1) return float2(p.y, 1.0f - p.x);        // 90 clockwise
    if (turns == 2) return float2(1.0f - p.x, 1.0f - p.y);
    if (turns == 3) return float2(1.0f - p.y, p.x);        // 90 counter-clockwise
    return p;
}

static inline float3 zebra(float3 o, uint2 gid, float threshold) {
    if (threshold <= 1.0f && max(o.r, max(o.g, o.b)) >= threshold) {
        if (fmod(float(gid.x + gid.y), 18.0f) < 9.0f) return float3(0.15f);
    }
    return o;
}

kernel void finder(texture2d<float, access::sample> src [[texture(0)]],
                   texture2d<float, access::write> dst [[texture(1)]],
                   constant FinderUniforms& u [[buffer(0)]],
                   uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
    constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
    float4 c = src.sample(s, source(gid, dst.get_width(), dst.get_height(), u.rotation));
    float3 o = u.mono > 0.5f ? float3(dot(c.rgb, float3(0.2126f, 0.7152f, 0.0722f))) : c.rgb;
    dst.write(float4(zebra(o, gid, u.zebra), 1.0f), gid);
}

/// Apple Log, decoded: the constants of `AppleLog` in FinderTone.swift.
static inline float logLinear(float y) {
    const float r0 = -0.05641088f, c = 47.28711236f, beta = 0.00964052f, gamma = 0.08550479f, delta = 0.69336945f;
    const float pt = c * (0.01f - r0) * (0.01f - r0);
    if (y >= pt) return exp2((y - delta) / gamma) - beta;
    if (y > 0.0f) return sqrt(y / c) + r0;
    return r0;
}

static inline float paper(float g, constant float* lut, float lutLast) {
    float x = clamp(g, 0.0f, 1.0f) * lutLast;
    int i = min(int(x), int(lutLast) - 1);
    float f = x - float(i);
    return lut[i] + (lut[i + 1] - lut[i]) * f;
}

kernel void finderLog(texture2d<float, access::sample> luma [[texture(0)]],
                      texture2d<float, access::sample> chroma [[texture(1)]],
                      texture2d<float, access::write> dst [[texture(2)]],
                      constant FinderUniforms& u [[buffer(0)]],
                      constant float* lut [[buffer(1)]],
                      uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
    constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
    float2 uv = source(gid, dst.get_width(), dst.get_height(), u.rotation);
    // 10-bit codes left-justified in 16 bits: the texture reads them as
    // code/1023. Video range: Y' 64..940, Cb and Cr 64..960 about 512.
    float y = (luma.sample(s, uv).r - 64.0f / 1023.0f) * (1023.0f / 876.0f);
    float2 cc = (chroma.sample(s, uv).rg - 512.0f / 1023.0f) * (1023.0f / 896.0f);
    float3 enc = u.ycc * float3(y, cc.x, cc.y);
    float3 l = float3(logLinear(enc.r), logLinear(enc.g), logLinear(enc.b)) * u.gain;
    float3 o;
    if (u.plate > 0.5f) {
        // Three positives: each taking filter on the linear picture, its
        // lamp, the same paper. A clipped site clamps at 1 in every channel.
        float3 g = clamp(float3(dot(l, u.vR), dot(l, u.vG), dot(l, u.vB)), 0.0f, 1.0f) * u.lampGain;
        o = float3(paper(g.r, lut, u.lutLast), paper(g.g, lut, u.lutLast), paper(g.b, lut, u.lutLast));
    } else {
        o = float3(paper(dot(l, u.v), lut, u.lutLast));
    }
    dst.write(float4(zebra(o, gid, u.zebra), 1.0f), gid);
}
