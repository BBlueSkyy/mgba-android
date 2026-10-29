// HQ2x by Lior Halphon (2015-2016), MIT licensed.
// Algorithm from libretro/glsl-shaders/hqx/shader-files/hq2x-halphon.glsl.
// Port for GLES 2.0: arithmetic replacement for unsupported integer bitmasks.
// Original copyright: Copyright (c) 2015-2016 Lior Halphon.
// SPDX-License-Identifier: MIT
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexture;
uniform vec2 uInputSize;
varying vec2 vTexCoord;
#define COMPAT_TEXTURE texture2D
#define textureDimensions uInputSize
vec3 rgb_to_hq_colospace(vec4 rgb)
{
    return vec3( 0.250 * rgb.r + 0.250 * rgb.g + 0.250 * rgb.b,
                 0.250 * rgb.r - 0.000 * rgb.g - 0.250 * rgb.b,
                -0.125 * rgb.r + 0.250 * rgb.g - 0.125 * rgb.b);
}

bool is_different(vec4 a, vec4 b)
{
    vec3 diff = abs(rgb_to_hq_colospace(a) - rgb_to_hq_colospace(b));
    return diff.x > 0.188 || diff.y > 0.027 || diff.z > 0.031;
}

// GLES 2.0 has no integer bitwise operators; an eight-bit float mask is exact.
bool bitPattern(float pattern, float mask, float rule) {
    float matched = 0.0;
    for (int i = 0; i < 8; ++i) {
        float bitValue = exp2(float(i));
        float a = mod(floor(pattern / bitValue), 2.0);
        float b = mod(floor(mask / bitValue), 2.0);
        matched += a * b * bitValue;
    }
    return abs(matched - rule) < 0.5;
}
#define P(m, r) bitPattern(pattern, float(m), float(r))

vec4 interp_2px(vec4 c1, float w1, vec4 c2, float w2)
{
    return (c1 * w1 + c2 * w2) / (w1 + w2);
}

vec4 interp_3px(vec4 c1, float w1, vec4 c2, float w2, vec4 c3, float w3)
{
    return (c1 * w1 + c2 * w2 + c3 * w3) / (w1 + w2 + w3);
}

vec4 scale(sampler2D image, vec2 coord)
{
    // o = offset, the width of a pixel
    vec2 o = 1.0 / textureDimensions;
    vec2 texCoord = coord;

    /* We always calculate the top left pixel.  If we need a different pixel, we flip the image */

    // p = the position within a pixel [0...1]
    vec2 p = fract(texCoord * textureDimensions);

    if (p.x > 0.5) o.x = -o.x;
    if (p.y > 0.5) o.y = -o.y;

    vec4 w0 = COMPAT_TEXTURE(image, texCoord + vec2( -o.x, -o.y));
    vec4 w1 = COMPAT_TEXTURE(image, texCoord + vec2(    0, -o.y));
    vec4 w2 = COMPAT_TEXTURE(image, texCoord + vec2(  o.x, -o.y));
    vec4 w3 = COMPAT_TEXTURE(image, texCoord + vec2( -o.x,    0));
    vec4 w4 = COMPAT_TEXTURE(image, texCoord + vec2(    0,    0));
    vec4 w5 = COMPAT_TEXTURE(image, texCoord + vec2(  o.x,    0));
    vec4 w6 = COMPAT_TEXTURE(image, texCoord + vec2( -o.x,  o.y));
    vec4 w7 = COMPAT_TEXTURE(image, texCoord + vec2(    0,  o.y));
    vec4 w8 = COMPAT_TEXTURE(image, texCoord + vec2(  o.x,  o.y));

    float pattern = 0.0;
    if (is_different(w0, w4)) pattern += 1.0;
    if (is_different(w1, w4)) pattern += 2.0;
    if (is_different(w2, w4)) pattern += 4.0;
    if (is_different(w3, w4)) pattern += 8.0;
    if (is_different(w5, w4)) pattern += 16.0;
    if (is_different(w6, w4)) pattern += 32.0;
    if (is_different(w7, w4)) pattern += 64.0;
    if (is_different(w8, w4)) pattern += 128.0;

    if ((P(0xbf,0x37) || P(0xdb,0x13)) && is_different(w1, w5))
        return interp_2px(w4, 3.0, w3, 1.0);
    if ((P(0xdb,0x49) || P(0xef,0x6d)) && is_different(w7, w3))
        return interp_2px(w4, 3.0, w1, 1.0);
    if ((P(0x0b,0x0b) || P(0xfe,0x4a) || P(0xfe,0x1a)) && is_different(w3, w1))
        return w4;
    if ((P(0x6f,0x2a) || P(0x5b,0x0a) || P(0xbf,0x3a) || P(0xdf,0x5a) ||
         P(0x9f,0x8a) || P(0xcf,0x8a) || P(0xef,0x4e) || P(0x3f,0x0e) ||
         P(0xfb,0x5a) || P(0xbb,0x8a) || P(0x7f,0x5a) || P(0xaf,0x8a) ||
         P(0xeb,0x8a)) && is_different(w3, w1))
        return interp_2px(w4, 3.0, w0, 1.0);
    if (P(0x0b,0x08))
        return interp_3px(w4, 2.0, w0, 1.0, w1, 1.0);
    if (P(0x0b,0x02))
        return interp_3px(w4, 2.0, w0, 1.0, w3, 1.0);
    if (P(0x2f,0x2f))
        return interp_3px(w4, 1.04, w3, 1.0, w1, 1.0);
    if (P(0xbf,0x37) || P(0xdb,0x13))
        return interp_3px(w4, 5.0, w1, 2.0, w3, 1.0);
    if (P(0xdb,0x49) || P(0xef,0x6d))
        return interp_3px(w4, 5.0, w3, 2.0, w1, 1.0);
    if (P(0x1b,0x03) || P(0x4f,0x43) || P(0x8b,0x83) || P(0x6b,0x43))
        return interp_2px(w4, 3.0, w3, 1.0);
    if (P(0x4b,0x09) || P(0x8b,0x89) || P(0x1f,0x19) || P(0x3b,0x19))
        return interp_2px(w4, 3.0, w1, 1.0);
    if (P(0x7e,0x2a) || P(0xef,0xab) || P(0xbf,0x8f) || P(0x7e,0x0e))
        return interp_3px(w4, 2.0, w3, 3.0, w1, 3.0);
    if (P(0xfb,0x6a) || P(0x6f,0x6e) || P(0x3f,0x3e) || P(0xfb,0xfa) ||
        P(0xdf,0xde) || P(0xdf,0x1e))
        return interp_2px(w4, 3.0, w0, 1.0);
    if (P(0x0a,0x00) || P(0x4f,0x4b) || P(0x9f,0x1b) || P(0x2f,0x0b) ||
        P(0xbe,0x0a) || P(0xee,0x0a) || P(0x7e,0x0a) || P(0xeb,0x4b) ||
        P(0x3b,0x1b))
        return interp_3px(w4, 2.0, w3, 1.0, w1, 1.0);
    
    return interp_3px(w4, 6.0, w3, 1.0, w1, 1.0);
}

void main() {
    gl_FragColor = scale(uTexture, vTexCoord);
}
