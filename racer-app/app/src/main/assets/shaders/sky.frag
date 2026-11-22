#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat4 uInvVP;
uniform vec3 uCam;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uSkyTop;
uniform vec3 uSkyHor;
uniform float uCloud;
uniform float uNight;
uniform float uStars;
uniform float uExposure;
uniform float uTime;
uniform float uFogDens;
out vec4 frag;
//#include common

void main() {
    vec4 p = uInvVP * vec4(vNdc, 1.0, 1.0);
    vec3 d = normalize(p.xyz / p.w - uCam);
    vec3 c = skyColor(d, uSkyTop, uSkyHor, uSunDir, uSunCol, uCloud, uNight);
    float sd = max(dot(d, uSunDir), 0.0);
    // The sun's disc (or the moon's)
    float disc = smoothstep(0.9993, 0.9997, sd);
    c += uSunCol * disc * (uNight > 0.5 ? 1.2 : 6.0) * (1.0 - uCloud * 0.85);
    // Stars
    if (uStars > 0.0 && d.y > 0.0) {
        vec2 g = d.xz / (d.y + 0.25) * 140.0;
        vec2 cell = floor(g);
        float h = hash12(cell);
        if (h > 0.985) {
            vec2 f = fract(g) - 0.5;
            float tw = 0.6 + 0.4 * sin(uTime * 2.0 + h * 50.0);
            c += vec3(0.8, 0.85, 1.0) * smoothstep(0.12, 0.0, length(f)) * uStars * tw * smoothstep(0.0, 0.25, d.y) * (1.0 - uCloud);
        }
    }
    // Clouds drifting overhead
    if (d.y > 0.0 && uCloud > 0.0) {
        vec2 q = d.xz / (d.y + 0.12) * 1.6 + vec2(uTime * 0.004, uTime * 0.002);
        float n = fbm(q);
        float cov = smoothstep(0.62 - uCloud * 0.45, 0.85 - uCloud * 0.2, n);
        vec3 cloudCol = mix(uSkyHor * 0.9, vec3(1.0) * (0.4 + 0.6 * (uSunCol.r * 0.3 + 0.2)) + uSkyHor * 0.3, 0.5);
        if (uNight > 0.5) cloudCol = uSkyHor * 1.4 + vec3(0.02);
        c = mix(c, cloudCol, cov * smoothstep(0.0, 0.18, d.y) * 0.9);
    }
    // Haze close to the horizon matches the fog
    c = mix(c, uSkyHor, clamp(uFogDens * 500.0, 0.0, 1.0) * (1.0 - smoothstep(0.0, 0.25, d.y)));
    frag = vec4(tonemap(c, uExposure), 1.0);
}
