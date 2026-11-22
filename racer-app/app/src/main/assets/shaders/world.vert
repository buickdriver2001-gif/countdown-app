#version 300 es
// World and cars: everything solid, lit by the sun or moon, sky light, street lights and headlights.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aCol;
layout(location = 4) in float aLamp;
layout(location = 5) in float aLayer;
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vPos;
out vec3 vNor;
out vec2 vUv;
out vec4 vCol;
out float vLamp;
flat out float vLayer;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vPos = wp.xyz;
    vNor = mat3(uModel) * aNor;
    vUv = aUv;
    vCol = aCol;
    vLamp = aLamp;
    vLayer = aLayer;
    gl_Position = uVP * wp;
}
