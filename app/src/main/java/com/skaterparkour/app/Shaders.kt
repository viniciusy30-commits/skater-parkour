package com.skaterparkour.app

object Shaders {
    const val SKIN_VS = """#version 300 es
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec4 uBones[240];
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
layout(location=2) in vec2 aUv;
layout(location=3) in vec4 aJoints;
layout(location=4) in vec4 aWeights;
out vec3 vW;
out vec3 vN;
out vec2 vUv;
void main() {
    vec4 p = vec4(aPos, 1.0);
    vec3 sp = vec3(0.0);
    vec3 sn = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float w = aWeights[i];
        if (w > 0.0) {
            int j = int(aJoints[i] + 0.5) * 3;
            sp += w * vec3(dot(uBones[j], p), dot(uBones[j + 1], p), dot(uBones[j + 2], p));
            sn += w * vec3(dot(uBones[j].xyz, aNor), dot(uBones[j + 1].xyz, aNor), dot(uBones[j + 2].xyz, aNor));
        }
    }
    vec4 wp = uModel * vec4(sp, 1.0);
    vW = wp.xyz;
    vN = mat3(uModel) * sn;
    vUv = aUv;
    gl_Position = uVP * wp;
}
"""

    const val SKIN_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uNrm;
uniform vec3 uCam;
in vec3 vW;
in vec3 vN;
in vec2 vUv;
out vec4 o;
mat3 cotangent(vec3 N, vec3 p, vec2 uv) {
    vec3 dp1 = dFdx(p);
    vec3 dp2 = dFdy(p);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, N);
    vec3 dp1perp = cross(N, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    float d = max(dot(T, T), dot(B, B));
    float inv = d == 0.0 ? 0.0 : inversesqrt(d);
    return mat3(T * inv, B * inv, N);
}
void main() {
    vec3 albedo = pow(texture(uTex, vUv).rgb, vec3(2.2));
    vec3 N = normalize(vN);
    if (!gl_FrontFacing) N = -N;
    vec3 nm = texture(uNrm, vUv).xyz * 2.0 - 1.0;
    N = normalize(cotangent(N, vW, vUv) * nm);
    vec3 L = normalize(vec3(0.45, 0.85, 0.35));
    vec3 F = normalize(vec3(-0.6, 0.25, -0.7));
    vec3 V = normalize(uCam - vW);
    float d = max(dot(N, L), 0.0);
    float f = max(dot(N, F), 0.0) * 0.30;
    vec3 amb = mix(vec3(0.22, 0.22, 0.26), vec3(0.50, 0.58, 0.72), N.y * 0.5 + 0.5);
    float sp = pow(max(dot(N, normalize(L + V)), 0.0), 24.0) * 0.08;
    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.15;
    vec3 col = albedo * (amb + vec3(1.0, 0.95, 0.88) * d * 1.1 + vec3(0.7, 0.85, 1.0) * f) + vec3(sp + rim);
    col = col / (1.0 + col * 0.12);
    o = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);
}
"""

    const val WORLD_VS = """#version 300 es
uniform mat4 uVP;
uniform mat4 uModel;
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
out vec3 vW;
out vec3 vN;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vW = wp.xyz;
    vN = aNor;
    gl_Position = uVP * wp;
}
"""

    const val WORLD_FS = """#version 300 es
precision highp float;
uniform vec3 uColor;
uniform vec3 uCam;
uniform vec3 uFog;
uniform float uGrid;
in vec3 vW;
in vec3 vN;
out vec4 o;
void main() {
    vec3 N = normalize(vN);
    vec3 base = pow(uColor, vec3(2.2));
    vec2 uv = abs(N.y) > 0.5 ? vW.xz : (abs(N.x) > 0.5 ? vW.zy : vW.xy);
    vec2 g = abs(fract(uv - 0.5) - 0.5) / max(fwidth(uv), vec2(0.0001));
    float line = 1.0 - min(min(g.x, g.y), 1.0);
    base = mix(base, base * 0.5, line * 0.85 * uGrid);
    vec3 L = normalize(vec3(0.45, 0.85, 0.35));
    float d = max(dot(N, L), 0.0);
    vec3 amb = mix(vec3(0.25, 0.25, 0.28), vec3(0.52, 0.60, 0.74), N.y * 0.5 + 0.5);
    vec3 col = base * (amb + vec3(1.0, 0.95, 0.88) * d * 0.95);
    float dist = length(uCam - vW);
    float fogF = 1.0 - exp(-dist * dist * 0.00045);
    col = mix(col, pow(uFog, vec3(2.2)), clamp(fogF, 0.0, 1.0));
    col = col / (1.0 + col * 0.10);
    o = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);
}
"""

    const val SHADOW_VS = """#version 300 es
uniform mat4 uVP;
uniform mat4 uModel;
layout(location=0) in vec3 aPos;
out vec2 vP;
void main() {
    vP = aPos.xz;
    gl_Position = uVP * uModel * vec4(aPos, 1.0);
}
"""

    const val SHADOW_FS = """#version 300 es
precision mediump float;
in vec2 vP;
out vec4 o;
void main() {
    float a = (1.0 - smoothstep(0.2, 1.0, length(vP))) * 0.5;
    o = vec4(0.0, 0.0, 0.0, a);
}
"""
}
