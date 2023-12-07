#version 300 es

precision highp float;

#define MAX_NUM_RECTS 30
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inVelocity;
layout(location = 2) in float inTime;
layout(location = 3) in float inDuration;

out vec2 outPosition;
out vec2 outVelocity;
out float outTime;
out float outDuration;

out float alpha;
out vec2 initPos;

uniform struct Rect {
    int left;
    int top;
    int width;
    int height;
} u_Rects[MAX_NUM_RECTS];

uniform float init;
uniform float time;
uniform float deltaTime;
uniform ivec2 size;
uniform int diameter;
uniform float seed;

float particleEaseInWindowFunction(float t) {
    return t;
}

float particleEaseInValueAt(float fraction, float t) {
    float windowSize = 0.8;
    float effectiveT = t;
    float windowStartOffset = -windowSize;
    float windowEndOffset = 1.0;
    float windowPosition = (1.0 - fraction) * windowStartOffset + fraction * windowEndOffset;
    float windowT = max(0.0, min(windowSize, effectiveT - windowPosition)) / windowSize;
    float localT = 1.0 - particleEaseInWindowFunction(windowT);
    return localT;
}

int TausStep(int z, int S1, int S2, int S3, int M) {
    int b = (((z << S1) ^ z) >> S2);
    return ((z & M) << S3) ^ b;
}

float Loki(float _seed) {
    int seed1 = int(_seed) * 1099087573;
    int seed2 = seed1;
    int seed3 = seed1;

    // Round 1: Randomise seed
    int z1 = TausStep(seed1,13,19,12,429496729);
    int z2 = TausStep(seed2,2,25,4,429496729);
    int z3 = TausStep(seed3,3,11,17,429496729);
    int z4 = (1664525 * seed1 + 1013904223);

    seed1 = (z1 ^ z2 ^ z3 ^ z4) * 2;

    // Round 2: Randomise seed again
    z1 = TausStep(seed1,13,19,12,429496729);
    z2 = TausStep(seed1,2,25,4,429496729);
    z3 = TausStep(seed1,3,11,17,429496729);
    z4 = (1664525 * seed1 + 1013904223);

    seed1 = (z1 ^ z2 ^ z3 ^ z4) * 2;

    // Round 3: Randomise seed again
    z1 = TausStep(seed1,13,19,12,429496729);
    z2 = TausStep(seed1,2,25,4,429496729);
    z3 = TausStep(seed1,3,11,17,429496729);
    z4 = (1664525 * seed1 + 1013904223);

    return float((z1 ^ z2 ^ z3 ^ z4) * 2) * 2.3283064365387e-10;
}

void main() {
    // TODO: init once, move to if
    int rectIndex = 0;
    int vertID = gl_VertexID;
    for (int i = 0; i < MAX_NUM_RECTS; i++) {
        int rectWidth = u_Rects[0].width / diameter;
        int rectHeight = u_Rects[0].height / diameter;
        int rectSize = rectWidth * rectHeight;

        if (vertID <= rectSize) {
            rectIndex = i;
            break;
        } else {
            vertID -= rectSize;
        }
    }

    vec2 offsetFromBasePosition = inPosition;
    vec2 velocity = inVelocity;
    float particleLifetime = inDuration;
    float particlePhase = inTime + deltaTime;

    Rect rect = u_Rects[rectIndex];
    int gridWidth = max(1, rect.width / diameter);
    int gridHeight = max(1, rect.height / diameter);

    float x = (float(vertID % gridWidth) * float(rect.width)) / float(gridWidth) + float(rect.left);
    float y = (float(vertID / gridWidth) * float(rect.height)) / float(gridHeight) + float(rect.top);

    initPos = vec2(x, y) / vec2(float(size.x), float(size.y));

  if (init > 0.) {
      offsetFromBasePosition = vec2(0., 0.);

      float seedVal = float(gl_VertexID) + seed;
      float direction = Loki(seedVal) * (3.14159265 * 2.0);
      float preVelocity = (0.1 + Loki(seedVal + 1.0) * (0.2 - 0.1)) * 1220.0;
      velocity = vec2(cos(direction) * preVelocity, sin(direction) * preVelocity);

      particleLifetime = 0.7 + Loki(seedVal + 2.0) * (1.5 - 0.7);
  }

    float easeInDuration = 0.8;
    float effectFraction = max(0.0, min(easeInDuration, particlePhase)) / easeInDuration;
    int particleX = vertID % gridWidth;
    float particleXFraction = float(particleX) / float(gridWidth);
    float particleFraction = particleEaseInValueAt(effectFraction, particleXFraction);

    offsetFromBasePosition += (velocity * deltaTime) * particleFraction ;
    float rng1 = Loki(float(particlePhase * deltaTime)* float(gl_VertexID));
    float rng2 = Loki(float(particlePhase * particleXFraction)* float(gl_VertexID) * 3.32);
    vec2 offsetNorm = vec2(1.0, 1.0) * 50.0 * deltaTime;
    velocity += ((-offsetNorm) * 0.5 + vec2(rng1, rng2) * offsetNorm) * particleFraction;
    velocity = velocity * (1.0 - particleFraction) + velocity * 1.001 * particleFraction;
    velocity += vec2(0., deltaTime * 120.) * particleFraction;

    particleLifetime = max(0.0, particleLifetime - deltaTime * particleFraction);

    outPosition = offsetFromBasePosition;
    outVelocity = velocity;
    outTime = particlePhase;
    outDuration = particleLifetime;

    vec2 position = (vec2(initPos.x, 1. - initPos.y) + offsetFromBasePosition / vec2(float(size.x), float(size.y)));
    gl_PointSize = float(diameter);
    gl_Position = vec4((position * 2.0 - vec2(1.0)), 0.0, 1.0);
    alpha = max(0.0, min(0.3, particleLifetime) / 0.3);
}
