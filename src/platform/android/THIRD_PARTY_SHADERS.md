# GLSL shader sources and licenses

The Android renderer adapts the following shaders from the Libretro
[GLSL shader repository](https://github.com/libretro/glsl-shaders).

- **HQ2x**: Lior Halphon (2015–2016), originally
  `hqx/shader-files/hq2x-halphon.glsl`, MIT License. The GLES2 port uses
  arithmetic bit tests where the original GLSL uses bitwise operators.
- **xBR level 2 (2xBR / 4xBR)**: Hyllian (2011–2016), originally
  `xbr/shaders/xbr-lv2.glsl`, MIT License. Both modes use the same
  algorithm with scale constants 2 and 4.
- **LCD3x**: Gigaherz, originally `handheld/shaders/lcd3x.glsl`,
  public domain. Adjusted to mGBA's source-size uniforms.
- **HQ4x menu option**: two HQ2x passes using a 2x intermediate
  framebuffer. This is *not* the original Libretro HQ4x LUT algorithm.

The shader filters are optional and selected via Graphics -> GLSL Shader.
They only change final image presentation, not emulated video output.
If a filter fails to compile or link, the renderer uses the default
nearest/bilinear shader and logs the GL compiler error.

## MIT License (HQ2x and xBR)

Copyright (c) 2015-2016 Lior Halphon

Copyright (c) 2011-2016 Hyllian

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
