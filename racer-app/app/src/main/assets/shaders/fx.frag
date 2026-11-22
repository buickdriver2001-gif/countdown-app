#version 300 es
precision mediump float;
in vec2 vUv;
in vec4 vCol;
in float vDist;
uniform float uFogDens;
out vec4 frag;
void main() {
    // uv in -1..1: a soft round spot (uv.y > 1.5 marks a streak: no falloff across it)
    float r = length(vUv);
    float a = vUv.y > 1.5 ? 1.0 : smoothstep(1.0, 0.0, r);
    a *= a;
    float f = exp(-vDist * vDist * uFogDens * uFogDens * 0.6);
    frag = vec4(vCol.rgb * a * vCol.a * f, a * vCol.a * f);
}
