// LCD3x by Gigaherz. Public domain. GLES 2.0 port.
// Original: libretro/glsl-shaders/handheld/shaders/lcd3x.glsl.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTexCoord;
uniform sampler2D uTexture;
uniform vec2 uInputSize;
const vec3 offsets = vec3(3.141592654) * vec3(0.5, 0.5 - 2.0 / 3.0, 0.5 - 4.0 / 3.0);
void main() {
    vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
    vec2 angle = vTexCoord * uInputSize * 6.28318530718;
    float yfactor = (16.0 + sin(angle.y)) / 17.0;
    vec3 xfactors = (4.0 + sin(angle.x + offsets)) / 5.0;
    gl_FragColor = vec4(yfactor * xfactors * rgb, 1.0);
}
