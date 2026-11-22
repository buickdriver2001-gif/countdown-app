#version 300 es
// Effects: soft glowing sprites (lights, smoke, spray, sparks, nitro) and rain streaks.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aCol;
uniform mat4 uVP;
out vec2 vUv;
out vec4 vCol;
out float vDist;
uniform vec3 uCam;
void main() { vUv = aUv; vCol = aCol; vDist = length(aPos - uCam); gl_Position = uVP * vec4(aPos, 1.0); }
