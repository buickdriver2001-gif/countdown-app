#version 300 es
precision highp float;
precision mediump sampler2DArray;
in vec3 vPos;
in vec3 vNor;
in vec2 vUv;
in vec4 vCol;
in float vLamp;
flat in float vLayer;
uniform mediump sampler2DArray uTex;
uniform vec3 uCam;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uAmbSky;
uniform vec3 uAmbGround;
uniform vec3 uSkyTop;
uniform vec3 uSkyHor;
uniform vec3 uFogCol;
uniform float uFogDens;
uniform vec3 uLampCol;
uniform float uNight;
uniform float uWet;
uniform float uCloud;
uniform float uSand;
uniform float uExposure;
uniform float uCutout;
uniform float uCarLamp;
uniform vec3 uHeadPos;
uniform vec3 uHeadDir;
uniform float uHeadOn;
uniform vec2 uSiren;
uniform float uBrake;
uniform vec3 uFlash;
uniform vec3 uPaint;
uniform float uTime;
out vec4 frag;
//#include common

void main() {
    int L = int(vLayer + 0.5);
    vec3 N = normalize(vNor);
    vec3 V = normalize(uCam - vPos);
    float dist = length(uCam - vPos);
    vec3 albedo;
    float emis = vCol.a;
    vec3 emisCol = vCol.rgb;
    float gloss = 0.0;
    float spec = 0.0;

    if (L == 13) {
        // The far skyline: a silhouette in the haze, with lit windows at night (and snowy peaks)
        vec4 t = texture(uTex, vec3(vUv, 13.0));
        if (t.a < 0.5) discard;
        vec3 sil = mix(uFogCol, uAmbSky * 0.5 + uSunCol * 0.08, 0.45);
        vec3 col = sil + t.r * (uSunCol * 0.6 + uAmbSky * 0.8) * 0.7 + t.g * vec3(1.0, 0.78, 0.5) * uNight * 0.9;
        col = mix(col, uFogCol, clamp(uFogDens * 300.0, 0.0, 0.85));
        frag = vec4(tonemap(col, uExposure), 1.0);
        return;
    }
    if (L == 20) {
        // Ground: grass on the flat, rock on the steep, sand by the sea
        vec3 g = texture(uTex, vec3(vUv, 2.0)).rgb;
        vec3 r = texture(uTex, vec3(vUv * 0.6, 3.0)).rgb;
        vec3 s = texture(uTex, vec3(vUv * 1.3, 4.0)).rgb;
        float rock = 1.0 - smoothstep(0.62, 0.84, N.y);
        albedo = mix(g, r, rock);
        float beach = (1.0 - smoothstep(0.5, 3.5, vPos.y)) * uSand;
        albedo = mix(albedo, s, beach);
        // Far away, break up the repeats with big soft patches
        albedo *= 0.8 + 0.4 * vnoise(vPos.xz * 0.012);
        albedo *= vCol.rgb;
        spec = 0.05;
    } else if (L >= 14) {
        // Car parts
        albedo = vCol.rgb;
        if (L == 14) { albedo = vCol.rgb * uPaint; gloss = 1.0; spec = 0.55; }
        else if (L == 15) { albedo = vec3(0.025, 0.03, 0.04); gloss = 1.0; spec = 0.9; }
        else if (L == 16) { albedo = vec3(0.35, 0.05, 0.05); emis = uSiren.x * 2.2; emisCol = vec3(1.0, 0.12, 0.08); }
        else if (L == 17) { albedo = vec3(0.05, 0.08, 0.35); emis = uSiren.y * 2.2; emisCol = vec3(0.15, 0.35, 1.0); }
        else if (L == 18) { albedo = vec3(0.3, 0.02, 0.02); emis = 0.25 + 0.45 * uNight + 1.3 * uBrake; emisCol = vec3(1.0, 0.06, 0.03); }
    } else {
        vec4 t = texture(uTex, vec3(vUv, float(L)));
        if (uCutout > 0.5 && t.a < 0.5) discard;
        albedo = t.rgb * vCol.rgb;
        if (L >= 5 && L <= 8) {
            // Building faces: lit windows at night; the glass towers reflect the sky
            // (Each window its own light: mostly warm, some cooler; brightness from the texture)
            float wh = hash12(floor(vUv * 8.0) + vec2(float(L) * 7.0, 3.0));
            emis = t.a * uNight * (0.6 + 0.5 * wh);
            emisCol = wh > 0.72 ? vec3(0.75, 0.85, 1.0) : vec3(1.0, 0.8, 0.52);
            if (L == 5) { gloss = 0.9; spec = 0.45; } else { spec = 0.08; }
        } else if (L == 0) {
            // Asphalt: in the rain it darkens and shines, more where water gathers
            float puddle = smoothstep(0.3, 0.75, t.a);
            float w = uWet * (0.6 + 0.4 * puddle);
            albedo *= mix(1.0, 0.5, w);
            gloss = w; spec = 0.12 + 0.88 * w;
            // Fine grain close up
            albedo *= 0.92 + 0.16 * vnoise(vPos.xz * 6.0);
        } else if (L == 10 || L == 12) {
            spec = 0.3;
        }
    }

    // Light: sky and ground bounce, the sun (or moon), street lights, headlights
    float ndl = max(dot(N, uSunDir), 0.0);
    vec3 light = mix(uAmbGround, uAmbSky, N.y * 0.5 + 0.5) + uSunCol * ndl;
    float lamp = (vLamp + uCarLamp) * uNight;
    light += uLampCol * lamp * 2.2;
    if (uHeadOn > 0.0) {
        vec3 d = vPos - uHeadPos;
        float hd = length(d);
        vec3 dn = d / max(hd, 0.001);
        float cone = smoothstep(0.82, 0.97, dot(dn, uHeadDir));
        float att = 1.0 / (1.0 + hd * hd * 0.0018);
        light += vec3(1.0, 0.95, 0.85) * 2.4 * cone * att * max(dot(N, -dn), 0.0) * uHeadOn;
    }
    light += uFlash * (0.5 + 0.5 * N.y);
    vec3 col = albedo * light;

    if (spec > 0.0) {
        vec3 R = reflect(-V, N);
        float fres = pow(1.0 - max(dot(N, V), 0.0), 5.0);
        if (gloss > 0.0) {
            vec3 env = skyColor(R, uSkyTop, uSkyHor, uSunDir, uSunCol, uCloud, uNight);
            col = mix(col, env, clamp(gloss * (0.06 + 0.94 * fres) * spec * 1.3, 0.0, 0.92));
            // Wet ground glistens where the street lights fall on it
        }
        vec3 H = normalize(uSunDir + V);
        col += uSunCol * pow(max(dot(N, H), 0.0), 60.0 + 200.0 * gloss) * spec * (1.0 - uCloud * 0.6);
    }
    col += uLampCol * lamp * uWet * gloss * (0.25 + 0.9 * pow(1.0 - max(dot(N, V), 0.0), 2.0)) * 0.8;
    col += emisCol * emis;

    // Haze with distance
    float f = 1.0 - exp(-dist * dist * uFogDens * uFogDens);
    col = mix(col, uFogCol, f);
    frag = vec4(tonemap(col, uExposure), 1.0);
}
