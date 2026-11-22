#version 300 es
// A triangle covering the screen; the sky is worked out for each pixel's view direction.
layout(location = 0) in vec2 aPos;
out vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }
