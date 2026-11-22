#version 300 es
precision highp float;
in vec3 vPos;
uniform vec3 uCam;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uAmbSky;
uniform vec3 uSkyTop;
uniform vec3 uSkyHor;
uniform vec3 uFogCol;
uniform float uFogDens;
uniform float uCloud;
uniform float uNight;
uniform float uExposure;
uniform float uTime;
uniform float uRain;
out vec4 frag;
//#include common

void main() {
    // Waves: a few moving ripples for the surface's tilt
    vec2 p = vPos.xz;
    float t = uTime;
    float e = 0.6;
    float hx = (vnoise(p * 0.05 + vec2(t * 0.05, 0.0)) - vnoise(p * 0.05 + vec2(t * 0.05 + e, 0.0))) * 1.0
             + (vnoise(p * 0.23 + vec2(0.0, t * 0.11)) - vnoise(p * 0.23 + vec2(e, t * 0.11))) * 0.45;
    float hz = (vnoise(p * 0.05 + vec2(0.0, t * 0.04)) - vnoise(p * 0.05 + vec2(0.0, t * 0.04 + e))) * 1.0
             + (vnoise(p * 0.23 + vec2(t * 0.09, 0.0)) - vnoise(p * 0.23 + vec2(t * 0.09, e))) * 0.45;
    vec3 N = normalize(vec3(hx * (0.6 + uRain), 1.0, hz * (0.6 + uRain)));
    vec3 V = normalize(uCam - vPos);
    vec3 R = reflect(-V, N);
    float fres = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
    vec3 deep = vec3(0.02, 0.07, 0.1) * (uAmbSky * 1.5 + uSunCol * 0.3);
    vec3 env = skyColor(R, uSkyTop, uSkyHor, uSunDir, uSunCol, uCloud, uNight);
    vec3 col = mix(deep, env, fres);
    vec3 H = normalize(uSunDir + V);
    col += uSunCol * pow(max(dot(N, H), 0.0), 220.0) * 2.5 * (1.0 - uCloud * 0.8);
    float dist = length(uCam - vPos);
    float f = 1.0 - exp(-dist * dist * uFogDens * uFogDens);
    col = mix(col, uFogCol, f);
    frag = vec4(tonemap(col, uExposure), 1.0);
}
