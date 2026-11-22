// Shared by the world, water and sky shaders (pasted in after the uniforms they use).
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x), mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
    float s = 0.0; float a = 0.5;
    for (int i = 0; i < 4; i++) { s += vnoise(p) * a; p = p * 2.03 + vec2(17.1, 9.3); a *= 0.5; }
    return s;
}
// The sky in a direction (for the sky itself, and for reflections in glass, paint, wet roads and water).
vec3 skyColor(vec3 d, vec3 skyTop, vec3 skyHor, vec3 sunDir, vec3 sunCol, float cloud, float night) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = mix(skyHor, skyTop, pow(h, 0.55));
    if (d.y < 0.0) c = skyHor * (0.75 + 0.25 * exp(d.y * 6.0));
    float sd = max(dot(d, sunDir), 0.0);
    c += sunCol * (pow(sd, 6.0) * 0.18 + pow(sd, 48.0) * 0.35) * (1.0 - cloud * 0.8);
    return c;
}
vec3 tonemap(vec3 c, float exposure) {
    return vec3(1.0) - exp(-c * exposure);
}
