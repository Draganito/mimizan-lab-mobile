// The viewfinder, not the print. The camera's own preview, turned upright.
// Monochrome is that picture with the colour taken out. Colour is the
// picture unchanged. Zebra marks highlights that are already white.
#include <metal_stdlib>
using namespace metal;

struct FinderUniforms {
    float mono;
    float zebra;
    float rotation;
    float pad;
};

kernel void finder(texture2d<float, access::sample> src [[texture(0)]],
                   texture2d<float, access::write> dst [[texture(1)]],
                   constant FinderUniforms& u [[buffer(0)]],
                   uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
    constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
    float2 p = (float2(gid) + 0.5f) / float2(dst.get_width(), dst.get_height());
    float2 uv = p;
    int turns = int(u.rotation + 0.5f) % 4;
    if (turns == 1) uv = float2(p.y, 1.0f - p.x);
    else if (turns == 2) uv = float2(1.0f - p.x, 1.0f - p.y);
    else if (turns == 3) uv = float2(1.0f - p.y, p.x);
    float4 c = src.sample(s, uv);
    float3 o = u.mono > 0.5f ? float3(dot(c.rgb, float3(0.2126f, 0.7152f, 0.0722f))) : c.rgb;
    if (u.zebra <= 1.0f && max(o.r, max(o.g, o.b)) >= u.zebra) {
        if (fmod(float(gid.x + gid.y), 18.0f) < 9.0f) o = float3(0.15f);
    }
    dst.write(float4(o, 1.0f), gid);
}
