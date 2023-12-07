#version 300 es

precision highp float;

uniform sampler2D uTexture;

in float alpha;
in vec2 outPosition;
in vec2 initPos;
out vec4 fragColor;

void main() {
  vec4 color = texture(uTexture, vec2(initPos.x, initPos.y));
  fragColor = vec4(color.rgb, color.a * alpha);
}